package com.amandhakar.ledgerly.parser

/**
 * docs/parser.md's classification table. Phase 1 only tags — it does not build transfer linking,
 * card liability, or reversal netting yet (that's Phase 2), which is why `TRANSFER` isn't here:
 * detecting it needs both sides of the pair from the ledger, not just this one message.
 */
enum class TxnClass { DEBIT, CREDIT, CARD_SPEND, CARD_PAYMENT, STATEMENT, ATM_WITHDRAWAL, REVERSAL }

private val STATEMENT_SIGNAL = Regex("(?i)statement is sent|total (of|amount due)|minimum (of|amount due)")
private val REVERSAL_SIGNAL = Regex("(?i)\\brefund\\b|\\breversed\\b|\\breversal\\b")
private val CARD_PAYMENT_SIGNAL = Regex("(?i)cc payment|billdesk|(credit card.*autopay)|(payment.*received.*credit card)")
private val ATM_SIGNAL = Regex("(?i)\\batm\\b|cash wdl|withdrawn at")
private val CARD_SIGNAL = Regex("(?i)credit card|\\bcard\\b")

/**
 * Getting this wrong double-counts (docs/parser.md): a card bill payment is a transfer, not an
 * expense, and a statement is not a transaction at all. Runs on messages [classify] has already
 * let through as [ParseClass.TRANSACTION] — this is a second, finer-grained tag on top, not a
 * replacement for the pre-filter.
 */
fun classifyTransaction(body: String, direction: Direction?): TxnClass = when {
    STATEMENT_SIGNAL.containsMatchIn(body) -> TxnClass.STATEMENT
    REVERSAL_SIGNAL.containsMatchIn(body) -> TxnClass.REVERSAL
    CARD_PAYMENT_SIGNAL.containsMatchIn(body) -> TxnClass.CARD_PAYMENT
    ATM_SIGNAL.containsMatchIn(body) -> TxnClass.ATM_WITHDRAWAL
    CARD_SIGNAL.containsMatchIn(body) && direction == Direction.DEBIT -> TxnClass.CARD_SPEND
    direction == Direction.CREDIT -> TxnClass.CREDIT
    else -> TxnClass.DEBIT
}
