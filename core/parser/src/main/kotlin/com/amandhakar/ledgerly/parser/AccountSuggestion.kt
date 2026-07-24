package com.amandhakar.ledgerly.parser

/**
 * Task 1.9: "Accounts auto-suggested from sender + last4 combinations found in the archive."
 * One archived message per [SourceMessage]; [GenericExtractor.extract]'s `accountLast4` field is
 * what identifies the account, grouped under the message's already-normalised institution
 * (docs/corpus-findings.md §3 — last4 is the only reliably consistent part of an account
 * reference within one institution).
 */
data class SourceMessage(val institution: String, val body: String, val receivedAt: Long)

/**
 * One (institution, last4) combination seen in the archive, with how often and how recently — the
 * caller uses these to rank and pre-fill the "add account" flow. This doesn't itself pick an
 * account type; last4 alone can't distinguish a savings account from a credit card sharing the
 * same trailing digits, so the user still confirms that.
 */
data class AccountSuggestion(
    val institution: String,
    val last4: String,
    val messageCount: Int,
    val lastSeenAt: Long,
)

fun suggestAccounts(messages: List<SourceMessage>): List<AccountSuggestion> =
    messages
        .mapNotNull { message ->
            val last4 = GenericExtractor.extract(message.body, message.receivedAt).accountLast4.value
                ?: return@mapNotNull null
            Triple(message.institution, last4, message.receivedAt)
        }
        .groupBy { (institution, last4, _) -> institution to last4 }
        .map { (key, group) ->
            AccountSuggestion(
                institution = key.first,
                last4 = key.second,
                messageCount = group.size,
                lastSeenAt = group.maxOf { it.third },
            )
        }
        .sortedWith(compareByDescending<AccountSuggestion> { it.messageCount }.thenByDescending { it.lastSeenAt })
