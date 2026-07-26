package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.CardStatement
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 2.9's own test list. */
@RunWith(RobolectricTestRunner::class)
class NetWorthCalculatorTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var calculator: NetWorthCalculator
    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        calculator = NetWorthCalculator(db.accountDao(), db.balanceAnchorDao(), db.cardStatementDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun accountWithAnchor(
        id: String,
        type: AccountType,
        balance: Long,
        asOf: Long = now,
    ): Account {
        val account = Account(
            id = id,
            name = id,
            type = type,
            last4 = null,
            currency = "INR",
            currentBalance = balance,
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
                balance = balance,
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

    @Test
    fun `card outstanding (already negative) reduces the total`() = runTest {
        accountWithAnchor("savings", AccountType.SAVINGS, balance = 100_000L)
        accountWithAnchor("card", AccountType.CREDIT_CARD, balance = -30_000L)

        val report = calculator.compute(now)

        assertThat(report.total).isEqualTo(70_000L)
    }

    @Test
    fun `a balance older than 30 days is flagged stale, not excluded`() = runTest {
        accountWithAnchor("old", AccountType.SAVINGS, balance = 50_000L, asOf = now - 40 * day)

        val report = calculator.compute(now)

        assertThat(report.staleAccountIds).containsExactly("old")
        assertThat(report.total).isEqualTo(50_000L)
    }

    @Test
    fun `an account with no anchor at all is excluded, not treated as zero`() = runTest {
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

        assertThat(report.total).isEqualTo(0L)
        assertThat(report.excluded).hasSize(1)
        assertThat(report.excluded.single().accountId).isEqualTo("unanchored")
        assertThat(report.excluded.single().reason).isEqualTo(NO_BALANCE_DATA_REASON)
    }

    @Test
    fun `a LOAN account is excluded from the formula entirely, per Phase 7`() = runTest {
        accountWithAnchor("loan", AccountType.LOAN, balance = -500_000L)

        val report = calculator.compute(now)

        assertThat(report.total).isEqualTo(0L)
        assertThat(report.excluded).isEmpty() // not even reported as excluded - simply out of scope
    }

    @Test
    fun `a BNPL account's outstanding comes from its latest statement, not its stale current_balance`() = runTest {
        // current_balance never gets reanchored from individual axio spends (Task 2.6) - only the
        // monthly statement carries a real outstanding figure.
        accountWithAnchor("bnpl", AccountType.BNPL, balance = 0L, asOf = now - 60 * day)
        db.cardStatementDao().insert(
            CardStatement(
                id = "stmt-1",
                accountId = "bnpl",
                totalDue = 169_800L,
                minimumDue = 169_800L,
                dueDate = now,
                statementDate = now - 2 * day,
                rawSmsId = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )

        val report = calculator.compute(now)

        assertThat(report.total).isEqualTo(-169_800L)
        assertThat(report.staleAccountIds).isEmpty() // the statement's own date is recent
    }
}
