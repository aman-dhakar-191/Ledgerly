package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.ParseClass
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 1.13's own test list: "confirming writes a transaction, a rule, and a golden test." */
@RunWith(RobolectricTestRunner::class)
class ReviewConfirmationServiceTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var service: ReviewConfirmationService

    private val body = "ICICI Bank Acc XX924 debited Rs. 500.00 on 09-Jun-26; X credited. UPI:1"
    private val receivedAt = 2_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val reconciler = TransactionReconciler(db.balanceAnchorDao(), db.transactionDao())
        val pipeline = SmsParsingPipeline(
            db.rawSmsDao(),
            db.senderRegistryDao(),
            db.accountDao(),
            db.transactionDao(),
            db.parserRuleDao(),
            db.balanceAnchorDao(),
            db.payeeAllowlistDao(),
            reconciler,
            LedgerSettingsStore(ApplicationProvider.getApplicationContext()),
            CardPaymentMatcher(db.transactionDao(), db.rawSmsDao(), db.accountDao(), db.transferDao()),
            db.cardStatementDao(),
            db.transferDao(),
            RefundMatcher(db.transactionDao(), db.transferDao()),
        )
        service = ReviewConfirmationService(
            db.transactionDao(),
            db.rawSmsDao(),
            db.parserRuleDao(),
            db.goldenTestDao(),
            db.transactionAuditDao(),
            db.payeeAllowlistDao(),
            db.accountDao(),
            db.balanceAnchorDao(),
            reconciler,
            pipeline,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedPendingTransaction(): Transaction {
        db.accountDao().insert(
            Account(
                id = "acct-1",
                name = "Test",
                type = AccountType.SAVINGS,
                last4 = "924",
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
            ),
        )
        db.rawSmsDao().insert(
            RawSms(
                id = "sms-1",
                sender = "AD-ICICIT-S",
                body = body,
                receivedAt = receivedAt,
                subscriptionId = null,
                dedupeHash = computeDedupeHash("AD-ICICIT-S", receivedAt, body),
                institution = "ICICIT",
                parseStatus = ParseStatus.REVIEW,
                parseClass = ParseClass.TRANSACTION,
                matchedRuleId = null,
                createdAt = receivedAt,
                updatedAt = receivedAt,
                deletedAt = null,
            ),
        )
        val transaction = Transaction(
            id = "txn-1",
            accountId = "acct-1",
            amount = 50_000L,
            direction = Direction.DEBIT,
            occurredAt = receivedAt,
            merchantRaw = null,
            balanceAfter = null,
            rawSmsId = "sms-1",
            source = TransactionSource.SMS_GENERIC,
            status = TransactionStatus.PENDING_REVIEW,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = receivedAt,
            updatedAt = receivedAt,
            deletedAt = null,
        )
        db.transactionDao().insert(transaction)
        return transaction
    }

    @Test
    fun `confirming an unedited extraction writes the transaction, a rule, and a golden test`() = runTest {
        val transaction = seedPendingTransaction()
        val correction = ReviewCorrection(
            amount = 50_000L,
            direction = Direction.DEBIT,
            merchant = null,
            occurredAt = receivedAt,
            balanceAfter = null,
        )

        service.confirm(transaction, correction)

        val confirmed = db.transactionDao().getById("txn-1")
        assertThat(confirmed?.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(confirmed?.amount).isEqualTo(50_000L)

        val rules = db.parserRuleDao().observeAll().first()
        assertThat(rules).hasSize(1)
        assertThat(rules.single().institution).isEqualTo("ICICIT")
        assertThat(rules.single().active).isTrue()

        val goldenTests = db.goldenTestDao().observeAll().first()
        assertThat(goldenTests).hasSize(1)
        assertThat(goldenTests.single().ruleId).isEqualTo(rules.single().id)

        val sms = db.rawSmsDao().getById("sms-1")
        assertThat(sms?.matchedRuleId).isEqualTo(rules.single().id)
    }

    @Test
    fun `an edited amount is written to the transaction but does not anchor a rule`() = runTest {
        val transaction = seedPendingTransaction()
        val correction = ReviewCorrection(
            amount = 60_000L, // user corrected the amount away from what the extractor found
            direction = Direction.DEBIT,
            merchant = null,
            occurredAt = receivedAt,
            balanceAfter = null,
        )

        service.confirm(transaction, correction)

        assertThat(db.transactionDao().getById("txn-1")?.amount).isEqualTo(60_000L)
        // amount was edited (excluded), and merchant/occurredAt/balanceAfter don't confirm as-is
        // for this body either, so nothing is left to anchor a rule on — no rule, but the
        // transaction and golden test are still written with the corrected value.
        assertThat(db.parserRuleDao().observeAll().first()).isEmpty()
        val goldenTests = db.goldenTestDao().observeAll().first()
        assertThat(goldenTests.single().expectedJson).contains("\"amount\":60000")
    }

    @Test
    fun `an edited field writes an audit row, per CONTEXT_md invariant 5`() = runTest {
        val transaction = seedPendingTransaction()
        val correction = ReviewCorrection(
            amount = 60_000L,
            direction = Direction.DEBIT,
            merchant = null,
            occurredAt = receivedAt,
            balanceAfter = null,
        )

        service.confirm(transaction, correction)

        val audits = db.transactionAuditDao().observeForTransaction("txn-1").first()
        assertThat(audits).hasSize(1)
        assertThat(audits.single().field).isEqualTo("amount")
        assertThat(audits.single().oldValue).isEqualTo("50000")
        assertThat(audits.single().newValue).isEqualTo("60000")
    }

    @Test
    fun `confirming with no edits writes no audit rows`() = runTest {
        val transaction = seedPendingTransaction()
        val correction = ReviewCorrection(
            amount = 50_000L,
            direction = Direction.DEBIT,
            merchant = null,
            occurredAt = receivedAt,
            balanceAfter = null,
        )

        service.confirm(transaction, correction)

        assertThat(db.transactionAuditDao().observeForTransaction("txn-1").first()).isEmpty()
    }

    @Test
    fun `the anonymized golden test body masks the account digits but keeps the amount and merchant`() = runTest {
        val transaction = seedPendingTransaction()
        val correction = ReviewCorrection(
            amount = 50_000L,
            direction = Direction.DEBIT,
            merchant = null,
            occurredAt = receivedAt,
            balanceAfter = null,
        )

        service.confirm(transaction, correction)

        val rawBody = db.goldenTestDao().observeAll().first().single().rawBody
        assertThat(rawBody).doesNotContain("924")
        assertThat(rawBody).contains("XXX")
        assertThat(rawBody).contains("500.00")
    }

    @Test
    fun `a transaction with no linked raw sms confirms without a rule or golden test`() = runTest {
        val transaction = Transaction(
            id = "txn-manual",
            accountId = "acct-1",
            amount = 10_000L,
            direction = Direction.DEBIT,
            occurredAt = receivedAt,
            merchantRaw = null,
            balanceAfter = null,
            rawSmsId = null,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.PENDING_REVIEW,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = receivedAt,
            updatedAt = receivedAt,
            deletedAt = null,
        )
        db.accountDao().insert(
            Account(
                id = "acct-1",
                name = "Test",
                type = AccountType.SAVINGS,
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
            ),
        )
        db.transactionDao().insert(transaction)

        service.confirm(
            transaction,
            ReviewCorrection(amount = 10_000L, direction = Direction.DEBIT, merchant = null, occurredAt = receivedAt, balanceAfter = null),
        )

        assertThat(db.transactionDao().getById("txn-manual")?.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(db.parserRuleDao().observeAll().first()).isEmpty()
        assertThat(db.goldenTestDao().observeAll().first()).isEmpty()
    }

    /** A body where the merchant anchor's span (`"at PAYTM15JUN26PVT"`) strictly contains a valid, unseparated date ("15JUN26"). */
    private val overlappingSpanBody = "ICICI Bank Acc XX924 debited Rs. 500.00 at PAYTM15JUN26PVT. Avl Bal Rs. 1,000.00"

    private suspend fun seedOverlappingSpanTransaction(): Pair<Transaction, GenericExtraction> {
        db.accountDao().insert(
            Account(
                id = "acct-2",
                name = "Test",
                type = AccountType.SAVINGS,
                last4 = "924",
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
            ),
        )
        db.rawSmsDao().insert(
            RawSms(
                id = "sms-2",
                sender = "AD-ICICIT-S",
                body = overlappingSpanBody,
                receivedAt = receivedAt,
                subscriptionId = null,
                dedupeHash = computeDedupeHash("AD-ICICIT-S", receivedAt, overlappingSpanBody),
                institution = "ICICIT",
                parseStatus = ParseStatus.REVIEW,
                parseClass = ParseClass.TRANSACTION,
                matchedRuleId = null,
                createdAt = receivedAt,
                updatedAt = receivedAt,
                deletedAt = null,
            ),
        )
        val extraction = GenericExtractor.extract(overlappingSpanBody, receivedAt)
        val transaction = Transaction(
            id = "txn-overlap",
            accountId = "acct-2",
            amount = extraction.amount.value!!,
            direction = Direction.DEBIT,
            occurredAt = extraction.occurredAt.value!!,
            merchantRaw = extraction.merchant.value,
            balanceAfter = extraction.balanceAfter.value,
            rawSmsId = "sms-2",
            source = TransactionSource.SMS_GENERIC,
            status = TransactionStatus.PENDING_REVIEW,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = receivedAt,
            updatedAt = receivedAt,
            deletedAt = null,
        )
        db.transactionDao().insert(transaction)
        return transaction to extraction
    }

    /**
     * A real production crash (0.1.1): `GeneratedRule.kt`'s `generateRule` assumes confirmed field
     * spans never overlap, but [GenericExtractor]'s fields are each found by an independent regex
     * search - nothing stops one from landing entirely inside another, as here.
     */
    @Test
    fun `a merchant span that contains the occurredAt span does not crash rule generation`() = runTest {
        val (transaction, extraction) = seedOverlappingSpanTransaction()
        val merchantSpan = extraction.merchant.span!!
        val occurredAtSpan = extraction.occurredAt.span!!
        // Confirms the fixture actually reproduces the overlap, not just that nothing crashed.
        assertThat(occurredAtSpan.first).isAtLeast(merchantSpan.first)
        assertThat(occurredAtSpan.last).isAtMost(merchantSpan.last)

        val correction = ReviewCorrection(
            amount = extraction.amount.value!!,
            direction = Direction.DEBIT,
            merchant = extraction.merchant.value,
            occurredAt = extraction.occurredAt.value!!,
            balanceAfter = extraction.balanceAfter.value,
        )

        service.confirm(transaction, correction) // must not throw StringIndexOutOfBoundsException

        assertThat(db.transactionDao().getById("txn-overlap")?.status).isEqualTo(TransactionStatus.CONFIRMED)
        // occurredAt lost out to merchant (kept by earliest start); amount/merchant/balanceAfter
        // don't overlap each other, so the rule still anchors on all three.
        val fieldMap = decodeFieldMap(db.parserRuleDao().observeAll().first().single().fieldMap)
        assertThat(fieldMap).doesNotContainKey("occurredAt")
        assertThat(fieldMap).containsKey("amount")
        assertThat(fieldMap).containsKey("merchant")
        assertThat(fieldMap).containsKey("balanceAfter")
    }
}
