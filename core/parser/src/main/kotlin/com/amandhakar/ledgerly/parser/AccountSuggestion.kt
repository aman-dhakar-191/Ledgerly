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
 * same trailing digits, so the user still confirms that. [sampleMessage] is the newest matching
 * body — "ICICIT ....924" alone doesn't tell the user which real-world account that is.
 */
data class AccountSuggestion(
    val institution: String,
    val last4: String,
    val messageCount: Int,
    val lastSeenAt: Long,
    val sampleMessage: String,
)

private data class SuggestionCandidate(val institution: String, val last4: String, val receivedAt: Long, val body: String)

fun suggestAccounts(messages: List<SourceMessage>): List<AccountSuggestion> =
    messages
        .mapNotNull { message ->
            val last4 = GenericExtractor.extract(message.body, message.receivedAt).accountLast4.value
                ?: return@mapNotNull null
            SuggestionCandidate(message.institution, last4, message.receivedAt, message.body)
        }
        .groupBy { it.institution to it.last4 }
        .map { (key, group) ->
            val newest = group.maxBy { it.receivedAt }
            AccountSuggestion(
                institution = key.first,
                last4 = key.second,
                messageCount = group.size,
                lastSeenAt = newest.receivedAt,
                sampleMessage = newest.body,
            )
        }
        .sortedWith(compareByDescending<AccountSuggestion> { it.messageCount }.thenByDescending { it.lastSeenAt })
