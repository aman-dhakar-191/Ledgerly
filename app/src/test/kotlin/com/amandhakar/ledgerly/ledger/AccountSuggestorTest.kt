package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 1.9: "Accounts auto-suggested from sender + last4 combinations found in the archive."
 * Covers the wiring itself — the extraction logic is [com.amandhakar.ledgerly.parser.AccountSuggestionTest]'s job.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSuggestorTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var suggestor: AccountSuggestor

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        suggestor = AccountSuggestor(db.rawSmsDao(), db.senderRegistryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun archive(sender: String, body: String, receivedAt: Long) {
        db.rawSmsDao().insert(
            RawSms(
                id = "sms-$receivedAt",
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = null,
                dedupeHash = computeDedupeHash(sender, receivedAt, body),
                parseStatus = ParseStatus.UNPROCESSED,
                matchedRuleId = null,
                createdAt = receivedAt,
                updatedAt = receivedAt,
                deletedAt = null,
            ),
        )
    }

    @Test
    fun `a sender in the registry is grouped under its normalized institution`() = runTest {
        db.senderRegistryDao().insert(
            SenderRegistry(
                senderId = "AD-ICICIT-S",
                institution = "ICICIT",
                label = "ICICI Bank",
                type = SenderType.BANK,
                trusted = true,
                accountId = null,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
        archive("AD-ICICIT-S", "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:1", 1_000L)
        archive("JX-ICICIT-S", "ICICI Bank Acct XX924 debited for Rs 200.00 on 10-Jun-26; Y credited. UPI:2", 2_000L)

        // JX-ICICIT-S isn't itself in the registry, but normalizeSender still collapses it to ICICIT.
        val suggestions = suggestor.suggest()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().institution).isEqualTo("ICICIT")
        assertThat(suggestions.single().messageCount).isEqualTo(2)
    }

    @Test
    fun `a sender with no registry entry still falls back to normalizeSender`() = runTest {
        archive("AD-ICICIT-S", "ICICI Bank Acct XX924 debited for Rs 500.00 on 09-Jun-26; X credited. UPI:1", 1_000L)

        val suggestions = suggestor.suggest()

        assertThat(suggestions.single().institution).isEqualTo("ICICIT")
        assertThat(suggestions.single().last4).isEqualTo("924")
    }

    private fun account(last4: String?) = Account(
        id = "acct-1",
        name = "Test",
        type = AccountType.SAVINGS,
        last4 = last4,
        currency = "INR",
        currentBalance = 0,
        balanceAsOf = 0,
        creditLimit = null,
        statementDay = null,
        dueDay = null,
        archived = false,
        createdAt = 0,
        updatedAt = 0,
        deletedAt = null,
    )

    @Test
    fun `prefillAnchor picks the earliest post-start message matching this account's last4`() = runTest {
        val ledgerStartDate = 1_000L
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 debited Rs. 5000.00 on 12-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 45,231.50",
            ledgerStartDate + 2_000,
        )
        // A different account's last4 — must not be picked.
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Credit Card XX6001 debited for INR 585.28 on 15-Jun-26 for UPI-1-ZOMATO",
            ledgerStartDate + 1_000,
        )

        val prefill = suggestor.prefillAnchor(account(last4 = "924"), ledgerStartDate)

        assertThat(prefill?.balance).isEqualTo(4_523_150L)
        assertThat(prefill?.asOf).isEqualTo(ledgerStartDate + 2_000)
    }

    @Test
    fun `prefillAnchor returns null for an account with no last4 to match on`() = runTest {
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 debited Rs. 5000.00 on 12-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 45,231.50",
            2_000L,
        )

        assertThat(suggestor.prefillAnchor(account(last4 = null), ledgerStartDate = 0L)).isNull()
    }
}
