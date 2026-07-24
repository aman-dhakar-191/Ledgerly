package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.ParseClass
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
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
 * docs/parser.md's Flow diagram, exercised end to end against a real in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
class SmsParsingPipelineTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var pipeline: SmsParsingPipeline
    private lateinit var ledgerSettingsStore: LedgerSettingsStore
    private lateinit var reconciler: TransactionReconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledgerSettingsStore = LedgerSettingsStore(ApplicationProvider.getApplicationContext())
        reconciler = TransactionReconciler(db.balanceAnchorDao(), db.transactionDao())
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
        )
    }

    /**
     * Builds a rule the same way [ReviewConfirmationService] would: generalise the amount,
     * occurred-at, and (if present) balance spans found in [sampleBody], leaving everything else
     * literal.
     */
    private fun buildRule(institution: String, sampleBody: String, txnType: ParserTxnType = ParserTxnType.DEBIT): ParserRule {
        val extraction = GenericExtractor.extract(sampleBody, 0L)
        val fields = mutableMapOf<String, IntRange>()
        extraction.amount.span?.let { fields["amount"] = it }
        extraction.occurredAt.span?.let { fields["occurredAt"] = it }
        extraction.balanceAfter.span?.let { fields["balanceAfter"] = it }
        val generated = generateRule(sampleBody, fields)
        return ParserRule(
            id = "rule-1",
            institution = institution,
            pattern = generated.pattern,
            fieldMap = encodeFieldMap(generated.fieldMap),
            txnType = txnType,
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

    @Test
    fun `a matching active rule writes a confirmed rule transaction, no balance to reconcile`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        val rule = buildRule("ICICIT", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited.")
        db.parserRuleDao().insert(rule)
        archive("AD-ICICIT-S", "ICICI Bank Acc XX924 debited Rs. 700.00 on 10-Jun-26; X credited.")

        pipeline.processUnprocessed()

        val sms = db.rawSmsDao().getById("sms-2000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.PARSED)
        assertThat(sms?.matchedRuleId).isEqualTo("rule-1")
        val txn = db.transactionDao().getByRawSmsId("sms-2000")
        assertThat(txn?.amount).isEqualTo(70_000L)
        assertThat(txn?.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(txn?.source).isEqualTo(TransactionSource.SMS_RULE)
        assertThat(db.parserRuleDao().getById("rule-1")?.matchCount).isEqualTo(1)
    }

    @Test
    fun `a rule match with a balance that reconciles exactly is confirmed and re-anchors the account`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        val acct = account(last4 = "924")
        db.balanceAnchorDao().insert(
            BalanceAnchor(
                id = "anchor-0",
                accountId = acct.id,
                balance = 100_000L,
                asOf = 0L,
                source = BalanceAnchorSource.OPENING,
                note = null,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
        val rule = buildRule(
            "ICICIT",
            "ICICI Bank Acc XX924 debited Rs. 500.00 on 12-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 1,000.00",
        )
        db.parserRuleDao().insert(rule)
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 debited Rs. 200.00 on 15-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 800.00",
            receivedAt = 5_000L,
        )

        pipeline.processUnprocessed()

        val txn = db.transactionDao().getByRawSmsId("sms-5000")
        assertThat(txn?.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(txn?.balanceAfter).isEqualTo(80_000L)

        val updatedAccount = db.accountDao().getById(acct.id)
        assertThat(updatedAccount?.currentBalance).isEqualTo(80_000L)
        val anchors = db.balanceAnchorDao().observeForAccount(acct.id).first()
        assertThat(anchors.map { it.source }).contains(BalanceAnchorSource.SMS_DERIVED)
    }

    @Test
    fun `a rule match with a balance that does not reconcile is flagged for review, not confirmed`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        val acct = account(last4 = "924")
        db.balanceAnchorDao().insert(
            BalanceAnchor(
                id = "anchor-0",
                accountId = acct.id,
                balance = 100_000L,
                asOf = 0L,
                source = BalanceAnchorSource.OPENING,
                note = null,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
        val rule = buildRule(
            "ICICIT",
            "ICICI Bank Acc XX924 debited Rs. 500.00 on 12-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 1,000.00",
        )
        db.parserRuleDao().insert(rule)
        // Expected balance after a 200.00 debit off a 1,000.00 anchor is 800.00, not 750.00.
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Acc XX924 debited Rs. 200.00 on 16-Jun-26 InfoBIL*INFT*CC001.Avl Bal Rs. 750.00",
            receivedAt = 6_000L,
        )

        pipeline.processUnprocessed()

        val sms = db.rawSmsDao().getById("sms-6000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.REVIEW)
        val txn = db.transactionDao().getByRawSmsId("sms-6000")
        assertThat(txn?.status).isEqualTo(TransactionStatus.PENDING_REVIEW)
        assertThat(txn?.source).isEqualTo(TransactionSource.SMS_RULE)
    }

    @Test
    fun `a message matching no active rule is left for review, never re-parsed by the generic extractor`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        val rule = buildRule("ICICIT", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited.")
        db.parserRuleDao().insert(rule)
        // A different, unrelated shape for the same institution - the generic extractor could
        // parse this fine, but Tier 2 is a seed, never a fallback, once any rule is active.
        archive("AD-ICICIT-S", "ICICI Bank Credit Card XX6001 debited for INR 585.28 on 15-Jun-26 for UPI-1-ZOMATO")

        pipeline.processUnprocessed()

        val sms = db.rawSmsDao().getById("sms-2000")
        assertThat(sms?.parseStatus).isEqualTo(ParseStatus.REVIEW)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
    }

    @Test
    fun `backfillRule updates an existing pending Tier 2 suggestion instead of duplicating it`() = runTest {
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        val body = "ICICI Bank Acc XX924 debited Rs. 700.00 on 10-Jun-26; X credited."
        archive("AD-ICICIT-S", body)
        db.rawSmsDao().update(
            db.rawSmsDao().getById("sms-2000")!!.copy(institution = "ICICIT", parseStatus = ParseStatus.REVIEW),
        )
        val pending = Transaction(
            id = "txn-generic",
            accountId = "acct-1",
            amount = 70_000L,
            direction = Direction.DEBIT,
            occurredAt = 2_000L,
            merchantRaw = null,
            balanceAfter = null,
            rawSmsId = "sms-2000",
            source = TransactionSource.SMS_GENERIC,
            status = TransactionStatus.PENDING_REVIEW,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = 2_000L,
            updatedAt = 2_000L,
            deletedAt = null,
        )
        db.transactionDao().insert(pending)

        val rule = buildRule("ICICIT", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited.")
        db.parserRuleDao().insert(rule)
        pipeline.backfillRule(rule)

        val txn = db.transactionDao().getById("txn-generic")
        assertThat(txn?.source).isEqualTo(TransactionSource.SMS_RULE)
        assertThat(txn?.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")?.id).isEqualTo("txn-generic")
        assertThat(db.rawSmsDao().getById("sms-2000")?.parseStatus).isEqualTo(ParseStatus.PARSED)
    }

    @Test
    fun `backfillRule never touches an already-confirmed transaction`() = runTest {
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        val body = "ICICI Bank Acc XX924 debited Rs. 700.00 on 10-Jun-26; X credited."
        archive("AD-ICICIT-S", body)
        db.rawSmsDao().update(
            db.rawSmsDao().getById("sms-2000")!!.copy(institution = "ICICIT", parseStatus = ParseStatus.REVIEW),
        )
        val confirmed = Transaction(
            id = "txn-confirmed",
            accountId = "acct-1",
            amount = 1L, // deliberately wrong, to prove backfill never touches it
            direction = Direction.CREDIT,
            occurredAt = 2_000L,
            merchantRaw = "user typed this in manually",
            balanceAfter = null,
            rawSmsId = "sms-2000",
            source = TransactionSource.SMS_GENERIC,
            status = TransactionStatus.CONFIRMED,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = 2_000L,
            updatedAt = 2_000L,
            deletedAt = null,
        )
        db.transactionDao().insert(confirmed)

        val rule = buildRule("ICICIT", "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited.")
        db.parserRuleDao().insert(rule)
        pipeline.backfillRule(rule)

        val txn = db.transactionDao().getById("txn-confirmed")
        assertThat(txn?.amount).isEqualTo(1L)
        assertThat(txn?.merchantRaw).isEqualTo("user typed this in manually")
    }
}
