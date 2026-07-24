package com.amandhakar.ledgerly.parser

/**
 * docs/parser.md's non-transaction pre-filter. Hardcoded, not learned — these classes carry a
 * plausible amount, merchant and account but represent no money movement, and a rule engine would
 * happily parse them as transactions.
 */
enum class ParseClass {
    TRANSACTION,
    OTP,
    DECLINED,
    SI_UPCOMING,
    SI_FAILED,
    AUTOPAY_SCHEDULED,
    COLLECT_REQUEST,
    PROMO,
    UNKNOWN,
}

// Order matters: "successfully processed payment of" (SI_PROCESSED) must never be caught by a
// broader pattern below it, and every discriminator here can appear anywhere in the body — the
// corpus puts most of them *after* the amount, so this can never stop at the first amount match.
private val TRANSACTION_OVERRIDE = Regex("(?i)successfully processed payment of")
private val DISCRIMINATORS = listOf(
    ParseClass.OTP to Regex("(?i)one-time password|\\botp\\b"),
    ParseClass.DECLINED to Regex("(?i)declined due to|is declined, as"),
    ParseClass.SI_UPCOMING to Regex("(?i)is due by|to be debited from"),
    ParseClass.SI_FAILED to Regex("(?i)could not be processed"),
    ParseClass.AUTOPAY_SCHEDULED to Regex("(?i)is scheduled on|for the upcoming mandate set for"),
    ParseClass.COLLECT_REQUEST to Regex("(?i)has requested money from you"),
)

/**
 * Classifies an SMS body against the hardcoded non-transaction pre-filter. Runs before rule
 * matching and before the generic extractor — anything other than [ParseClass.TRANSACTION] never
 * reaches them (docs/schema.md).
 *
 * Does not detect [ParseClass.PROMO]; that requires sender-registry context (marketing sender
 * class, or the message carrying no amount at all), which this pure text classifier doesn't have.
 */
@Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
fun classify(body: String): ParseClass {
    if (TRANSACTION_OVERRIDE.containsMatchIn(body)) return ParseClass.TRANSACTION
    for ((parseClass, discriminator) in DISCRIMINATORS) {
        if (discriminator.containsMatchIn(body)) return parseClass
    }
    return ParseClass.TRANSACTION
}
