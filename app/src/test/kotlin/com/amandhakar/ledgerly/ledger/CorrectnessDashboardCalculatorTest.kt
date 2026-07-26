package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.DetectedBy
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.database.entity.Transfer
import com.amandhakar.ledgerly.database.entity.TransferKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 2.10's own bullet list: last reconciled, unmatched transfers, pending review, statement mismatches, staleness. */
@RunWith(RobolectricTestRunner::class)
class CorrectnessDashboardCalculatorTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var calculator: CorrectnessDashboardCalculator
    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        calculator = CorrectnessDashboardCalculator(db.accountDao(), db.balanceAnchorDao(), db.transactionDao(), db.transferDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun accountWithAnchor(id: String, asOf: Long = now): Account {
        val account = Account(
            id = id,
            name = id,
            type = AccountType.SAVINGS,
            last4 = null,
            currency = "INR",
            currentBalance = 0,
            balanceAsOf = asOf,
            creditLimit = null,
            statementDay = null,
            dueDay = null,
            archived = false,
            createdAt = asOf,
            updatedAt = asOf,
            deletedAt = null,
        )
        db.accountDao().insert(account)
        db.balanceAnchorDao().insert(
            BalanceAnchor(
                id = "anchor-$id",
                accountId = id,
                balance = 0,
                asOf = asOf,
                source = BalanceAnchorSource.OPENING,
                note = null,
                createdAt = asOf,
                updatedAt = asOf,
                deletedAt = null,
            ),
        )
        return account
    }

    private suspend fun transaction(id: String, accountId: String, status: TransactionStatus, source: TransactionSource) {
        db.transactionDao().insert(
            Transaction(
                id = id,
                accountId = accountId,
                amount = 1_000L,
                direction = Direction.DEBIT,
                occurredAt = now,
                merchantRaw = null,
                balanceAfter = null,
                rawSmsId = null,
                source = source,
                status = status,
                transferId = null,
                isInternal = false,
                notes = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }

    @Test
    fun `an anchored account reports its balance_as_of as last reconciled`() = runTest {
        accountWithAnchor("acct-1", asOf = now)

        val report = calculator.compute(now)

        val reconciliation = report.accountReconciliations.single()
        assertThat(reconciliation.lastReconciledAt).isEqualTo(now)
        assertThat(reconciliation.stale).isFalse()
    }

    @Test
    fun `an account with no anchor at all reports never reconciled`() = runTest {
        db.accountDao().insert(
            Account(
                id = "unanchored",
                name = "Unanchored",
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

        val report = calculator.compute(now)

        assertThat(report.accountReconciliations.single().lastReconciledAt).isNull()
    }

    @Test
    fun `a balance older than 30 days is flagged stale`() = runTest {
        accountWithAnchor("old", asOf = now - 40 * day)

        val report = calculator.compute(now)

        assertThat(report.accountReconciliations.single().stale).isTrue()
    }

    @Test
    fun `only a transfer missing its second leg counts as unmatched`() = runTest {
        accountWithAnchor("acct-1")
        transaction("txn-a", "acct-1", TransactionStatus.CONFIRMED, TransactionSource.SMS_GENERIC)
        transaction("txn-b", "acct-1", TransactionStatus.CONFIRMED, TransactionSource.SMS_GENERIC)
        transaction("txn-c", "acct-1", TransactionStatus.CONFIRMED, TransactionSource.SMS_GENERIC)
        val now2 = System.currentTimeMillis()
        db.transferDao().insert(
            Transfer(
                id = "one-sided",
                fromTxnId = "txn-a",
                toTxnId = null,
                kind = TransferKind.CARD_PAYMENT,
                detectedBy = DetectedBy.AUTO,
                confidence = 1f,
                createdAt = now2,
                updatedAt = now2,
                deletedAt = null,
            ),
        )
        db.transferDao().insert(
            Transfer(
                id = "two-sided",
                fromTxnId = "txn-b",
                toTxnId = "txn-c",
                kind = TransferKind.CARD_PAYMENT,
                detectedBy = DetectedBy.AUTO,
                confidence = 1f,
                createdAt = now2,
                updatedAt = now2,
                deletedAt = null,
            ),
        )

        val report = calculator.compute(now)

        assertThat(report.unmatchedTransferCount).isEqualTo(1)
    }

    @Test
    fun `pending review count includes both ordinary suggestions and statement mismatches`() = runTest {
        accountWithAnchor("acct-1")
        transaction("txn-review", "acct-1", TransactionStatus.PENDING_REVIEW, TransactionSource.SMS_GENERIC)
        transaction("txn-confirmed", "acct-1", TransactionStatus.CONFIRMED, TransactionSource.SMS_RULE)
        transaction("txn-mismatch", "acct-1", TransactionStatus.PENDING_REVIEW, TransactionSource.ADJUSTMENT)

        val report = calculator.compute(now)

        assertThat(report.pendingReviewCount).isEqualTo(2)
        assertThat(report.statementMismatches).hasSize(1)
        assertThat(report.statementMismatches.single().accountId).isEqualTo("acct-1")
    }
}
