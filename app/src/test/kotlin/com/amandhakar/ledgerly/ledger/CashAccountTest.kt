package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.AccountType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.7: [ensureCashAccount]/[creditCashAccount] in isolation from the full pipeline, covering
 * the out-of-order accumulation case [SmsParsingPipelineAtmTest] can't easily force (its withdrawals
 * always arrive and process in ascending `received_at` order, per [RawSmsDao.getByStatus]).
 */
@RunWith(RobolectricTestRunner::class)
class CashAccountTest {

    private lateinit var db: LedgerlyDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ensureCashAccount creates exactly one account, named Cash, and reuses it on later calls`() = runTest {
        val first = ensureCashAccount(db.accountDao(), "INR", now = 1_000L)
        val second = ensureCashAccount(db.accountDao(), "INR", now = 2_000L)

        assertThat(first.id).isEqualTo(second.id)
        assertThat(first.name).isEqualTo("Cash")
        assertThat(first.type).isEqualTo(AccountType.CASH)
    }

    @Test
    fun `a later withdrawal adds to the running balance`() = runTest {
        val account = ensureCashAccount(db.accountDao(), "INR", now = 0L)
        creditCashAccount(db.accountDao(), db.balanceAnchorDao(), account, amount = 100_000L, asOf = 1_000L)
        val afterFirst = db.accountDao().getById(account.id)!!

        creditCashAccount(db.accountDao(), db.balanceAnchorDao(), afterFirst, amount = 50_000L, asOf = 2_000L)

        assertThat(db.accountDao().getById(account.id)?.currentBalance).isEqualTo(150_000L)
    }

    @Test
    fun `an out-of-order older withdrawal still adds to the balance instead of being dropped`() = runTest {
        val account = ensureCashAccount(db.accountDao(), "INR", now = 0L)
        // Processed out of chronological order: the later (2_000) withdrawal lands first.
        creditCashAccount(db.accountDao(), db.balanceAnchorDao(), account, amount = 100_000L, asOf = 2_000L)
        val afterFirst = db.accountDao().getById(account.id)!!

        creditCashAccount(db.accountDao(), db.balanceAnchorDao(), afterFirst, amount = 50_000L, asOf = 1_000L)

        val updated = db.accountDao().getById(account.id)!!
        assertThat(updated.currentBalance).isEqualTo(150_000L) // both contributions counted
        assertThat(updated.balanceAsOf).isEqualTo(2_000L) // freshness cursor never regresses
    }
}
