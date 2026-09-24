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
    private var started = false

    // Set by abandonStart() for the start in progress, which then gives up; the next start() clears it.
    @Volatile
    private var stopRequested = false
    private var savingState = AtomicBoolean(false)
    private var synced = false
    private var lastStoreHeight: Long = 0

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced(SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    private val _balanceFlow = MutableStateFlow(Balance(0, 0))
    val balanceFlow = _balanceFlow.asStateFlow()

    private val _lastBlockUpdatedFlow = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lastBlockUpdatedFlow = _lastBlockUpdatedFlow.asSharedFlow()

    private val _allTransactionsFlow = MutableStateFlow<List<TransactionInfo>>(emptyList())
    val allTransactionsFlow: StateFlow<List<TransactionInfo>> = _allTransactionsFlow

    private var nodeInfo: NodeInfo? = null

    val receiveAddress: String
        get() {
            val wallet = walletService.wallet
            return if (wallet != null) {
                val lastUnusedSubaddress = getSubaddresses(wallet).drop(1).lastOrNull { it.txsCount == 0L }
                lastUnusedSubaddress?.address ?: walletService.wallet?.newSubaddress ?: ""
            } else if (seed is Seed.WatchOnly) {
                seed.address
            } else {
                getAddress(seed, accountIndex, 1)
            }
        }

    val balance: Balance
        get() = _balanceFlow.value

    /** False after a start that failed, was abandoned, or has not run yet. */
    val isStarted: Boolean
        get() = started

    val lastBlockHeight: Long?
        get() = if (walletService.getConnectionStatus() == ConnectionStatus_Connected)
            walletService.getDaemonHeight()
        else
            null

    suspend fun start() {
        startStopMutex.withLock {
            if (started) return
            stopRequested = false

            _syncStateFlow.update {
                SyncState.Connecting(true)
            }

            var kitState = KitManager.checkAndGetInitialState(kitId)

            while (kitState == KitState.Waiting && !stopRequested) {
                delay(1000)
                kitState = KitManager.checkAndGetState(kitId)
            }

            if (kitState == KitState.Running) {
                _syncStateFlow.update {
                    SyncState.Connecting(false)
                }
                started = startInternal()
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
     * The caller has moved on (another wallet tapped, the node changed) and this kit will be stopped: make
     * a start in progress give up now. A start holds the mutex through wallet2's first contact with the
     * node, a connection check that waits up to 20 s on a node that never answers, so that connection is
     * cut. Scans this process's sockets: keep it off the main thread.
     */
    fun abandonStart() {
        if (!startStopMutex.isLocked || started) return
        stopRequested = true
        walletService.cutNodeConnections()
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
            if (stopRequested) {
                walletService.stop()
                return false
            }

            nodeInfo = selectedNode
            WalletManager.getInstance().setDaemon(selectedNode)

            val status = walletService.start(wallet, trustNode)

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

        walletService.createTransaction(txData)
        walletService.sendTransaction(memo)
    }

    fun estimateFee(
        amount: Long,
        address: String,
        memo: String?,
        sweepAll: Boolean = false
    ): Long {
        val wallet = walletService.wallet ?: throw IllegalStateException("Wallet is NULL")
        val txData = buildTxData(amount, address, memo, sweepAll)

        return wallet.estimateTransactionFee(txData)
    }

    fun getSubaddresses(): List<Subaddress> {
        val wallet = walletService.wallet
        if (wallet == null) {
            if (seed is Seed.WatchOnly) {
                return listOf(Subaddress(0, 0, seed.address, ""))
            }
            return generateSubaddresses(seed, accountIndex, 2)
        }

        return getSubaddresses(wallet)
    }

    private fun getSubaddresses(wallet: Wallet): List<Subaddress> {
        val list = mutableListOf<Subaddress>()
        for (i in 0..wallet.numSubaddresses) {
            wallet.getSubaddressObject(i)?.let {
                list.add(it)
            }
        }
        return list
    }

    fun createSubaddress(): String? {
        val wallet = walletService.wallet ?: return null
        wallet.addSubaddress(accountIndex, "")
        return wallet.getLastSubaddress(accountIndex)
    }

    fun getSubaddress(accountIndex: Int, subaddressIndex: Int): Subaddress? {
        return walletService.wallet?.getSubaddressObject(accountIndex, subaddressIndex)
    }

    fun getKeys(): Keys? {
        val wallet = walletService.wallet ?: return null

        return Keys(
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
        val success = when (seed) {
            is Seed.Bip39,
            is Seed.Electrum -> {
                val electrum = seed.toElectrum()
                val offset = electrum.passphrase
                val mnemonic = electrum.mnemonic.joinToString(" ")
                val newWallet = WalletManager.getInstance().recoveryWallet(newWalletFile, walletPassword, mnemonic, offset, restoreHeight)
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
                    /* restoreHeight = */ restoreHeight,
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

        statusInfo["Node"] = nodeInfo?.name?.let { "$it (${if (trustNode) "trusted" else "untrusted"})" } ?: "NULL"
        statusInfo["Wallet Status"] = walletService.wallet?.status ?: "NULL"
        statusInfo["Sync State"] = _syncStateFlow.value.description
        statusInfo["Last Block Height"] = lastBlockHeight ?: 0L
        statusInfo["Wallet Height"] = walletService.wallet?.blockChainHeight ?: 0L
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
            val walletService = WalletService(context)
            val restoreHeight = getHeight(restoreDateOrHeight)

            NetCipherHelper.createInstance(context)

            return MoneroKit(context, seed, restoreHeight, walletId, walletService, node, trustNode)
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

        private fun generateSubaddresses(seed: Seed, accountIndex: Int, count: Int): List<Subaddress> {
            val electrumSeed = seed.toElectrum()
            val mnemonic = electrumSeed.mnemonic.joinToString(" ")
            val passphrase = electrumSeed.passphrase

            val subaddresses = mutableListOf<Subaddress>()
            for (i in 0 until count) {
                val address = WalletManager.getAddress(mnemonic, passphrase, accountIndex, i)
                val subaddress = Subaddress(accountIndex, i, address, "")
                subaddresses.add(subaddress)
            }
            return subaddresses
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
