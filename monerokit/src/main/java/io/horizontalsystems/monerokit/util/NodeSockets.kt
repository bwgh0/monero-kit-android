package io.horizontalsystems.monerokit.util

import timber.log.Timber

/**
 * Cuts this process's TCP connections to a daemon (abortJ in monerujo.cpp). wallet2 cannot cancel an RPC,
 * and one stuck on a dead connection holds the wallet's daemon mutex for up to its 3.5 min timeout.
 */
object NodeSockets {

    init {
        System.loadLibrary("monerujo")
    }

    /**
     * [daemonAddress] as wallet2 was given it (WalletManager.getDaemonAddress()): `ip:port`, `[ipv6]:port`,
     * optionally with an `http://` or `https://` prefix. [connecting] also cuts every connect() of this
     * process still waiting for the peer to answer (a node that drops packets never does; the destination of
     * such a socket is unknown, so pass it only while tearing a wallet down). Returns the number of cuts.
     */
    fun abort(daemonAddress: String, connecting: Boolean = false): Int {
        val (host, port) = daemonHostAndPort(daemonAddress) ?: return 0
        return try {
            abortJ(host, port, connecting)
        } catch (e: Throwable) {
            Timber.w(e, "cutting node connections failed")
            0
        }
    }

    @JvmStatic
    private external fun abortJ(host: String, port: Int, connecting: Boolean): Int
}

/** Host and port of a daemon address as wallet2 takes it; outside [NodeSockets] so it tests without the native lib. */
internal fun daemonHostAndPort(daemonAddress: String): Pair<String, Int>? {
    val address = daemonAddress.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringAfterLast('@')
        .substringBefore('/')
    val host: String
    val portText: String
    if (address.startsWith("[")) {
        val end = address.indexOf(']')
        if (end < 0) return null
        host = address.substring(1, end)
        portText = address.substring(end + 1).removePrefix(":")
    } else {
        host = address.substringBeforeLast(':', "")
        portText = address.substringAfterLast(':', "")
    }
    val port = portText.toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return host to port
}
