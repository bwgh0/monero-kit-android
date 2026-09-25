package io.horizontalsystems.monerokit

import android.content.Context
import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.Wallet.Status
import io.horizontalsystems.monerokit.model.WalletListener
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
import io.horizontalsystems.monerokit.util.NodeSockets
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.concurrent.write

class WalletService(private val context: Context) {

    companion object {
        var running: Boolean = false
        private const val STATUS_UPDATE_INTERVAL = 120_000L // 120s

        // An RPC in flight past this long is presumed stuck and its connection is cut.
        private const val QUIESCE_GRACE_MS = 750L
        // What stop() still waits after the cut for store + close before handing them to the background.
        private const val CLOSE_GRACE_MS = 2_000L
        // How often an abandoned start cuts its node connection again.
        private const val START_CUT_INTERVAL_MS = 250L
    }

    private var observer: Observer? = null
    private var listener: MyWalletListener? = null

    // The node wallet2 was initialised with (WalletManager.getDaemonAddress(): resolved ip:port).
    @Volatile
    private var daemonAddress: String? = null

    // Set while quiesceRefresh() winds a pass down; its callbacks would report an interrupted pass as synced.
    @Volatile
    private var quiescing = false

    /*
     * Which lock protects what. close() deletes the native wallet, so no JNI call may reach a Wallet once
     * its close begins. wallet2 changes its subaddress tables with no lock of its own.
     *
     * walletLock, read: a getter's whole JNI sequence on the published wallet (withWallet). Held for short
     *   reads only, never across an RPC, a quiesce or a store, so a getter on Main waits a few ms at most.
     *   Getters read no subaddress table (see knownSubaddressCount): the scan grows it outside any lock.
     * walletLock, write: each change to the subaddress tables (one subaddress add), and once by the close
     *   thread as a barrier: getters that read the wallet before it was unpublished finish first.
     * sessionLock: everything that brings refresh to rest or waits on the node: a store, a subaddress add,
     *   a send, a fee estimate, start() up to the start of refresh, and the close. They run one at a time.
     *   The close waits for the one in progress. One that gets the lock after the close finds no wallet.
     * Order: sessionLock first, then walletLock. A walletLock holder never waits for sessionLock, and a
     * reader never takes the write lock.
     * wallet2's refresh thread (newBlock, refreshed, onRefreshed) takes neither lock: a sessionLock holder
     * in quiesceRefresh() waits for its pass to end. The monitor guards publishing the wallet and
     * storeWallet(). Nothing that holds the monitor waits for either lock.
     */
    private val walletLock = ReentrantReadWriteLock()
    private val sessionLock = ReentrantLock()

    // The wallet whose refresh start() started. Only that wallet is brought to rest for a store or an add.
    @Volatile
    private var refreshingWallet: Wallet? = null

    // Account 0's subaddress count, read where nothing else changes wallet2's subaddress tables: at the
    // open, after an add, after a quiesce, and on the refresh thread itself. Getters use it instead of
    // wallet2's table, which a scan resizes when a payment arrives on a later subaddress.
    @Volatile
    private var knownSubaddressCount = 1

    /** Account 0's subaddress count for getters on the published wallet. */
    internal val subaddressCount: Int
        get() = knownSubaddressCount

    private var daemonHeight: Long = 0
    private var lastDaemonStatusUpdate: Long = 0
    private var connectionStatus: Wallet.ConnectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Disconnected

    @Volatile
    var wallet: Wallet? = null
        private set

    interface Observer {
        fun onRefreshed(wallet: Wallet, fullStatus: Status, full: Boolean): Boolean
        fun onInitialWalletState(balance: Balance, txs: List<TransactionInfo?>?)
    }

    fun setObserver(obs: Observer?) {
        observer = obs
        Timber.d("Observer set: %s", observer)
    }

    fun getDaemonHeight(): Long = daemonHeight
    fun getConnectionStatus(): Wallet.ConnectionStatus = connectionStatus

    /**
     * Runs [block] on the published wallet under the read lock; null when no wallet is open. [block] must
     * be short and must not call into sessionLock (a store, an add, a send).
     */
    internal fun <T> withWallet(block: (Wallet) -> T): T? = walletLock.read {
        wallet?.let(block)
    }

    // Runs [block] on the published wallet under sessionLock; null when no wallet is open.
    private inline fun <T> withSession(block: (Wallet) -> T): T? {
        // No wallet: return now instead of waiting behind a close that holds the lock.
        if (wallet == null) return null
        return sessionLock.withLock {
            val wallet = wallet ?: return null
            block(wallet)
        }
    }

    /**
     * Initialises [wallet] for the node set in WalletManager, checks the connection and starts refresh.
     * [restoreHeight] is where a wallet that has not scanned yet starts its scan (see [initWallet]).
     * Returns null, with the wallet closed, when [cancelled] turns true before refresh starts.
     */
    fun start(
        wallet: Wallet,
        trustNode: Boolean,
        restoreHeight: Long? = null,
        cancelled: () -> Boolean = { false }
    ): Status? {
        Timber.d("start()")

        // Known before the wait for sessionLock, so an abandon can cut the connection this start makes.
        daemonAddress = WalletManager.getInstance().daemonAddress

        // A store or subaddress add in progress ends before refresh starts; later ones bring it to rest.
        val walletStatus = sessionLock.withLock {
            if (cancelled()) return@withLock null
            synchronized(this) {
                running = true
                this.wallet = wallet

                Timber.d("wallet address %s, restore height: %d", wallet.address, wallet.restoreHeight)

                // One cut can miss: after it, wallet2's TLS autodetect reconnects in plain text on a new
                // socket. While this start is abandoned, cut again until its node contact is over.
                val cutter = cutWhile(cancelled)
                val status = try {
                    initWallet(wallet, trustNode, restoreHeight)
                    wallet.fullStatus
                } finally {
                    cutter.interrupt()
                }
                Timber.tag("eee").e("+++++ initialized wallet status: $status")

                when {
                    // The connection check can outlast an abandon: refresh never starts for this start.
                    cancelled() -> null
                    status.isOk -> {
                        listener = MyWalletListener().apply { start() }
                        refreshingWallet = wallet
                        status
                    }
                    else -> status
                }
            }
        }

        // Not under sessionLock: the close that stop() starts needs it.
        if (walletStatus == null || !walletStatus.isOk) stop()
        return walletStatus
    }

    // For observer callbacks, which run on wallet2's refresh thread; other callers use storeWalletSafely().
    @Synchronized
    fun storeWallet() {
        val success = wallet?.let { storeCache(it) }
        Timber.d("Wallet stored: $success")
    }

    /** Stores from outside the refresh thread: holds the refresh thread at rest for the write, then resumes it. */
    fun storeWalletSafely(): Boolean = withSession { wallet -> atRest(wallet) { storeCache(wallet) } } ?: false

    /**
     * Adds the next subaddress of [accountIndex] and stores the wallet. Returns it, or null when no wallet
     * is open. wallet2 inserts into the subaddress map that its refresh scan reads, with no lock, so refresh
     * is brought to rest for the add. Can wait QUIESCE_GRACE_MS and a store: keep it off the main thread.
     */
    fun addSubaddress(accountIndex: Int, label: String): Subaddress? = withSession { wallet ->
        atRest(wallet) {
            walletLock.write { addNext(wallet, accountIndex, label) }?.also { storeCache(wallet) }
        }
    }

    /**
     * Adds subaddresses to [accountIndex] until it has [count] of them (indices 0 until count). Stops between
     * two adds when [cancelled] returns true. Stores nothing: it runs before the first scan, when a store
     * must not happen (see [storeCache]), so each start adds them again until the first store after a scan.
     * Returns how many it added, or null when no wallet is open.
     */
    fun ensureSubaddresses(accountIndex: Int, count: Int, cancelled: () -> Boolean): Int? = withSession { wallet ->
        atRest(wallet) {
            var added = 0
            while (!cancelled()) {
                // One add per write hold, so getters get in between the adds.
                val more = walletLock.write {
                    wallet.getNumSubaddresses(accountIndex) < count && addNext(wallet, accountIndex, "") != null
                }
                if (!more) break
                added++
            }
            added
        }
    }

    // Caller holds the write lock.
    private fun addNext(wallet: Wallet, accountIndex: Int, label: String): Subaddress? {
        // wallet2 throws for an account that does not exist, and a C++ exception through JNI aborts.
        if (accountIndex < 0 || accountIndex >= wallet.numAccounts) return null
        val before = wallet.getNumSubaddresses(accountIndex)
        wallet.addSubaddress(accountIndex, label)
        val count = wallet.getNumSubaddresses(accountIndex)
        if (accountIndex == 0) knownSubaddressCount = count
        if (count <= before) {
            Timber.w("addSubaddress: account %d still has %d subaddress(es)", accountIndex, count)
            return null
        }
        val index = count - 1
        return Subaddress(accountIndex, index, wallet.getSubaddress(accountIndex, index), label)
    }

    /**
     * Writes the wallet cache, except before the wallet's first scan. wallet2 opens a cache at height 1 as
     * a brand new wallet and scans it from the chain tip, so a restored wallet would never see its history.
     * Before that scan the cache holds nothing to keep: the keys file has the restore height.
     */
    private fun storeCache(wallet: Wallet): Boolean {
        val height = wallet.blockChainHeight
        if (height <= 1) {
            Timber.d("Wallet store skipped at height %d", height)
            return false
        }
        return wallet.store().also { stored ->
            if (!stored) Timber.w("Wallet store failed: %s", wallet.status.errorString)
        }
    }

    /**
     * Runs [block] with the refresh thread at rest, then resumes it. A wallet whose refresh start() has not
     * started has nothing to bring to rest. Caller holds sessionLock.
     */
    private inline fun <T> atRest(wallet: Wallet, block: () -> T): T {
        if (refreshingWallet !== wallet) return block()
        val rescue = daemonAddress?.let { cutConnectionAfter(it, QUIESCE_GRACE_MS) }
        try {
            try {
                quiesceRefresh(wallet)
            } finally {
                rescue?.interrupt()
            }
            // The scan may have added subaddresses; at rest, nothing else changes the table.
            knownSubaddressCount = wallet.getNumSubaddresses(0)
            return block()
        } finally {
            quiescing = false
            // Unpublished meanwhile: the wallet is closing, and a resumed pass would make the close wait for
            // its RPC. The close brings it to rest anyway.
            if (this.wallet === wallet) {
                wallet.setOffline(false)
                wallet.startRefresh()
            }
        }
    }

    /** Cuts every connection to the node, including connects still waiting for an answer. */
    fun cutNodeConnections(): Int {
        val daemon = daemonAddress ?: return 0
        return NodeSockets.abort(daemon, connecting = true).also { Timber.d("cut %d node connection(s)", it) }
    }

    /**
     * Drops the connection to the node so the next RPC dials a fresh one. A request stuck on a dead
     * connection (network change, NAT timeout, a node that stopped answering) fails now instead of after
     * wallet2's 3.5 min timeout, and the refresh pass retries on the new connection.
     */
    fun recycleConnection(): Int {
        val daemon = daemonAddress ?: return 0
        return NodeSockets.abort(daemon).also { Timber.d("recycled %d node connection(s)", it) }
    }

    /**
     * Returns once the wallet is closed, or after at most QUIESCE_GRACE_MS + CLOSE_GRACE_MS: a close still
     * running then finishes in the background, and [WalletClosings] holds back a reopen of the same wallet
     * until it is done. The caller (a wallet switch, a node change) is never held for a stuck RPC.
     */
    fun stop() {
        Timber.d("stop() listener: $listener")
        setObserver(null)
        // Unpublish first: getters and late wallet2 callbacks see null instead of a wallet being closed.
        val closing = synchronized(this) {
            val current = this.wallet
            this.wallet = null
            listener = null
            current
        }
        if (closing != null) {
            val name = closing.name
            val daemon = daemonAddress
            val closed = WalletClosings.begin(name)
            thread(name = "wallet-close") {
                try {
                    // A store, add, send or fee estimate in progress ends first.
                    sessionLock.withLock {
                        // Barrier: getters that read the wallet before it was unpublished are done with it.
                        walletLock.write {}
                        try {
                            closeWallet(closing, daemon)
                        } finally {
                            quiescing = false
                            if (refreshingWallet === closing) refreshingWallet = null
                        }
                    }
                } finally {
                    WalletClosings.end(name, closed)
                }
            }
            if (!closed.await(QUIESCE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                // An RPC in flight holds wallet2's daemon mutex, so quiesceRefresh() waits for the node to
                // answer: on a dead connection that is wallet2's 3.5 min timeout.
                val cut = daemon?.let { NodeSockets.abort(it, connecting = true) } ?: 0
                Timber.w("stop: %s busy after %d ms, cut %d node connection(s)", name, QUIESCE_GRACE_MS, cut)
                if (!closed.await(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    Timber.w("stop: %s still closing, finishing in the background", name)
                }
            }
        }
        running = false
    }

    private fun closeWallet(closing: Wallet, daemon: String?) {
        try {
            // Not under the monitor: a callback already inside onRefreshed() may still need it for
            // storeWallet(), and quiesceRefresh() waits for that callback's pass to end.
            // stop() cuts the node connection once, but the close can start later: after a store, an add or
            // a send that resumed refresh just before the unpublish, whose new pass has an RPC in flight.
            val rescue = daemon?.let { cutConnectionAfter(it, QUIESCE_GRACE_MS) }
            try {
                quiesceRefresh(closing)
            } finally {
                rescue?.interrupt()
            }
            Timber.d("Storing wallet before close")
            try {
                storeCache(closing)
            } catch (e: Exception) {
                Timber.w(e, "Failed to store wallet before close")
            }
            closing.setListener(null)
            Timber.d("Closing wallet")
            closing.close()
            Timber.d("Wallet closed")
        } catch (e: Throwable) {
            Timber.e(e, "Closing wallet failed")
        }
    }

    // Cuts the node connections every START_CUT_INTERVAL_MS while [cancelled] holds, until interrupted.
    private fun cutWhile(cancelled: () -> Boolean): Thread = thread(name = "wallet-start-cut") {
        try {
            while (true) {
                Thread.sleep(START_CUT_INTERVAL_MS)
                if (cancelled()) cutNodeConnections()
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun cutConnectionAfter(daemon: String, delayMs: Long): Thread = thread(name = "wallet-rescue") {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            return@thread
        }
        val cut = NodeSockets.abort(daemon, connecting = true)
        Timber.w("refresh busy after %d ms, cut %d node connection(s)", delayMs, cut)
    }

    /**
     * Brings wallet2's refresh thread to rest so the cache can be written. pauseRefresh() alone only keeps
     * new passes from starting: a pass already running keeps appending to the block hash chain, and a
     * store() racing it serializes that deque mid-reallocation (SIGSEGV in wallet2::store on wallet switch).
     * Offline mode fails the running pass's next RPC, interruptRefresh() ends its block loop, and refresh()
     * returns only after that pass has exited, since both take WalletImpl's refresh mutex. Offline, our own
     * refresh() pass skips the network and returns at once.
     */
    private fun quiesceRefresh(wallet: Wallet) {
        val startedAt = System.currentTimeMillis()
        quiescing = true
        wallet.pauseRefresh()
        // wallet2's refresh() re-arms its stop flag when a pass begins, so one interrupt can be lost.
        val done = AtomicBoolean(false)
        val interrupter = thread(name = "wallet-quiesce") {
            while (!done.get()) {
                wallet.interruptRefresh()
                Thread.sleep(50)
            }
        }
        try {
            // waits out an RPC in flight (wallet2's daemon RPC mutex), then drops the connection
            wallet.setOffline(true)
            wallet.refresh()
        } finally {
            done.set(true)
            interrupter.join()
        }
        Timber.d("refresh at rest after %d ms", System.currentTimeMillis() - startedAt)
    }

    fun openWallet(walletName: String, walletPassword: String): Wallet? {
        val path = Helper.getWalletFile(context, walletName).absolutePath
        val walletMgr = WalletManager.getInstance()
        Timber.d("WalletManager network=%s", walletMgr.networkType.name)

        return if (walletMgr.walletExists(path)) {
            Timber.d("open wallet %s", path)
            val wallet = walletMgr.openWallet(path, walletPassword)
            Timber.d("wallet opened")
            if (!wallet.status.isOk) {
                Timber.d("wallet status is %s", wallet.status)
                walletMgr.close(wallet)
                null
            } else {
                try {
                    wallet.refreshHistory()
                    observer?.onInitialWalletState(Balance(wallet.balance, wallet.unlockedBalance), wallet.history.all)
                } catch (err: Throwable) {
                    Timber.e(err, "error in openWallet onInitialWalletState")
                    Unit
                }
                knownSubaddressCount = wallet.getNumSubaddresses(0)
                synchronized(this) { this.wallet = wallet }
                wallet
            }
        } else {
            Timber.d("service.openWallet wallet path does not exists %s", path)
            null
        }
    }

    private fun initWallet(wallet: Wallet, trustNode: Boolean, restoreHeight: Long?) {
        Timber.d("Using daemon %s", daemonAddress)
        wallet.init(0)
        // A cache stored before its first scan (height 1) opens as a brand new wallet, and init() then moves
        // the scan start to the chain tip: the wallet would never see its history. Put the start back.
        if (restoreHeight != null) {
            val height = wallet.blockChainHeight
            if (height <= 1) {
                val from = restoreHeight.coerceAtLeast(0)
                val initFrom = wallet.restoreHeight
                if (initFrom != from) Timber.w("wallet at height %d: scan from %d, not %d", height, from, initFrom)
                wallet.setRestoreHeight(from)
            }
        }
        wallet.setTrustedDaemon(trustNode)
        wallet.setProxy(NetCipherHelper.getProxy())
    }

    private fun updateDaemonState(wallet: Wallet, height: Long) {
        val now = System.currentTimeMillis()
        if (height > 0) {
            daemonHeight = height
            connectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Connected
            lastDaemonStatusUpdate = now
        } else if (now - lastDaemonStatusUpdate > STATUS_UPDATE_INTERVAL) {
            lastDaemonStatusUpdate = now
            daemonHeight = wallet.daemonBlockChainHeight
            connectionStatus = if (daemonHeight > 0)
                Wallet.ConnectionStatus.ConnectionStatus_Connected
            else Wallet.ConnectionStatus.ConnectionStatus_Disconnected
        }
    }

    /** Wallet listener handling blockchain updates. Runs on wallet2's refresh thread: it takes neither lock. */
    private inner class MyWalletListener : WalletListener {
        var updated = true
        private var lastBlockTime = 0L
        private var lastTxCount = 0

        fun start() {
            Timber.d("WalletListener.start()")
            val wallet = wallet ?: throw IllegalStateException("No wallet!")
            wallet.setListener(this)
            wallet.startRefresh()
        }

        override fun moneySpent(txId: String, amount: Long) = Timber.d("moneySpent() $amount @ $txId")
        override fun moneyReceived(txId: String, amount: Long) = Timber.d("moneyReceived() $amount @ $txId")
        override fun unconfirmedMoneyReceived(txId: String, amount: Long) = Timber.d("unconfirmedMoneyReceived() $amount @ $txId")

        override fun newBlock(height: Long) {
            if (quiescing) return
            val wallet = wallet ?: run {
                Timber.w("newBlock() wallet is NULL")
                return
            }

            // don't flood with an update for every block ...
            if (lastBlockTime < System.currentTimeMillis() - 2000) {
                lastBlockTime = System.currentTimeMillis()
                // On the thread that grows the subaddress table, so the read cannot race it.
                knownSubaddressCount = wallet.getNumSubaddresses(0)
                Timber.d("newBlock() @ %d with observer %s", height, observer)
                if (observer != null) {
                    var fullRefresh = false
                    updateDaemonState(wallet, if (wallet.isSynchronized) height else 0)
                    if (!wallet.isSynchronized) {
                        updated = true
                        // we want to see our transactions as they come in
                        wallet.refreshHistory()
                        val txCount = wallet.getHistory().getCount()
                        if (txCount > lastTxCount) {
                            // update the transaction list only if we have more than before
                            lastTxCount = txCount
                            fullRefresh = true
                        }
                    }
                    observer?.onRefreshed(wallet, Status(), fullRefresh)
                }
            }
        }

        override fun updated() {
            Timber.d("updated()")
            updated = true
        }

        override fun refreshed() {
            Timber.d("refreshed() updated= %b", updated)
            if (quiescing) return
            val wallet = wallet ?: run {
                Timber.w("refreshed() wallet is NULL")
                return
            }
            knownSubaddressCount = wallet.getNumSubaddresses(0)

            val walletFullStatus = wallet.fullStatus
            if (!walletFullStatus.isOk) {
                observer?.onRefreshed(wallet, walletFullStatus, false)
                return
            }

            wallet.setSynchronized() // TODO sometimes called even if sync is not complete
            if (updated) {
                updateDaemonState(wallet, wallet.blockChainHeight)
                wallet.refreshHistory()
                if (observer != null) {
                    updated = !observer!!.onRefreshed(wallet, walletFullStatus, true)
                }
            }
        }
    }

    /**
     * Fee of [txData] at the node's current rates. Throws when no wallet is open, before start() has
     * initialised it for the node, and when the node does not answer.
     */
    fun estimateFee(txData: TxData): Long = withSession { wallet ->
        // Before init wallet2 has no node for its fork rules and fee rate.
        check(refreshingWallet === wallet) { "Wallet is not connected" }
        wallet.estimateTransactionFee(txData)
    } ?: throw IllegalStateException("Wallet is NULL")

    /** Creates and commits one transaction, both on the same wallet. */
    fun send(txData: TxData, notes: String?) {
        if (wallet == null) throw IllegalStateException("Create Transaction failed: Wallet is NULL")
        sessionLock.withLock {
            createTransaction(txData)
            sendTransaction(notes)
        }
    }

    fun createTransaction(txData: TxData) {
        // No wallet: fail now instead of waiting behind a close that holds the lock.
        if (wallet == null) throw IllegalStateException("Create Transaction failed: Wallet is NULL")
        sessionLock.withLock {
            val wallet = wallet ?: run {
                throw IllegalStateException("Create Transaction failed: Wallet is NULL")
            }
            // Before init there is no node, and wallet2 would start refresh for a wallet start() has not set up.
            check(refreshingWallet === wallet) { "Wallet is not connected" }
            Timber.d("CREATE TX for wallet: %s", wallet.name)

            wallet.disposePendingTransaction()
            txData.createPocketChange(wallet)

            val pendingTransaction = wallet.createTransaction(txData)
            val status = pendingTransaction.status
            if (status !== PendingTransaction.Status.Status_Ok) {
                Timber.e("Create Transaction failed: %s", pendingTransaction.getErrorString())
                throw IllegalStateException("Create Transaction failed: ${pendingTransaction.getErrorString()}")
            }
        }
    }

    fun sendTransaction(notes: String?) {
        if (wallet == null) throw IllegalStateException("Send Transaction failed: Wallet is NULL")
        sessionLock.withLock {
            val wallet = wallet ?: run {
                throw IllegalStateException("Send Transaction failed: Wallet is NULL")
            }

            Timber.d("SEND TX for wallet: %s", wallet.name)

            val pendingTransaction = wallet.pendingTransaction
            requireNotNull(pendingTransaction) { "PendingTransaction is null" }
            if (pendingTransaction.status !== PendingTransaction.Status.Status_Ok) {
                Timber.e("PendingTransaction is %s", pendingTransaction.status)

                wallet.disposePendingTransaction()
                throw IllegalStateException("Send Transaction failed: ${pendingTransaction.getErrorString()}")
            }
            val txId = pendingTransaction.getFirstTxId()
            val success = pendingTransaction.commit("", true)

            if (success) {
                wallet.disposePendingTransaction()
                if (!notes.isNullOrEmpty()) {
                    wallet.setUserNote(txId, notes)
                }

                // commit() restarts refresh, so a pass may already be running
                val rc = storeWalletSafely()
                Timber.d("wallet stored: %s with rc=%b", wallet.name, rc)
                listener?.updated = true
            } else {
                val error = pendingTransaction.getErrorString()
                wallet.disposePendingTransaction()
                throw IllegalStateException("Send Transaction failed: $error")
            }
        }
    }
}
