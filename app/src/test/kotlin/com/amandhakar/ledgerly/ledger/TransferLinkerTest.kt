package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.database.entity.TransferKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 2.1's own test list, exercised against a real in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
class TransferLinkerTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var linker: TransferLinker
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        linker = TransferLinker(db.transactionDao(), db.transferDao(), db.accountDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun account(id: String) = Account(
        id = id,
        name = "Test $id",
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
    ).also { db.accountDao().insert(it) }

    private suspend fun transaction(id: String, accountId: String, amount: Long, direction: Direction, occurredAt: Long) =
        Transaction(
            id = id,
            accountId = accountId,
            amount = amount,
            direction = direction,
            occurredAt = occurredAt,
            merchantRaw = null,
            balanceAfter = null,
            rawSmsId = null,
            source = TransactionSource.SMS_GENERIC,
            status = TransactionStatus.CONFIRMED,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = occurredAt,
            updatedAt = occurredAt,
            deletedAt = null,
        ).also { db.transactionDao().insert(it) }

    @Test
    fun `exact amount match links`() = runTest {
        account("acct-a")
        account("acct-b")
        val from = transaction("txn-1", "acct-a", 50_000, Direction.DEBIT, baseTime)
        val to = transaction("txn-2", "acct-b", 50_000, Direction.CREDIT, baseTime + 3_600_000)

        val found = linker.findCounterpart(from)

        assertThat(found?.id).isEqualTo(to.id)
    }

    @Test
    fun `1 paise off does not match`() = runTest {
        account("acct-a")
        account("acct-b")
        val from = transaction("txn-1", "acct-a", 50_000, Direction.DEBIT, baseTime)
        transaction("txn-2", "acct-b", 50_001, Direction.CREDIT, baseTime + 3_600_000)

        assertThat(linker.findCounterpart(from)).isNull()
    }

    @Test
    fun `73 hour gap does not match`() = runTest {
        account("acct-a")
        account("acct-b")
        val from = transaction("txn-1", "acct-a", 50_000, Direction.DEBIT, baseTime)
        transaction("txn-2", "acct-b", 50_000, Direction.CREDIT, baseTime + 73 * 3_600_000L)

        assertThat(linker.findCounterpart(from)).isNull()
    }

    @Test
    fun `same direction pair does not match`() = runTest {
        account("acct-a")
        account("acct-b")
        val from = transaction("txn-1", "acct-a", 50_000, Direction.DEBIT, baseTime)
        transaction("txn-2", "acct-b", 50_000, Direction.DEBIT, baseTime + 3_600_000)

        assertThat(linker.findCounterpart(from)).isNull()
    }

    @Test
    fun `unlink restores both transactions to normal`() = runTest {
        account("acct-a")
        account("acct-b")
        val from = transaction("txn-1", "acct-a", 50_000, Direction.DEBIT, baseTime)
        val to = transaction("txn-2", "acct-b", 50_000, Direction.CREDIT, baseTime + 3_600_000)

        val transfer = linker.link(from, to, TransferKind.ACCOUNT_TO_ACCOUNT)
        assertThat(db.transactionDao().getById(from.id)?.isInternal).isTrue()
        assertThat(db.transactionDao().getById(to.id)?.transferId).isEqualTo(transfer.id)

        linker.unlink(transfer.id)

        val restoredFrom = db.transactionDao().getById(from.id)
        val restoredTo = db.transactionDao().getById(to.id)
        assertThat(restoredFrom?.transferId).isNull()
        assertThat(restoredFrom?.isInternal).isFalse()
        assertThat(restoredTo?.transferId).isNull()
        assertThat(restoredTo?.isInternal).isFalse()
        assertThat(db.transferDao().getById(transfer.id)).isNull()
    }
}
