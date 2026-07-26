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
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.database.entity.TransferKind
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.8/docs/corpus-findings.md §6: refunds and reversals through [SmsParsingPipeline] - split
 * out from [SmsParsingPipelineTest] to keep that class under detekt's `LargeClass` threshold, same
 * as [SmsParsingPipelineBnplTest]/[SmsParsingPipelineAtmTest].
 */
@RunWith(RobolectricTestRunner::class)
class SmsParsingPipelineRefundTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var pipeline: SmsParsingPipeline
    private lateinit var ledgerSettingsStore: LedgerSettingsStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledgerSettingsStore = LedgerSettingsStore(ApplicationProvider.getApplicationContext())
        val reconciler = TransactionReconciler(db.balanceAnchorDao(), db.transactionDao())
        pipeline = SmsParsingPipeline(
            db.rawSmsDao(),
            db.senderRegistryDao(),
            db.accountDao(),
            db.transactionDao(),
            db.parserRuleDao(),
            db.balanceAnchorDao(),
            db.payeeAllowlistDao(),
            reconciler,
            ledgerSettingsStore,
            CardPaymentMatcher(db.transactionDao(), db.rawSmsDao(), db.accountDao(), db.transferDao()),
            db.cardStatementDao(),
            db.transferDao(),
            RefundMatcher(db.transactionDao(), db.transferDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun archive(sender: String, body: String, receivedAt: Long, id: String = "sms-$receivedAt") {
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

    private suspend fun trustSender(senderId: String, institution: String) {
        db.senderRegistryDao().insert(
            SenderRegistry(
                senderId = senderId,
                institution = institution,
                label = institution,
                type = SenderType.BANK,
                trusted = true,
                accountId = null,
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
    fun `a partial refund nets against the matching prior spend via a linked transfer`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "6001")
        archive(
            "AD-ICICIT-S",
            "INR 500.00 spent using ICICI Bank Card XX6001 on 04-Jan-26 on AMAZON. Avl Limit: INR 15,468.00.",
            receivedAt = 1_000L,
        )
        archive(
            "AD-ICICIT-S",
            "AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on 13-JAN-26. " +
                "Revised total due Rs 5,377.55, minimum due Rs .00",
            receivedAt = 2_000L,
        )

        pipeline.processUnprocessed()

        val spend = db.transactionDao().getByRawSmsId("sms-1000")!!
        val refund = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(refund.isInternal).isTrue()
        assertThat(refund.transferId).isNotNull()
        assertThat(spend.transferId).isEqualTo(refund.transferId)
        // The spend stays a real expense (not internal) - only its effective amount is reduced,
        // which is a derived/reporting concern (effectiveAmount), not a change to the stored amount.
        assertThat(spend.isInternal).isFalse()
        assertThat(spend.amount).isEqualTo(50_000L)

        val transfer = db.transferDao().getById(spend.transferId!!)!!
        assertThat(transfer.kind).isEqualTo(TransferKind.REFUND)
        assertThat(transfer.fromTxnId).isEqualTo(spend.id)
        assertThat(transfer.toTxnId).isEqualTo(refund.id)
    }

    @Test
    fun `an unmatched refund is left as a standalone credit flagged for review, never income`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "6001")
        archive(
            "AD-ICICIT-S",
            "AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on 13-JAN-26. " +
                "Revised total due Rs 5,377.55, minimum due Rs .00",
            receivedAt = 2_000L,
        )

        pipeline.processUnprocessed()

        val refund = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(refund.isInternal).isTrue()
        assertThat(refund.transferId).isNull()
        assertThat(refund.status).isEqualTo(TransactionStatus.PENDING_REVIEW)
    }

    @Test
    fun `a refund does not match a spend from a different merchant`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "6001")
        archive(
            "AD-ICICIT-S",
            "INR 500.00 spent using ICICI Bank Card XX6001 on 04-Jan-26 on BLINKIT. Avl Limit: INR 15,468.00.",
            receivedAt = 1_000L,
        )
        archive(
            "AD-ICICIT-S",
            "AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6001 on 13-JAN-26. " +
                "Revised total due Rs 5,377.55, minimum due Rs .00",
            receivedAt = 2_000L,
        )

        pipeline.processUnprocessed()

        val refund = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(refund.transferId).isNull()
        assertThat(refund.isInternal).isTrue() // still never income, even unmatched
    }

    @Test
    fun `a failed-payment reversal (equal debit and credit, same merchant) also nets via the same mechanism`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 debited Rs. 500.00 on 01-Jan-26 to FLIPKART. Avl Bal Rs. 10,000.00",
            receivedAt = 1_000L,
        )
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 credited Rs. 500.00 on 02-Jan-26 to FLIPKART. " +
                "Payment reversed as it failed earlier. Avl Bal Rs. 10,500.00",
            receivedAt = 2_000L,
        )

        pipeline.processUnprocessed()

        val spend = db.transactionDao().getByRawSmsId("sms-1000")!!
        val reversal = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(reversal.isInternal).isTrue()
        assertThat(reversal.transferId).isEqualTo(spend.transferId)
        assertThat(spend.transferId).isNotNull()
    }
}
