package io.horizontalsystems.monerokit

import android.content.Context
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
import kotlin.concurrent.thread

class WalletService(private val context: Context) {

    companion object {
        var running: Boolean = false
        private const val STATUS_UPDATE_INTERVAL = 120_000L // 120s

        // An RPC in flight past this long is presumed stuck and its connection is cut.
        private const val QUIESCE_GRACE_MS = 750L
        // What stop() still waits after the cut for store + close before handing them to the background.
        private const val CLOSE_GRACE_MS = 2_000L
    }

    private var observer: Observer? = null
    private var listener: MyWalletListener? = null

    // The node wallet2 was initialised with (WalletManager.getDaemonAddress(): resolved ip:port).
    @Volatile
    private var daemonAddress: String? = null

    // Set while quiesceRefresh() winds a pass down; its callbacks would report an interrupted pass as synced.
    @Volatile
    private var quiescing = false

    private var daemonHeight: Long = 0
    private var lastDaemonStatusUpdate: Long = 0
    private var connectionStatus: Wallet.ConnectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Disconnected

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

    @Synchronized
    fun start(wallet: Wallet, trustNode: Boolean): Status? {
        Timber.d("start()")

        running = true
        this.wallet = wallet

        Timber.d("wallet address %s, restore height: %d", wallet.address, wallet.restoreHeight)

        initWallet(wallet, trustNode)

        val walletStatus = wallet.fullStatus
        Timber.tag("eee").e("+++++ initialized wallet status: $walletStatus")

        if (!walletStatus.isOk) {
            stop()
            return walletStatus
        }

        listener = MyWalletListener().apply { start() }
        return walletStatus
    }

    // For observer callbacks, which run on wallet2's refresh thread; other callers use storeWalletSafely().
    @Synchronized
    fun storeWallet() {
        val success = wallet?.store()
        Timber.d("Wallet stored: $success")
    }

    /** Stores from outside the refresh thread: holds the refresh thread at rest for the write, then resumes it. */
    fun storeWalletSafely(): Boolean {
        val wallet = wallet ?: return false
        val rescue = daemonAddress?.let { cutConnectionAfter(it, QUIESCE_GRACE_MS) }
        try {
            quiesceRefresh(wallet)
        } finally {
            rescue?.interrupt()
        }
        return try {
            wallet.store().also { stored ->
                if (!stored) Timber.w("Wallet store failed: %s", wallet.status.errorString)
            }
        } finally {
            quiescing = false
            wallet.setOffline(false)
            wallet.startRefresh()
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
                    closeWallet(closing)
                } finally {
                    quiescing = false
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

    private fun closeWallet(closing: Wallet) {
        try {
            // Not under the monitor: a callback already inside onRefreshed() may still need it for
            // storeWallet(), and quiesceRefresh() waits for that callback's pass to end.
            quiesceRefresh(closing)
            Timber.d("Storing wallet before close")
            try {
                if (!closing.store()) Timber.w("Wallet store failed: %s", closing.status.errorString)
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
                this.wallet = wallet
                wallet
            }
        } else {
            Timber.d("service.openWallet wallet path does not exists %s", path)
            null
        }
    }

    private fun initWallet(wallet: Wallet, trustNode: Boolean) {
        daemonAddress = WalletManager.getInstance().daemonAddress
        Timber.d("Using daemon %s", daemonAddress)
        wallet.init(0)
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

    /** Wallet listener handling blockchain updates */
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

    fun createTransaction(txData: TxData) {
        val wallet = wallet ?: run {
            throw IllegalStateException("Create Transaction failed: Wallet is NULL")
        }
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

    fun sendTransaction(notes: String?) {
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
