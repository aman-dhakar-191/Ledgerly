package com.amandhakar.ledgerly.database.dao

import androidx.room.withTransaction
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.RoomTestDatabase
import com.amandhakar.ledgerly.database.entity.AuditReason
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import com.amandhakar.ledgerly.database.entity.testTransaction
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
 * Task 0.4: "audit row written on update" — CONTEXT.md invariant #5 requires every user edit to
 * a transaction to write an audit row in the same atomic unit, so this exercises them together
 * inside `withTransaction` rather than as two independent calls.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionAuditTest {

    private lateinit var db: LedgerlyDatabase
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = RoomTestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `updating a transaction alongside its audit row persists both`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        val original = testTransaction(account.id)
        db.transactionDao().insert(original)

        val edited = original.copy(notes = "corrected merchant", updatedAt = now + 1)
        val audit = TransactionAudit(
            id = "audit-1",
            transactionId = original.id,
            field = "notes",
            oldValue = null,
            newValue = "corrected merchant",
            changedAt = now + 1,
            reason = AuditReason.USER_EDIT,
            createdAt = now + 1,
            updatedAt = now + 1,
            deletedAt = null,
        )

        db.withTransaction {
            db.transactionDao().update(edited)
            db.transactionAuditDao().insert(audit)
        }

        assertThat(db.transactionDao().getById(original.id)?.notes).isEqualTo("corrected merchant")
        assertThat(db.transactionAuditDao().observeForTransaction(original.id).first()).containsExactly(audit)
    }
}
