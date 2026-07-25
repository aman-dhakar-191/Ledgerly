package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.Direction as ParserDirection
import com.amandhakar.ledgerly.parser.ReconciliationResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 1.9/1.11's own test lists: "anchor resets drift; transactions before an anchor are
 * unaffected; reconciliation picks the correct anchor when several exist" / "matching balance
 * confirms ...; mismatch rejects and flags; a message with no balance updates the running balance
 * without confirming it."
 */
@RunWith(RobolectricTestRunner::class)
class TransactionReconcilerTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var reconciler: TransactionReconciler
    private val accountId = "acct-1"

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reconciler = TransactionReconciler(db.balanceAnchorDao(), db.transactionDao())

        db.accountDao().insert(
            Account(
                id = accountId,
                name = "Test Savings",
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun anchor(balance: Long, asOf: Long, id: String = "anchor-$asOf") {
        db.balanceAnchorDao().insert(
            BalanceAnchor(
                id = id,
                accountId = accountId,
                balance = balance,
                asOf = asOf,
                source = BalanceAnchorSource.OPENING,
                note = null,
                createdAt = asOf,
                updatedAt = asOf,
                deletedAt = null,
            ),
        )
    }

    private suspend fun confirmedTxn(amount: Long, direction: Direction, occurredAt: Long, id: String = "txn-$occurredAt") {
        db.transactionDao().insert(
            Transaction(
                id = id,
                accountId = accountId,
                amount = amount,
                direction = direction,
                occurredAt = occurredAt,
                merchantRaw = null,
                balanceAfter = null,
                rawSmsId = null,
                source = TransactionSource.SMS_RULE,
                status = TransactionStatus.CONFIRMED,
                transferId = null,
                isInternal = false,
                notes = null,
                createdAt = occurredAt,
                updatedAt = occurredAt,
                deletedAt = null,
            ),
        )
    }

    @Test
    fun `with no anchor at or before the transaction, there is nothing to reconcile against`() = runTest {
        anchor(balance = 10_000L, asOf = 2_000L)

        val result = reconciler.reconcile(
            accountId,
            occurredAt = 1_000L,
            txnAmount = 500L,
            txnDirection = ParserDirection.DEBIT,
            statedBalanceAfter = null,
        )

        assertThat(result).isNull()
    }

    @Test
    fun `a stated balance matching the computed baseline confirms`() = runTest {
        anchor(balance = 10_000L, asOf = 1_000L)

        val result = reconciler.reconcile(
            accountId,
            occurredAt = 2_000L,
            txnAmount = 500L,
            txnDirection = ParserDirection.DEBIT,
            statedBalanceAfter = 9_500L,
        )

        assertThat(result).isEqualTo(ReconciliationResult.Confirmed(9_500L))
    }

    @Test
    fun `a stated balance that does not match flags a mismatch, the probable missed message`() = runTest {
        anchor(balance = 10_000L, asOf = 1_000L)

        val result = reconciler.reconcile(
            accountId,
            occurredAt = 2_000L,
            txnAmount = 500L,
            txnDirection = ParserDirection.DEBIT,
            statedBalanceAfter = 8_000L,
        )

        assertThat(result).isEqualTo(ReconciliationResult.Mismatch(expected = 9_500L, stated = 8_000L))
    }

    @Test
    fun `no stated balance still advances the running balance without confirming it`() = runTest {
        anchor(balance = 10_000L, asOf = 1_000L)

        val result = reconciler.reconcile(
            accountId,
            occurredAt = 2_000L,
            txnAmount = 500L,
            txnDirection = ParserDirection.CREDIT,
            statedBalanceAfter = null,
        )

        assertThat(result).isEqualTo(ReconciliationResult.NoBalanceStated(10_500L))
    }

    @Test
    fun `confirmed transactions since the anchor accumulate into the baseline`() = runTest {
        anchor(balance = 10_000L, asOf = 1_000L)
        confirmedTxn(amount = 1_000L, direction = Direction.DEBIT, occurredAt = 1_500L)
        confirmedTxn(amount = 2_000L, direction = Direction.CREDIT, occurredAt = 1_800L)

        // baseline = 10,000 - 1,000 + 2,000 = 11,000; this debit of 500 expects 10,500
        val result = reconciler.reconcile(
            accountId,
            occurredAt = 2_000L,
            txnAmount = 500L,
            txnDirection = ParserDirection.DEBIT,
            statedBalanceAfter = 10_500L,
        )

        assertThat(result).isEqualTo(ReconciliationResult.Confirmed(10_500L))
    }

    @Test
    fun `a newer anchor resets drift instead of compounding past it`() = runTest {
        anchor(balance = 10_000L, asOf = 1_000L, id = "anchor-old")
        confirmedTxn(amount = 1_000L, direction = Direction.DEBIT, occurredAt = 1_200L)
        // A fresh anchor re-grounds the balance; the debit before it must not still be summed.
        anchor(balance = 50_000L, asOf = 1_500L, id = "anchor-new")

        val result = reconciler.reconcile(
            accountId,
            occurredAt = 2_000L,
            txnAmount = 500L,
            txnDirection = ParserDirection.DEBIT,
            statedBalanceAfter = 49_500L,
        )

        assertThat(result).isEqualTo(ReconciliationResult.Confirmed(49_500L))
    }

    /**
     * Task 2.3's own test: "spend increases outstanding; payment decreases it." No separate
     * accounting path exists for this - `Account.current_balance` is already "negative for
     * liabilities" (docs/schema.md), so this is the exact same signed-sum arithmetic as any other
     * account, just read with the sign flipped (outstanding = -current_balance).
     */
    @Test
    fun `for a credit card account, a spend increases outstanding and a payment decreases it`() = runTest {
        val cardAccountId = "card-1"
        db.accountDao().insert(
            Account(
                id = cardAccountId,
                name = "Test Card",
                type = AccountType.CREDIT_CARD,
                last4 = "6001",
                currency = "INR",
                currentBalance = 0,
                balanceAsOf = 0,
                creditLimit = 5_000_000L,
                statementDay = null,
                dueDay = null,
                archived = false,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
        db.balanceAnchorDao().insert(
            BalanceAnchor(
                id = "card-anchor",
                accountId = cardAccountId,
                balance = -50_000L, // 500.00 outstanding, stored negative for the liability
                asOf = 1_000L,
                source = BalanceAnchorSource.OPENING,
                note = null,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                deletedAt = null,
            ),
        )

        val afterSpend = reconciler.reconcile(
            cardAccountId,
            occurredAt = 1_500L,
            txnAmount = 10_000L,
            txnDirection = ParserDirection.DEBIT,
            statedBalanceAfter = null,
        )
        // outstanding: 500.00 -> 600.00
        assertThat(afterSpend).isEqualTo(ReconciliationResult.NoBalanceStated(-60_000L))

        db.transactionDao().insert(
            Transaction(
                id = "spend-1",
                accountId = cardAccountId,
                amount = 10_000L,
                direction = Direction.DEBIT,
                occurredAt = 1_500L,
                merchantRaw = null,
                balanceAfter = null,
                rawSmsId = null,
                source = TransactionSource.SMS_GENERIC,
                status = TransactionStatus.CONFIRMED,
                transferId = null,
                isInternal = false,
                notes = null,
                createdAt = 1_500L,
                updatedAt = 1_500L,
                deletedAt = null,
            ),
        )

        val afterPayment = reconciler.reconcile(
            cardAccountId,
            occurredAt = 2_000L,
            txnAmount = 25_000L,
            txnDirection = ParserDirection.CREDIT,
            statedBalanceAfter = null,
        )
        // outstanding: 600.00 -> 350.00
        assertThat(afterPayment).isEqualTo(ReconciliationResult.NoBalanceStated(-35_000L))
    }

    @Test
    fun `reconciliation picks the latest anchor at or before the transaction, not an earlier or later one`() = runTest {
        anchor(balance = 1_000L, asOf = 500L, id = "anchor-earliest")
        anchor(balance = 5_000L, asOf = 1_500L, id = "anchor-correct")
        anchor(balance = 9_000L, asOf = 2_500L, id = "anchor-future")

        val result = reconciler.reconcile(
            accountId,
            occurredAt = 2_000L,
            txnAmount = 0L,
            txnDirection = ParserDirection.CREDIT,
            statedBalanceAfter = null,
        )

        assertThat(result).isEqualTo(ReconciliationResult.NoBalanceStated(5_000L))
    }
}
