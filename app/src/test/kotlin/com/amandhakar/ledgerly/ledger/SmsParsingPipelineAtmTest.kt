package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.amandhakar.ledgerly.parser.generateRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.7/docs/corpus-findings.md §6: ATM withdrawals through [SmsParsingPipeline] - split out
 * from [SmsParsingPipelineTest] to keep that class under detekt's `LargeClass` threshold, same as
 * [SmsParsingPipelineBnplTest].
 */
@RunWith(RobolectricTestRunner::class)
class SmsParsingPipelineAtmTest {

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

    private suspend fun buildAtmRule(institution: String, sampleBody: String): ParserRule {
        val extraction = GenericExtractor.extract(sampleBody, 0L)
        val fields = mutableMapOf<String, IntRange>()
        extraction.amount.span?.let { fields["amount"] = it }
        extraction.occurredAt.span?.let { fields["occurredAt"] = it }
        extraction.balanceAfter.span?.let { fields["balanceAfter"] = it }
        val generated = generateRule(sampleBody, fields)
        return ParserRule(
            id = "rule-atm",
            institution = institution,
            pattern = generated.pattern,
            fieldMap = encodeFieldMap(generated.fieldMap),
            txnType = ParserTxnType.DEBIT,
            priority = 0,
            confidence = 1f,
            active = true,
            createdFromSmsId = "seed-sms",
            matchCount = 0,
            correctionCount = 0,
            version = 1,
            createdAt = 0,
            updatedAt = 0,
            deletedAt = null,
        )
    }

    private val withdrawalBody = "ICICI Bank Acc XX924 debited Rs. 4,000.00 on 03-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 32,327.01."

    @Test
    fun `an ATM withdrawal is marked internal, not an expense`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive("AD-ICICIT-S", withdrawalBody)

        pipeline.processUnprocessed()

        val bankTxn = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(bankTxn.amount).isEqualTo(400_000L)
        assertThat(bankTxn.isInternal).isTrue()
        assertThat(bankTxn.transferId).isNotNull()
    }

    @Test
    fun `an ATM withdrawal creates a same-amount credit on the auto-created cash account`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive("AD-ICICIT-S", withdrawalBody)

        pipeline.processUnprocessed()

        val cashAccount = db.accountDao().observeActive().first().single { it.type == AccountType.CASH }
        assertThat(cashAccount.name).isEqualTo("Cash")
        assertThat(cashAccount.currentBalance).isEqualTo(400_000L)

        val bankTxn = db.transactionDao().getByRawSmsId("sms-2000")!!
        val transfer = db.transferDao().getById(bankTxn.transferId!!)!!
        val cashTxn = db.transactionDao().getById(transfer.toTxnId!!)!!
        assertThat(cashTxn.accountId).isEqualTo(cashAccount.id)
        assertThat(cashTxn.amount).isEqualTo(400_000L)
        assertThat(cashTxn.isInternal).isTrue()
    }

    @Test
    fun `a second withdrawal reuses the same cash account and accumulates its balance`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive("AD-ICICIT-S", withdrawalBody, receivedAt = 2_000L)
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 debited Rs. 1,000.00 on 04-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 31,327.01.",
            receivedAt = 3_000L,
        )

        pipeline.processUnprocessed()

        val cashAccounts = db.accountDao().observeActive().first().filter { it.type == AccountType.CASH }
        assertThat(cashAccounts).hasSize(1)
        assertThat(cashAccounts.single().currentBalance).isEqualTo(500_000L)
    }

    @Test
    fun `a rule-matched ATM withdrawal also links to the cash account`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        val rule = buildAtmRule(
            "ICICIT",
            "ICICI Bank Acc XX924 debited Rs. 2,000.00 on 01-Jun-26 NFS*CASH WDL*. Avb Bal Rs. 30,000.00.",
        )
        db.parserRuleDao().insert(rule)
        archive("AD-ICICIT-S", withdrawalBody)

        pipeline.processUnprocessed()

        val bankTxn = db.transactionDao().getByRawSmsId("sms-2000")!!
        assertThat(bankTxn.isInternal).isTrue()
        assertThat(bankTxn.transferId).isNotNull()
        val cashAccount = db.accountDao().observeActive().first().single { it.type == AccountType.CASH }
        assertThat(cashAccount.currentBalance).isEqualTo(400_000L)
    }
}
