package io.horizontalsystems.monerokit

/**
 * A transfer wallet2 has signed and not broadcast yet. [txids] are final from here on: a broadcast
 * that times out (wallet2 throws before it lists the transaction) or a later seed restore (the
 * history comes back without destinations) still finds the transaction by them.
 * [amount]: what the destinations receive; [fee]: the network fee. Atomic units.
 */
data class SignedTransaction(val txids: List<String>, val amount: Long, val fee: Long)

/**
 * The node refused the broadcast ("was rejected by daemon"): nothing went out. Every other
 * broadcast failure (a timeout, a lost connection) may have reached the network.
 */
class TransactionRejectedException(message: String) : IllegalStateException(message)

/**
 * The transaction a review built ([MoneroKit.prepareSend]) is gone: the wallet closed, or another build
 * replaced it. Nothing went out; build it again. The message starts as a failed build's does.
 */
class PreparedTransactionLostException : IllegalStateException("Create Transaction failed: the transaction to send is gone; build it again")
