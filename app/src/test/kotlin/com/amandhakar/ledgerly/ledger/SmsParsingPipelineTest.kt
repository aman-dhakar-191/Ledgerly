package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.CardStatement
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
            CardPaymentMatcher(db.transactionDao(), db.rawSmsDao(), db.accountDao(), db.transferDao()),
            db.cardStatementDao(),
            db.transferDao(),
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

    private suspend fun creditCardAccount(last4: String, creditLimit: Long?, id: String = "card-1") = Account(
        id = id,
        name = "Test Card",
        type = AccountType.CREDIT_CARD,
        last4 = last4,
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

    private suspend fun walletAccount(id: String = "wallet-1") = Account(
        id = id,
        name = "Test Wallet",
        type = AccountType.WALLET,
        last4 = null,
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
    fun `a card spend with Avl Limit reanchors outstanding using the account's own credit limit`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        creditCardAccount(last4 = "6001", creditLimit = 5_000_000L)
        archive(
            "AD-ICICIT-S",
            "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. " +
                "Avl Limit: INR 15,468.00. If not you, call 1800 2662",
        )

        pipeline.processUnprocessed()

        val account = db.accountDao().getById("card-1")!!
        // outstanding = 50,000.00 - 15,468.00 = 34,532.00, stored negative per docs/schema.md's liability convention.
        assertThat(account.currentBalance).isEqualTo(-3_453_200L)
    }

    @Test
    fun `a card spend with no credit limit set on the account leaves its balance untouched`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        creditCardAccount(last4 = "6001", creditLimit = null)
        archive(
            "AD-ICICIT-S",
            "INR 1,630.00 spent using ICICI Bank Card XX6001 on 04-Jul-26 on BLINKIT. " +
                "Avl Limit: INR 15,468.00. If not you, call 1800 2662",
        )

        pipeline.processUnprocessed()

        val account = db.accountDao().getById("card-1")!!
        assertThat(account.currentBalance).isEqualTo(0L)
        assertThat(account.balanceAsOf).isEqualTo(0L)
    }

    @Test
    fun `both statement formats parse into a card statement without creating a transaction`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        creditCardAccount(last4 = "6001", creditLimit = 5_000_000L)
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
                "Total of Rs 10,391.94 or minimum of Rs 520.00 is due by 30-JUL-26.",
        )

        pipeline.processUnprocessed()

        assertThat(db.rawSmsDao().getById("sms-2000")?.parseStatus).isEqualTo(ParseStatus.PARSED)
        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
        val statement = db.cardStatementDao().getByRawSmsId("sms-2000")
        assertThat(statement?.totalDue).isEqualTo(1_039_194L)
        assertThat(statement?.minimumDue).isEqualTo(52_000L)
    }

    @Test
    fun `the 'pay total amount due' statement variant also parses without creating a transaction`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        creditCardAccount(last4 = "5001", creditLimit = 5_000_000L)
        archive(
            "AD-ICICIT-S",
            "Pay Total Amount Due of Rs 6,941.21 or Minimum Amount Due of Rs 2,170.00 " +
                "by 23-Jul-26 towards ICICI Bank Credit Card XX5001.",
        )

        pipeline.processUnprocessed()

        assertThat(db.transactionDao().getByRawSmsId("sms-2000")).isNull()
        val statement = db.cardStatementDao().getByRawSmsId("sms-2000")
        assertThat(statement?.totalDue).isEqualTo(694_121L)
        assertThat(statement?.minimumDue).isEqualTo(217_000L)
    }

    @Test
    fun `a statement mismatch against transactions since the last statement creates a flagged adjustment`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        creditCardAccount(last4 = "6001", creditLimit = 5_000_000L)
        db.cardStatementDao().insert(
            CardStatement(
                id = "stmt-prev",
                accountId = "card-1",
                totalDue = 50_000L, // Rs 500.00
                minimumDue = 5_000L,
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
                accountId = "card-1",
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
        // States Rs 650.00 - Rs 50.00 more than transactions alone explain (fees/interest).
        archive(
            "AD-ICICIT-S",
            "ICICI Bank Credit Card XX6001 Statement is sent to a***@gmail.com. " +
                "Total of Rs 650.00 or minimum of Rs 30.00 is due by 30-AUG-26.",
        )

        pipeline.processUnprocessed()

        val adjustment = db.transactionDao().observeByStatus(TransactionStatus.PENDING_REVIEW).first()
            .single { it.source == TransactionSource.ADJUSTMENT }
        assertThat(adjustment.amount).isEqualTo(5_000L) // Rs 50.00
        assertThat(adjustment.direction).isEqualTo(Direction.DEBIT)
        assertThat(adjustment.accountId).isEqualTo("card-1")
    }

    @Test
    fun `a bank debit funding an Amazon Pay wallet top-up is marked internal, not an expense`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        trustSender("AD-ICICIT-S", "ICICIT")
        account(last4 = "924")
        archive("AD-ICICIT-S", "ICICI Bank Acct XX924 debited for Rs 500.00 on 02-Jun-25; Amazon Pay Bala credited. UPI:123")

        pipeline.processUnprocessed()

        val txn = db.transactionDao().getByRawSmsId("sms-2000")
        assertThat(txn?.merchantRaw).isEqualTo("Amazon Pay Bala")
        assertThat(txn?.isInternal).isTrue()
    }

    @Test
    fun `a wallet debit resolved via the sender's linked account records as a normal expense`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val walletId = "wallet-1"
        walletAccount(id = walletId)
        trustSender("JX-JUSPAY-S", "JUSPAY", accountId = walletId)
        archive("JX-JUSPAY-S", "Your Apay Wallet balance is debited for INR 140.00. Reference Number is 600789415458.")

        pipeline.processUnprocessed()

        // No funding transaction exists at all in this test - partial history is normal (Task 2.5).
        val txn = db.transactionDao().getByRawSmsId("sms-2000")
        assertThat(txn?.accountId).isEqualTo(walletId)
        assertThat(txn?.amount).isEqualTo(14_000L)
        assertThat(txn?.direction).isEqualTo(Direction.DEBIT)
        assertThat(txn?.isInternal).isFalse()
    }

    @Test
    fun `a rule-matched wallet payment with a balance reconciles just like a bank balance`() = runTest {
        ledgerSettingsStore.setLedgerStartDate(0L)
        val walletId = "wallet-1"
        walletAccount(id = walletId)
        trustSender("JX-JUSPAY-S", "JUSPAY", accountId = walletId)
        db.balanceAnchorDao().insert(
            BalanceAnchor(
                id = "wallet-anchor",
                accountId = walletId,
                balance = 38_198L, // Rs 381.98
                asOf = 0L,
                source = BalanceAnchorSource.OPENING,
                note = null,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
        val rule = buildRule(
            "JUSPAY",
            "Payment of Rs 100.00 using Apay Balance successful at merchant. Updated Balance is Rs 300.00 - SMS by Juspay",
        )
        db.parserRuleDao().insert(rule)
        archive(
            "JX-JUSPAY-S",
            "Payment of Rs 114.00 using Apay Balance successful at merchant. Updated Balance is Rs 267.98 - SMS by Juspay",
            receivedAt = 5_000L,
        )

        pipeline.processUnprocessed()

        val txn = db.transactionDao().getByRawSmsId("sms-5000")
        assertThat(txn?.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(txn?.balanceAfter).isEqualTo(26_798L)
        assertThat(db.accountDao().getById(walletId)?.currentBalance).isEqualTo(26_798L)
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
