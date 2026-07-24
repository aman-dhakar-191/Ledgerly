package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.parser.AccountSuggestion
import com.amandhakar.ledgerly.parser.SourceMessage
import com.amandhakar.ledgerly.parser.normalizeSender
import com.amandhakar.ledgerly.parser.suggestAccounts
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Task 1.9: "Accounts auto-suggested from sender + last4 combinations found in the archive." Wires
 * the pure [suggestAccounts] to the real [RawSms][com.amandhakar.ledgerly.database.entity.RawSms]
 * archive. Falls back to [normalizeSender] directly for a sender that isn't in [SenderRegistryDao]
 * yet (Task 1.4's registry prompt hasn't necessarily run for every sender before the user gets
 * here) rather than skipping those messages entirely.
 */
class AccountSuggestor @Inject constructor(
    private val rawSmsDao: RawSmsDao,
    private val senderRegistryDao: SenderRegistryDao,
) {
    suspend fun suggest(): List<AccountSuggestion> {
        val institutionBySender = senderRegistryDao.observeAll().first().associate { it.senderId to it.institution }
        val messages = rawSmsDao.observeAll().first().map { rawSms ->
            val institution = institutionBySender[rawSms.sender] ?: normalizeSender(rawSms.sender)
            SourceMessage(institution, rawSms.body, rawSms.receivedAt)
        }
        return suggestAccounts(messages)
    }
}
