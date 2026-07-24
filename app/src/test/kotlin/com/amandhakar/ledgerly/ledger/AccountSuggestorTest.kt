package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
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
}
