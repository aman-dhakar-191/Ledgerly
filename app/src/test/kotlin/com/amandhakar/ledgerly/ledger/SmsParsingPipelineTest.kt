package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.ParseClass
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * docs/parser.md's Flow diagram, exercised end to end against a real in-memory Room database.
 * Only Tier 2 (the generic extractor) is wired yet — there are no [com.amandhakar.ledgerly.database.entity.ParserRule]
 * rows in any of these scenarios, matching a fresh install.
 */
@RunWith(RobolectricTestRunner::class)
class SmsParsingPipelineTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var pipeline: SmsParsingPipeline
    private lateinit var ledgerSettingsStore: LedgerSettingsStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledgerSettingsStore = LedgerSettingsStore(ApplicationProvider.getApplicationContext())
        pipeline = SmsParsingPipeline(
            db.rawSmsDao(),
            db.senderRegistryDao(),
            db.accountDao(),
            db.transactionDao(),
            ledgerSettingsStore,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun archive(sender: String, body: String, receivedAt: Long = 2_000L, id: String = "sms-$receivedAt") {
        db.rawSmsDao().insert(
            RawSms(
                id = id,
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

    private suspend fun trustSender(senderId: String, institution: String, accountId: String? = null) {
        db.senderRegistryDao().insert(
            SenderRegistry(
                senderId = senderId,
                institution = institution,
                label = institution,
                type = SenderType.BANK,
                trusted = true,
                accountId = accountId,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
    }

    private suspend fun account(last4: String?, id: String = "acct-1") = Account(
        id = id,
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
    ).also { db.accountDao().insert(it) }

    @Test
    fun `an OTP message is ignored and never becomes a transaction`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIO-T", "ICICIO")
        archive(
            "AD-ICICIO-T",
            "798594 is One-Time Password for INR 585.28 transaction towards ZOMATO using ICICI Bank Credit Card XX6001.",
        )

        pipeline.processUnprocessed()

        val sms = db.rawSmsDao().getById("sms-2000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.IGNORED)
        assertThat(sms?.parseClass).isEqualTo(ParseClass.OTP)
        assertThat(sms?.institution).isEqualTo("ICICIO")
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
    }

    @Test
    fun `an unregistered sender is auto-registered untrusted and its message is ignored`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        archive("AD-NEWBK-S", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited. UPI:1")

        pipeline.processUnprocessed()

        val sender = db.senderRegistryDao().getById("AD-NEWBK-S")
        assertThat(sender?.trusted).isFalse()
        assertThat(sender?.type).isEqualTo(SenderType.UNKNOWN)
        assertThat(sender?.institution).isEqualTo("NEWBK")
        assertThat(db.rawSmsDao().getById("sms-2000")?.parseStatus).isEqualTo(ParseStatus.IGNORED)
    }

    @Test
    fun `a message before ledger_start_date is ignored even from a trusted sender`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(5_000L)
        trustSender("AD-ICICIT-S", "ICICIT")
        archive("AD-ICICIT-S", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited. UPI:1", receivedAt = 1_000L)

        pipeline.processUnprocessed()

        assertThat(db.rawSmsDao().getById("sms-1000")?.parseStatus).isEqualTo(ParseStatus.IGNORED)
        assertThat(db.transactionDao().getByRawSmsId("sms-1000")).isNull()
    }

    @Test
    fun `a trusted sender's transaction with a matching account produces a pending-review transaction`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive("AD-ICICIT-S", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited. UPI:1")

        pipeline.processUnprocessed()

        val sms = db.rawSmsDao().getById("sms-2000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.REVIEW)
        val txn = db.transactionDao().getByRawSmsId("sms-2000")
        assertThat(txn).isNotNull()
        assertThat(txn?.amount).isEqualTo(50_000L)
        assertThat(txn?.accountId).isEqualTo("acct-1")
        assertThat(txn?.status).isEqualTo(TransactionStatus.PENDING_REVIEW)
        assertThat(txn?.source).isEqualTo(TransactionSource.SMS_GENERIC)
        assertThat(txn?.isInternal).isFalse()
    }

    @Test
    fun `a trusted sender's transaction with no matching account is left for manual review, no transaction written`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        archive("AD-ICICIT-S", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited. UPI:1")

        pipeline.processUnprocessed()

        assertThat(db.rawSmsDao().getById("sms-2000")?.parseStatus).isEqualTo(ParseStatus.REVIEW)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
    }

    @Test
    fun `reprocessInstitution picks up previously-ignored messages once the sender becomes trusted`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        account(last4 = "924")
        archive("AD-ICICIT-S", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited. UPI:1")
        pipeline.processUnprocessed()
        assertThat(db.rawSmsDao().getById("sms-2000")?.parseStatus).isEqualTo(ParseStatus.IGNORED)

        val sender = db.senderRegistryDao().getById("AD-ICICIT-S")!!
        db.senderRegistryDao().update(sender.copy(trusted = true, type = SenderType.BANK))
        pipeline.reprocessInstitution("ICICIT")

        val sms = db.rawSmsDao().getById("sms-2000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.REVIEW)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNotNull()
    }
}
