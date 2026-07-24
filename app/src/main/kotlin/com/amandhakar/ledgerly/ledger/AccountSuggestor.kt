package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.parser.AccountSuggestion
import com.amandhakar.ledgerly.parser.AnchorPrefill
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.SourceMessage
import com.amandhakar.ledgerly.parser.normalizeSender
import com.amandhakar.ledgerly.parser.selectAnchorPrefill
import com.amandhakar.ledgerly.parser.suggestAccounts
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Task 1.9: "Accounts auto-suggested from sender + last4 combinations found in the archive." Wires
 * the pure [suggestAccounts]/[selectAnchorPrefill] to the real
 * [RawSms][com.amandhakar.ledgerly.database.entity.RawSms] archive. Falls back to [normalizeSender]
 * directly for a sender that isn't in [SenderRegistryDao] yet (Task 1.4's registry prompt hasn't
 * necessarily run for every sender before the user gets here) rather than skipping those messages
 * entirely.
 */
class AccountSuggestor @Inject constructor(
    private val rawSmsDao: RawSmsDao,
    private val senderRegistryDao: SenderRegistryDao,
) {
    suspend fun suggest(): List<AccountSuggestion> = suggestAccounts(sourceMessages())

    /**
     * Task 1.10 step 4: the pre-fill candidate for one already-created [account]. Null if
     * [account] has no [Account.last4] to match on (a wallet or similar account with no card/acct
     * number in its SMS), or if nothing in the archive qualifies — either way the caller falls
     * back to asking the user for a manual opening balance.
     */
    suspend fun prefillAnchor(account: Account, ledgerStartDate: Long): AnchorPrefill? {
        val last4 = account.last4 ?: return null
        val messages = sourceMessages().filter { message ->
            GenericExtractor.extract(message.body, message.receivedAt).accountLast4.value == last4
        }
        return selectAnchorPrefill(messages, ledgerStartDate)
    }

    private suspend fun sourceMessages(): List<SourceMessage> {
        val institutionBySender = senderRegistryDao.observeAll().first().associate { it.senderId to it.institution }
        return rawSmsDao.observeAll().first().map { rawSms ->
            val institution = institutionBySender[rawSms.sender] ?: normalizeSender(rawSms.sender)
            SourceMessage(institution, rawSms.body, rawSms.receivedAt)
        }
    }
}
