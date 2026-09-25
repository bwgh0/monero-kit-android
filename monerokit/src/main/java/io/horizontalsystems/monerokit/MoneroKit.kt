package io.horizontalsystems.monerokit

import android.content.Context
import io.horizontalsystems.monerokit.KitManager.KitState
import io.horizontalsystems.monerokit.MoneroKit.Companion.MONERO_LEGACY_MNEMONIC_COUNT
import io.horizontalsystems.monerokit.data.NodeInfo
import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.data.UserNotes
import io.horizontalsystems.monerokit.model.NetworkType
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.Wallet.ConnectionStatus.ConnectionStatus_Connected
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
import io.horizontalsystems.monerokit.util.RestoreHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean


object KitManager {

    enum class KitState {
        Running, Waiting, Obsolete
    }

    private var runningKitId: String? = null
    private var waitingKitId: String? = null

    @Synchronized
    fun checkAndGetInitialState(kitId: String) =
        if (runningKitId != null && runningKitId != kitId) {
            waitingKitId = kitId
            KitState.Waiting
        } else {
            runningKitId = kitId
            KitState.Running
        }

    @Synchronized
    fun checkAndGetState(kitId: String) =
        if (runningKitId != null && runningKitId != kitId) {
            if (waitingKitId != null && waitingKitId == kitId) {
                KitState.Waiting
            } else {
                KitState.Obsolete
            }
        } else {
            runningKitId = kitId
            KitState.Running
        }

    @Synchronized
    fun removeRunning(kitId: String) {
        if (runningKitId == kitId) {
            runningKitId = waitingKitId
            waitingKitId = null
        } else if (waitingKitId == kitId) {
            // stopped before it ever ran: never promote it into the running slot
            waitingKitId = null
        }
    }
}

class MoneroKit(
    private val context: Context,
    private val seed: Seed,
    private val restoreHeight: Long,
    private val walletId: String,
    private val walletService: WalletService,
    private val node: String,
    private val trustNode: Boolean
) : WalletService.Observer {

    private val kitId = UUID.randomUUID().toString()
    private val accountIndex = 0
    private val startStopMutex = Mutex()
    @Volatile
    private var started = false

    // Set by abandonStart(). A start in progress gives up, and a start that has not begun yet gives up as
    // soon as it begins. Cleared when a start ends without starting, and when stop() is done.
    @Volatile
    private var stopRequested = false

    // Set by release(): the caller has dropped this instance, so it never starts again.
    @Volatile
    private var released = false
    private var savingState = AtomicBoolean(false)
    private var synced = false
    private var lastStoreHeight: Long = 0

    /**
     * How many subaddresses account 0 must have before refresh starts: a value of N means indices 0 until
     * N. start() creates the missing ones right after it opens the wallet file, while nothing scans yet, so
     * a cache rebuilt from the seed gets back the subaddresses the user made. Set it before start().
     */
    @Volatile
    var requiredSubaddressCount: Int = 0

    // A BIP39 seed takes a PBKDF2 run to convert. getInstance() already converts it, so this is at most once.
    private val electrumSeed: Seed.Electrum? by lazy {
        if (seed is Seed.WatchOnly) null else seed.toElectrum()
    }

    private val seedPrimary: String by lazy {
        if (seed is Seed.WatchOnly) seed.address else seedAddress(accountIndex, 0)
    }

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced(SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    private val _balanceFlow = MutableStateFlow(Balance(0, 0))
    val balanceFlow = _balanceFlow.asStateFlow()

    private val _lastBlockUpdatedFlow = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lastBlockUpdatedFlow = _lastBlockUpdatedFlow.asSharedFlow()

    private val _allTransactionsFlow = MutableStateFlow<List<TransactionInfo>>(emptyList())
    val allTransactionsFlow: StateFlow<List<TransactionInfo>> = _allTransactionsFlow

    private var nodeInfo: NodeInfo? = null

    /**
     * The newest subaddress (index 1 or above) that has received nothing, else the primary address. Before
     * the wallet file is open: the primary address derived from the seed. Never creates a subaddress.
     */
    val receiveAddress: String
        get() = walletService.withWallet { wallet -> unusedOrPrimaryAddress(wallet) } ?: seedPrimaryAddress()

    val balance: Balance
        get() = _balanceFlow.value

    /** False after a start that failed, was abandoned, or has not run yet. */
    val isStarted: Boolean
        get() = started

    /** True while an open wallet2 wallet is published: from the file open in start() until stop() begins its close. */
    val isWalletOpen: Boolean
        get() = walletService.wallet != null

    val lastBlockHeight: Long?
        get() = if (walletService.getConnectionStatus() == ConnectionStatus_Connected)
            walletService.getDaemonHeight()
        else
            null

    suspend fun start() {
        startStopMutex.withLock {
            if (started || released) return

            _syncStateFlow.update {
                SyncState.Connecting(true)
            }

            try {
                // Abandoned before it began: open nothing and contact no node. syncState stays Connecting.
                if (stopRequested) return

                var kitState = KitManager.checkAndGetInitialState(kitId)

                while (kitState == KitState.Waiting && !stopRequested) {
                    delay(1000)
                    kitState = KitManager.checkAndGetState(kitId)
                }

                if (kitState == KitState.Running && !stopRequested) {
                    _syncStateFlow.update {
                        SyncState.Connecting(false)
                    }
                    started = startInternal()
                }
            } finally {
                // The abandon is used up by this start, so a later start of this kit runs.
                if (!started) stopRequested = false
            }
        }
    }

    suspend fun stop() {
        abandonStart()
        startStopMutex.withLock {
            try {
                if (!started) {
                    KitManager.removeRunning(kitId)
                    return
                }

                stopInternal()
                KitManager.removeRunning(kitId)

                started = false
            } finally {
                stopRequested = false
            }
        }
    }

    /**
     * The caller drops this instance and stops it next: no start() runs on it again. A start that queued
     * behind that stop (an app resume that picked up this kit just before its release) would otherwise
     * reopen the wallet once the stop is done and keep KitManager's running slot, and the next kit would
     * wait for that slot for good. Scans this process's sockets (see [abandonStart]): keep it off the
     * main thread.
     */
    fun release() {
        released = true
        abandonStart()
    }

    /**
     * The caller has moved on (another wallet tapped, the node changed) and this kit will be stopped: make
     * its start give up now. A start holds the mutex through wallet2's first contact with the node, a
     * connection check that waits up to 20 s on a node that never answers, so that connection is cut. A
     * start that has not begun yet gives up as soon as it begins, before it opens the wallet. Scans this
     * process's sockets: keep it off the main thread.
     */
    fun abandonStart() {
        if (started) return
        stopRequested = true
        if (startStopMutex.isLocked) walletService.cutNodeConnections()
    }

    private suspend fun startInternal(): Boolean {
        try {
            // A close of this same wallet may still be finishing in the background (WalletService.stop()).
            // A stop() arriving meanwhile must not queue behind that wait: the start gives way to it.
            if (!WalletClosings.awaitSuspending(walletId, REOPEN_WAIT_MS) { stopRequested }) {
                if (!stopRequested) {
                    _syncStateFlow.update { SyncState.NotSynced(SyncError.StartError("Wallet is still closing")) }
                }
                return false
            }

            createWalletIfNotExists()

            walletService.setObserver(this@MoneroKit)
            val wallet = walletService.openWallet(walletId, "")
            if (wallet == null) {
                _syncStateFlow.update { SyncState.NotSynced(SyncError.InvalidNode("Invalid wallet")) }
                return false
            }

            val selectedNode = if (nodeInfo != null) {
                nodeInfo
            } else {
                NodeInfo.fromString(node)
            }

            if (selectedNode == null) {
                _syncStateFlow.update { SyncState.NotSynced(SyncError.InvalidNode("Invalid node")) }
                walletService.stop()
                return false
            }

            ensureRequiredSubaddresses()

            if (stopRequested) {
                walletService.stop()
                return false
            }

            nodeInfo = selectedNode
            WalletManager.getInstance().setDaemon(selectedNode)

            // An abandon during setDaemon() had no connection to cut: the service learns the node in start().
            if (stopRequested) {
                walletService.stop()
                return false
            }

            val status = walletService.start(wallet, trustNode, restoreHeight) { stopRequested }

            if (status == null || !status.isOk) {
                // an abandoned start failed because its connection was cut, not because of the node
                if (!stopRequested) {
                    _syncStateFlow.update { SyncState.NotSynced(SyncError.StartError(status?.toString() ?: "Wallet is NULL")) }
                }
                return false
            }
            return true
        } catch (ex: Exception) {
            if (!stopRequested) {
                _syncStateFlow.update { SyncState.NotSynced(SyncError.StartError(ex.message ?: ex.javaClass.simpleName)) }
            }
            // A wallet left open keeps its keys file locked: the next open of it would fail as "Invalid
            // wallet", which the app treats as a damaged cache and rebuilds from the seed.
            walletService.stop()
            return false
        }
    }

    /**
     * Creates the subaddresses [requiredSubaddressCount] asks for. Runs after the wallet file opens and
     * before refresh starts, so nothing scans while wallet2 adds them. Gives way to a stop between adds.
     */
    private fun ensureRequiredSubaddresses() {
        val required = requiredSubaddressCount
        // wallet2 always has index 0
        if (required <= 1) return
        try {
            val added = walletService.ensureSubaddresses(accountIndex, required) { stopRequested } ?: return
            if (added > 0) Timber.i("kit.start(%s): created %d subaddress(es) to reach %d", walletId, added, required)
        } catch (e: Exception) {
            // Not fatal: refresh still finds payments to them within wallet2's lookahead.
            Timber.w(e, "kit.start(%s): could not create the required subaddresses", walletId)
        }
    }

    private fun stopInternal() {
        // service.stop() stores the cache once the refresh thread is at rest
        try {
            walletService.stop()
        } catch (err: Throwable) {
            Timber.e(err, "kit.stop($walletId) error in service.stop()")
        }
    }

    // Must not take savingState: onRefreshed() spins on it from inside the refresh pass that
    // storeWalletSafely() waits out, so holding it here would deadlock.
    fun saveState() {
        walletService.storeWalletSafely()
    }

    /**
     * Drops the node connection so the next RPC dials a fresh one. Call after a network change or a long
     * stay in the background: a request stuck on a dead connection otherwise stalls sync for up to
     * wallet2's 3.5 min RPC timeout. Scans this process's sockets: keep it off the main thread.
     */
    fun recycleConnection() {
        if (started) walletService.recycleConnection()
    }

    fun send(
        amount: Long,
        address: String,
        memo: String?,
        sweepAll: Boolean = false
    ) {
        val txData = buildTxData(amount, address, memo, sweepAll)

        walletService.send(txData, memo)
    }

    fun estimateFee(
        amount: Long,
        address: String,
        memo: String?,
        sweepAll: Boolean = false
    ): Long {
        val txData = buildTxData(amount, address, memo, sweepAll)

        return walletService.estimateFee(txData)
    }

    /**
     * Account 0's subaddresses, with list position equal to addressIndex, and what each one received.
     * Before the wallet file is open: the primary address derived from the seed, index 0 only.
     */
    fun getSubaddresses(): List<Subaddress> =
        walletService.withWallet { wallet -> subaddressesOf(wallet) }
            ?: listOf(Subaddress(accountIndex, 0, seedPrimaryAddress(), ""))

    // Indices 0 until numSubaddresses: the ones wallet2 has created. Reads no wallet2 table a scan changes:
    // the count comes from WalletService, the addresses from the keys, and labels are "" (the kit writes no
    // other label).
    private fun subaddressesOf(wallet: Wallet): List<Subaddress> {
        val received = receivedPerIndex(wallet)
        val count = walletService.subaddressCount
        return List(count) { index ->
            Subaddress(accountIndex, index, wallet.getSubaddress(accountIndex, index), "").apply {
                received[index]?.let {
                    amount = it.amount
                    txsCount = it.txsCount
                }
            }
        }
    }

    private fun unusedOrPrimaryAddress(wallet: Wallet): String {
        val received = receivedPerIndex(wallet)
        val count = walletService.subaddressCount
        val unused = (count - 1 downTo 1).firstOrNull { received[it] == null } ?: 0
        return wallet.getSubaddress(accountIndex, unused)
    }

    private class Received(var amount: Long = 0, var txsCount: Long = 0)

    // Incoming totals per address index, from one pass over the history (the history holds account 0 only).
    private fun receivedPerIndex(wallet: Wallet): Map<Int, Received> {
        val totals = HashMap<Int, Received>()
        for (info in wallet.history.all) {
            if (info == null || info.direction != TransactionInfo.Direction.Direction_In) continue
            val total = totals.getOrPut(info.addressIndex) { Received() }
            total.amount += info.amount
            total.txsCount++
        }
        return totals
    }

    /**
     * Creates the next subaddress of account 0 and stores the wallet. Refresh is brought to rest for the
     * add, since wallet2's scan reads the table it changes. Returns the new subaddress (addressIndex is
     * numSubaddresses - 1 after the add), or null when no wallet is open. Blocks: call it off Main.
     */
    fun addSubaddress(label: String = ""): Subaddress? = walletService.addSubaddress(accountIndex, label)

    /** The address of a new subaddress, see [addSubaddress]. */
    fun createSubaddress(): String? = addSubaddress()?.address

    /**
     * One subaddress. From the open wallet, else derived from the seed. A WatchOnly seed has only its
     * primary address (account 0, index 0) before the wallet opens: other indices are null then.
     */
    fun getSubaddress(accountIndex: Int, subaddressIndex: Int): Subaddress? {
        if (accountIndex < 0 || subaddressIndex < 0) return null
        walletService.withWallet { wallet ->
            Subaddress(accountIndex, subaddressIndex, wallet.getSubaddress(accountIndex, subaddressIndex), "")
        }?.let { return it }
        return when {
            seed !is Seed.WatchOnly ->
                Subaddress(accountIndex, subaddressIndex, seedAddress(accountIndex, subaddressIndex), "")
            accountIndex == 0 && subaddressIndex == 0 -> Subaddress(0, 0, seed.address, "")
            else -> null
        }
    }

    /**
     * The primary address derived from the seed with the static derivation (WatchOnly: the address it was
     * made from). Depends on the seed alone, so it is the same before and after the wallet opens.
     */
    fun seedPrimaryAddress(): String = seedPrimary

    /** wallet2's primary address (account 0, index 0) of the open wallet, or null when none is open. */
    fun openWalletPrimaryAddress(): String? = walletService.withWallet { wallet -> wallet.getSubaddress(0, 0) }

    private fun seedAddress(accountIndex: Int, addressIndex: Int): String {
        val electrum = electrumSeed ?: throw IllegalStateException("A WatchOnly seed has no mnemonic")
        return WalletManager.getAddress(electrum.mnemonic.joinToString(" "), electrum.passphrase, accountIndex, addressIndex)
    }

    fun getKeys(): Keys? = walletService.withWallet { wallet ->
        Keys(
            privateSpendKey = wallet.secretSpendKey,
            publicSpendKey = wallet.publicSpendKey,
            privateViewKey = wallet.secretViewKey,
            publicViewKey = wallet.publicViewKey
        )
    }

    private fun buildTxData(
        amount: Long,
        destination: String,
        memo: String?,
        sweepAll: Boolean = false
    ) = TxData().apply {
        this.amount = if (sweepAll) Wallet.SWEEP_ALL else amount
        this.destination = destination
        mixin = MIXIN
        priority = PendingTransaction.Priority.Priority_Medium
        if (!memo.isNullOrEmpty()) {
            userNotes = UserNotes(memo)
        }
    }

    private suspend fun createWalletIfNotExists() = withContext(Dispatchers.IO) {
        // check if the wallet we want to create already exists
        val walletFolder: File = Helper.getWalletRoot(context)
        if (!walletFolder.isDirectory) {
            Timber.e("Wallet dir " + walletFolder.absolutePath + "is not a directory")
            return@withContext
        }
        val cacheFile = File(walletFolder, walletId)
        val keysFile = File(walletFolder, "$walletId.keys")
        val addressFile = File(walletFolder, "$walletId.address.txt")

        if (cacheFile.exists() || keysFile.exists() || addressFile.exists()) {
            Timber.e("Some wallet files already exist for %s", cacheFile.absolutePath)
            return@withContext
        }

        val newWalletFile = File(walletFolder, walletId)
        val walletPassword = ""
        // An unknown height is -1, which JNI hands wallet2 as 2^64 - 1: a wallet that never scans a block.
        // 0 scans from the start.
        val creationHeight = restoreHeight.coerceAtLeast(0)
        val success = when (seed) {
            is Seed.Bip39,
            is Seed.Electrum -> {
                val electrum = checkNotNull(electrumSeed)
                val offset = electrum.passphrase
                val mnemonic = electrum.mnemonic.joinToString(" ")
                val newWallet = WalletManager.getInstance().recoveryWallet(newWalletFile, walletPassword, mnemonic, offset, creationHeight)
                val success = checkAndCloseWallet(newWallet)

                val walletFile = File(walletFolder, walletId)
                walletFile.delete()

                success
            }

            is Seed.WatchOnly -> {
                val newWallet = WalletManager.getInstance().createWalletWithKeys(
                    /* aFile = */ newWalletFile,
                    /* password = */ walletPassword,
                    /* language = */ "",
                    /* restoreHeight = */ creationHeight,
                    /* addressString = */ seed.address,
                    /* viewKeyString = */ seed.viewPrivateKey,
                    /* spendKeyString = */ ""
                )

                checkAndCloseWallet(newWallet)
            }
        }

        if (success) {
            Timber.i("Created wallet in %s", newWalletFile.absolutePath)
            return@withContext
        } else {
            Timber.e("Could not create wallet in %s", newWalletFile.absolutePath)
            return@withContext
        }
    }

    // Observer ====================================

    private var firstBlock: Long = 0

    override fun onRefreshed(wallet: Wallet, fullStatus: Wallet.Status, full: Boolean): Boolean {
        Timber.d("onRefreshed() status=%s full=%b", fullStatus, full)

        if (!fullStatus.isOk) {
            _syncStateFlow.update {
                SyncState.NotSynced(IllegalStateException(fullStatus.toString()))
            }
            return false
        }

        val historyAll: List<TransactionInfo?>? = wallet.history.all

        if (historyAll != null) {
            _allTransactionsFlow.update {
                historyAll.mapNotNull { it }
            }
        }

        if (wallet.isSynchronized) {
            while (savingState.getAndSet(true)) {
                Thread.sleep(1000)
            }
            walletService.storeWallet()
            savingState.set(false)
            synced = true
        }

        if (!wallet.isSynchronized) {
            val daemonHeight: Long = walletService.getDaemonHeight()
            val walletHeight = wallet.getBlockChainHeight()

            // Periodically store wallet state during sync (every 2000 blocks)
            if (walletHeight - lastStoreHeight >= 2000) {
                if (!savingState.getAndSet(true)) {
                    walletService.storeWallet()
                    savingState.set(false)
                    lastStoreHeight = walletHeight
                }
            }
            val remainingBlocks = daemonHeight - walletHeight

            if (firstBlock == 0L) {
                firstBlock = walletHeight
            }

            Timber.i(
                "firstBlock: %d, daemonHeight: %d, walletHeight: %d, remainingBlocks: %d",
                firstBlock,
                daemonHeight,
                walletHeight,
                remainingBlocks
            )
            val totalBlocks = daemonHeight - firstBlock
            val progress: Double = if (totalBlocks > 0) {
                1 - remainingBlocks.toDouble() / totalBlocks
            } else {
                1.0
            }

            _syncStateFlow.update {
                SyncState.Syncing(progress, remainingBlocks)
            }
        } else {
            _syncStateFlow.update {
                SyncState.Synced
            }
        }

        _lastBlockUpdatedFlow.tryEmit(Unit)

        // The callback's own wallet: service.stop() clears walletService.wallet while a pass may
        // still be reporting, which would publish a zero balance for this wallet.
        _balanceFlow.update {
            Balance(wallet.balance, wallet.unlockedBalance)
        }

        return true
    }

    override fun onInitialWalletState(balance: Balance, txs: List<TransactionInfo?>?) {
        _balanceFlow.update {
            balance
        }

        txs?.let {
            _allTransactionsFlow.update {
                txs.mapNotNull { it }
            }
        }
    }

    private fun checkAndCloseWallet(aWallet: Wallet): Boolean {
        val walletStatus = aWallet.status
        if (!walletStatus.isOk) {
            Timber.tag("eee").e(walletStatus.errorString)
            throw IllegalStateException("Wallet recovery error: ${walletStatus.errorString}")
        }
        aWallet.close()
        return walletStatus.isOk
    }

    fun statusInfo(): Map<String, Any> {
        val statusInfo = LinkedHashMap<String, Any>()

        val (walletStatus, walletHeight) = walletService.withWallet { it.status to it.blockChainHeight } ?: (null to 0L)

        statusInfo["Node"] = nodeInfo?.name?.let { "$it (${if (trustNode) "trusted" else "untrusted"})" } ?: "NULL"
        statusInfo["Wallet Status"] = walletStatus ?: "NULL"
        statusInfo["Sync State"] = _syncStateFlow.value.description
        statusInfo["Last Block Height"] = lastBlockHeight ?: 0L
        statusInfo["Wallet Height"] = walletHeight
        statusInfo["Daemon Height"] = walletService.getDaemonHeight()
        statusInfo["Connection Status"] = walletService.getConnectionStatus()
        statusInfo["Kit started"] = started
        statusInfo["Service running"] = WalletService.running

        return statusInfo
    }

    sealed class SyncError : Error() {
        object NotStarted : SyncError() {
            override val message = "Not Started"
        }

        data class InvalidNode(override val message: String) : SyncError()
        data class StartError(override val message: String) : SyncError()
    }

    companion object {
        const val MIXIN: Int = 0
        const val MONERO_LEGACY_MNEMONIC_COUNT = 25

        // wallet2's 3.5 min RPC timeout bounds a close left in the background, plus store/close headroom
        private const val REOPEN_WAIT_MS = 240_000L

        fun getInstance(
            context: Context,
            seed: Seed.Bip39,
            restoreDateOrHeight: String,
            walletId: String,
            node: String,
            trustNode: Boolean
        ): MoneroKit {
            return getInstance(context, seed.toElectrum(), restoreDateOrHeight, walletId, node, trustNode)
        }

        fun getInstance(
            context: Context,
            seed: Seed,
            restoreDateOrHeight: String,
            walletId: String,
            node: String,
            trustNode: Boolean
        ): MoneroKit {
            // The kit uses the Electrum form only: a BIP39 seed is converted (PBKDF2) once, here.
            val kitSeed = if (seed is Seed.Bip39) seed.toElectrum() else seed
            val walletService = WalletService(context)
            val restoreHeight = getHeight(restoreDateOrHeight)

            NetCipherHelper.createInstance(context)

            return MoneroKit(context, kitSeed, restoreHeight, walletId, walletService, node, trustNode)
        }

        fun validateAddress(address: String) {
            if (!Wallet.isAddressValid(address)) {
                throw IllegalArgumentException("Invalid address")
            }
        }

        fun validatePrivateViewKey(privateViewKey: String, address: String) {
            val error = Wallet.isPrivateViewKeyValid(privateViewKey, address)
            check(error == null) { error }
        }

        fun validatePrivateSpendKey(privateSpendKey: String, address: String) {
            val error = Wallet.isPrivateSpendKeyValid(privateSpendKey, address)
            check(error == null) { error }
        }

        fun getKeys(seed: Seed): Keys {
            val electrumSeed = seed.toElectrum()
            val mnemonic = electrumSeed.mnemonic.joinToString(" ")
            val passphrase = electrumSeed.passphrase

            val privateSpendKey = WalletManager.getPrivateSpendKey(mnemonic, passphrase)
            val publicSpendKey = WalletManager.getPublicSpendKey(mnemonic, passphrase)
            val privateViewKey = WalletManager.getPrivateViewKey(mnemonic, passphrase)
            val publicViewKey = WalletManager.getPublicViewKey(mnemonic, passphrase)

            return Keys(privateSpendKey, publicSpendKey, privateViewKey, publicViewKey)
        }

        fun getAddress(seed: Seed, accountIndex: Int, addressIndex: Int): String {
            val electrumSeed = seed.toElectrum()
            val mnemonic = electrumSeed.mnemonic.joinToString(" ")
            val passphrase = electrumSeed.passphrase

            return WalletManager.getAddress(mnemonic, passphrase, accountIndex, addressIndex)
        }

        fun restoreHeightForNewWallet(): Long {
            return RestoreHeight.getInstance().getHeight(Calendar.getInstance().getTime())
        }

        private fun getHeight(input: String): Long {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return -1

            val walletManager = WalletManager.getInstance()
            val restoreHeight = RestoreHeight.getInstance()

            var height = -1L

            if (walletManager.networkType == NetworkType.NetworkType_Mainnet) {
                // Try parsing as date (yyyy-MM-dd)
                height = runCatching {
                    SimpleDateFormat("yyyy-MM-dd").apply { isLenient = false }.parse(trimmed)?.let { restoreHeight.getHeight(it) }
                }.getOrNull() ?: -1

                // Try parsing as date (yyyyMMdd) if previous failed
                if (height < 0 && trimmed.length == 8) {
                    height = runCatching {
                        SimpleDateFormat("yyyyMMdd").apply { isLenient = false }.parse(trimmed)?.let { restoreHeight.getHeight(it) }
                    }.getOrNull() ?: -1
                }
            }

            // If still invalid, try numeric height
            if (height < 0) {
                height = trimmed.toLongOrNull() ?: -1
            }

            Timber.d("Using Restore Height = %d", height)
            return height
        }

        fun deleteWallet(context: Context, walletId: String): Boolean {
            // A close still in progress would write the cache back after the delete.
            if (!WalletClosings.await(walletId, REOPEN_WAIT_MS)) {
                Timber.w("deleteWallet: %s is still closing", walletId)
            }
            val walletFile: File = Helper.getWalletFile(context, walletId)

            return deleteWallet(walletFile)
        }

        private fun deleteWallet(walletFile: File): Boolean {
            Timber.d("deleteWallet %s", walletFile.absolutePath)
            val dir = walletFile.getParentFile()
            val name = walletFile.getName()
            var success = true
            val cacheFile = File(dir, name)
            if (cacheFile.exists()) {
                success = cacheFile.delete()
            }
            success = File(dir, "$name.keys").delete() && success
            val addressFile = File(dir, "$name.address.txt")
            if (addressFile.exists()) {
                success = addressFile.delete() && success
            }
            Timber.d("deleteWallet is %s", success)
            return success
        }

    }
}

fun ByteArray?.toRawHexString(): String {
    return this?.joinToString(separator = "") {
        it.toInt().and(0xff).toString(16).padStart(2, '0')
    } ?: ""
}

fun ByteArray?.toHexString(): String {
    val rawHex = this?.toRawHexString() ?: return ""
    return "0x$rawHex"
}

data class Balance(
    val all: Long,
    val unlocked: Long
)

data class Keys(
    val privateSpendKey: String,
    val publicSpendKey: String,
    val privateViewKey: String,
    val publicViewKey: String
)

sealed class Seed {
    data class Electrum(val mnemonic: List<String>, val passphrase: String) : Seed() {
        init {
            check(mnemonic.size == MONERO_LEGACY_MNEMONIC_COUNT) { "Illegal Electrum Seed" }
        }
    }

    data class Bip39(val mnemonic: List<String>, val passphrase: String) : Seed() {
        init {
            check(mnemonic.size in listOf(12, 18, 24)) { "Illegal Bip39 Seed" }
        }
    }

    data class WatchOnly(val address: String, val viewPrivateKey: String) : Seed()
}

fun Seed.toElectrum() = when (this) {
    is Seed.Bip39 -> {
        val moneroMnemonic = CakeWalletStyleConverter.getLegacySeedFromBip39(mnemonic, passphrase)
            ?: throw IllegalArgumentException("BIP39 mnemonic can't be converted to Monero Legacy Mnemonic")
        Seed.Electrum(moneroMnemonic, "")
    }

    is Seed.WatchOnly -> {
        throw IllegalArgumentException("WatchOnly can't be converted to Monero Legacy Mnemonic")
    }

    is Seed.Electrum -> this
}
