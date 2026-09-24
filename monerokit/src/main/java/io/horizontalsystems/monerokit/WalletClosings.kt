package io.horizontalsystems.monerokit

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wallet closes that may outlive WalletService.stop(), keyed by wallet file name. Until a close is done,
 * wallet2 still holds the wallet's keys file lock and has yet to write its cache, so the same wallet must
 * not be opened again, nor its files deleted.
 */
internal object WalletClosings {

    private val pending = ConcurrentHashMap<String, CountDownLatch>()

    fun begin(walletName: String): CountDownLatch = CountDownLatch(1).also { pending[walletName] = it }

    fun end(walletName: String, closed: CountDownLatch) {
        closed.countDown()
        pending.remove(walletName, closed)
    }

    /** Blocks until [walletName] has no close in progress; false when [timeoutMs] ran out first. */
    fun await(walletName: String, timeoutMs: Long): Boolean =
        pending[walletName]?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: true

    /** Suspends until [walletName] has no close in progress; false when [timeoutMs] ran out or [cancelled]. */
    suspend fun awaitSuspending(walletName: String, timeoutMs: Long, cancelled: () -> Boolean): Boolean {
        val closed = pending[walletName] ?: return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (closed.count > 0) {
            if (cancelled() || System.currentTimeMillis() >= deadline) return false
            delay(100)
        }
        return true
    }
}
