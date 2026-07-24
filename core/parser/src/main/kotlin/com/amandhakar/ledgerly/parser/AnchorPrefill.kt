package com.amandhakar.ledgerly.parser

/** A candidate opening `BalanceAnchor`, before the user has confirmed or overridden it. */
data class AnchorPrefill(val balance: Long, val asOf: Long)

/**
 * Task 1.10 step 4: "Pre-fill a BalanceAnchor per account from the earliest post-start message
 * carrying a balance (source = SMS_DERIVED)." [messages] must already be filtered to one account
 * (its institution and last4) by the caller — this only knows about [ledgerStartDate] and each
 * message's own content.
 *
 * Returns null if nothing at or after [ledgerStartDate] carries a balance; the caller then falls
 * back to asking the user for a manual opening balance (`source = OPENING` instead).
 */
fun selectAnchorPrefill(messages: List<SourceMessage>, ledgerStartDate: Long): AnchorPrefill? =
    messages
        .filter { it.receivedAt >= ledgerStartDate }
        .sortedBy { it.receivedAt }
        .firstNotNullOfOrNull { message ->
            GenericExtractor.extract(message.body, message.receivedAt).balanceAfter.value
                ?.let { balance -> AnchorPrefill(balance, message.receivedAt) }
        }
