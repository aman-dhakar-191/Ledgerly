package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.CardStatement
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.ParseClass
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.6/docs/corpus-findings.md §10: axio (Amazon Pay Later) end-to-end through
 * [SmsParsingPipeline] - split out from [SmsParsingPipelineTest] to keep that class under
 * detekt's `LargeClass` threshold rather than growing one already-large integration test file
 * further with every new account type.
 */
@RunWith(RobolectricTestRunner::class)
class SmsParsingPipelineBnplTest {

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

    private suspend fun bnplAccount(id: String = "bnpl-1", creditLimit: Long? = null) = Account(
        id = id,
        name = "Test BNPL",
        type = AccountType.BNPL,
        last4 = null,
        currency = "INR",
        currentBalance = 0,
        balanceAsOf = 0,
        creditLimit = creditLimit,
        statementDay = null,
        dueDay = null,
        archived = false,
        createdAt = 0,
        updatedAt = 0,
        deletedAt = null,
    ).also { db.accountDao().insert(it) }

    @Test
    fun `axio's no-space BNPL spend format resolves via the sender's linked account as a debit with no merchant`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val bnplId = "bnpl-1"
        bnplAccount(id = bnplId)
        trustSender("JX-axioFS-S", "axioFS", accountId = bnplId)
        archive(
            "JX-axioFS-S",
            "Thank you for availing Pay Later credit of Rs656.7. For more info click http://axio.example " +
                "To report misuse call 18009877678 -axio",
        )

        pipeline.processUnprocessed()

        val txn = db.transactionDao().getByRawSmsId("sms-2000")
        assertThat(txn?.accountId).isEqualTo(bnplId)
        assertThat(txn?.amount).isEqualTo(65_670L)
        assertThat(txn?.direction).isEqualTo(Direction.DEBIT)
        assertThat(txn?.merchantRaw).isNull()
    }

    @Test
    fun `axio's EMI-eligible BNPL spend variant also resolves as a debit`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val bnplId = "bnpl-1"
        bnplAccount(id = bnplId)
        trustSender("JX-axioFS-S", "axioFS", accountId = bnplId)
        archive(
            "JX-axioFS-S",
            "Thanks for availing Rs4848.99 Pay Later credit. For more info on EMI, Rate of Interest & Tenure " +
                "click http://axio.example -axio",
        )

        pipeline.processUnprocessed()

        val txn = db.transactionDao().getByRawSmsId("sms-2000")
        assertThat(txn?.accountId).isEqualTo(bnplId)
        assertThat(txn?.amount).isEqualTo(484_899L)
        assertThat(txn?.direction).isEqualTo(Direction.DEBIT)
    }

    @Test
    fun `axio's BNPL bill due notice parses as a statement, never a transaction`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val bnplId = "bnpl-1"
        bnplAccount(id = bnplId)
        trustSender("JX-axioFS-S", "axioFS", accountId = bnplId)
        archive(
            "JX-axioFS-S",
            "Your Pay Later bill of Rs 1698 will be debited on 5th of this month from registered bank a/c. " +
                "View Bill http://axio.example -axio",
        )

        pipeline.processUnprocessed()

        assertThat(db.rawSmsDao().getById("sms-2000")?.parseStatus).isEqualTo(ParseStatus.PARSED)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
        val statement = db.cardStatementDao().getByRawSmsId("sms-2000")
        assertThat(statement?.totalDue).isEqualTo(169_800L)
        assertThat(statement?.minimumDue).isEqualTo(169_800L)
    }

    @Test
    fun `a BNPL bill mismatched against spends since the last bill creates a flagged adjustment`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val bnplId = "bnpl-1"
        bnplAccount(id = bnplId)
        trustSender("JX-axioFS-S", "axioFS", accountId = bnplId)
        db.cardStatementDao().insert(
            CardStatement(
                id = "stmt-prev",
                accountId = bnplId,
                totalDue = 50_000L,
                minimumDue = 50_000L,
                dueDate = 1_500L,
                statementDate = 1_000L,
                rawSmsId = null,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                deletedAt = null,
            ),
        )
        db.transactionDao().insert(
            Transaction(
                id = "spend-1",
                accountId = bnplId,
                amount = 10_000L, // Rs 100.00 spend -> expected new total is Rs 600.00
                direction = Direction.DEBIT,
                occurredAt = 1_200L,
                merchantRaw = null,
                balanceAfter = null,
                rawSmsId = null,
                source = TransactionSource.SMS_GENERIC,
                status = TransactionStatus.CONFIRMED,
                transferId = null,
                isInternal = false,
                notes = null,
                createdAt = 1_200L,
                updatedAt = 1_200L,
                deletedAt = null,
            ),
        )
        // States Rs 650.00 - Rs 50.00 more than the spend alone explains.
        archive(
            "JX-axioFS-S",
            "Your Pay Later bill of Rs 650 will be debited on 5th of this month from registered bank a/c. " +
                "View Bill http://axio.example -axio",
        )

        pipeline.processUnprocessed()

        val adjustment = db.transactionDao().observeByStatus(TransactionStatus.PENDING_REVIEW).first()
            .single { it.source == TransactionSource.ADJUSTMENT }
        assertThat(adjustment.amount).isEqualTo(5_000L) // Rs 50.00
        assertThat(adjustment.accountId).isEqualTo(bnplId)
    }

    @Test
    fun `axio's BNPL credit limit change updates the account's credit limit, never a transaction`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val bnplId = "bnpl-1"
        bnplAccount(id = bnplId, creditLimit = 2_000_000L)
        trustSender("JX-axioFS-S", "axioFS", accountId = bnplId)
        archive(
            "JX-axioFS-S",
            "Approved credit for your Pay Later account has been modified to Rs. 30000. " +
                "Please ensure timely payments on/before the due date for revaluation -axio",
        )

        pipeline.processUnprocessed()

        val sms = db.rawSmsDao().getById("sms-2000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.PARSED)
        assertThat(sms?.parseClass).isEqualTo(ParseClass.CREDIT_LIMIT_CHANGE)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
        assertThat(db.accountDao().getById(bnplId)?.creditLimit).isEqualTo(3_000_000L)
    }

    @Test
    fun `a bank debit towards CAPITALFLOAT settling the axio bill is linked as a one-sided transfer`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive("AD-ICICIT-S", "ICICI Bank Acc XX924 debited Rs. 1,698.00 on 05-Aug-26 towards CAPITALFLOAT. UPI:123")

        pipeline.processUnprocessed()

        val txn = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(txn.merchantRaw).isEqualTo("CAPITALFLOAT")
        assertThat(txn.isInternal).isTrue()
        assertThat(txn.transferId).isNotNull()
        val transfer = db.transferDao().getById(txn.transferId!!)!!
        assertThat(transfer.fromTxnId).isEqualTo(txn.id)
        assertThat(transfer.toTxnId).isNull()
    }
}
