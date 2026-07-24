package com.amandhakar.ledgerly.database.dao

import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.RoomTestDatabase
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.testAccount
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * docs/schema.md: reconciliation baseline is "the most recent anchor at or before the
 * transaction" — [BalanceAnchorDao.getLatestAtOrBefore] is the query that implements that, so it
 * gets its own coverage beyond the generic round-trip/soft-delete tests.
 */
@RunWith(RobolectricTestRunner::class)
class BalanceAnchorDaoTest {

    private lateinit var db: LedgerlyDatabase
    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = RoomTestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getLatestAtOrBefore picks the most recent anchor not after the given time`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)

        val opening = BalanceAnchor("a1", account.id, 100_000L, t0, BalanceAnchorSource.OPENING, null, t0, t0, null)
        val correction = BalanceAnchor(
            "a2", account.id, 120_000L, t0 + 30 * day, BalanceAnchorSource.USER_CORRECTION,
            "drift fix", t0, t0, null,
        )
        db.balanceAnchorDao().insert(opening)
        db.balanceAnchorDao().insert(correction)

        assertThat(db.balanceAnchorDao().getLatestAtOrBefore(account.id, t0 + 10 * day)).isEqualTo(opening)
        assertThat(db.balanceAnchorDao().getLatestAtOrBefore(account.id, t0 + 30 * day)).isEqualTo(correction)
        assertThat(db.balanceAnchorDao().getLatestAtOrBefore(account.id, t0 + 60 * day)).isEqualTo(correction)
        assertThat(db.balanceAnchorDao().getLatestAtOrBefore(account.id, t0 - day)).isNull()
    }

    @Test
    fun `observeForAccount orders newest first`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        val opening = BalanceAnchor("a1", account.id, 100_000L, t0, BalanceAnchorSource.OPENING, null, t0, t0, null)
        val correction = BalanceAnchor(
            "a2", account.id, 120_000L, t0 + 30 * day, BalanceAnchorSource.USER_CORRECTION,
            "drift fix", t0, t0, null,
        )
        db.balanceAnchorDao().insert(opening)
        db.balanceAnchorDao().insert(correction)

        assertThat(db.balanceAnchorDao().observeForAccount(account.id).first()).containsExactly(correction, opening).inOrder()
    }
}
