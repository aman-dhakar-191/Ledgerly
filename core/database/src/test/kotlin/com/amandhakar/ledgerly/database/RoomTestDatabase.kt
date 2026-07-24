package com.amandhakar.ledgerly.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType

/** In-memory database + a couple of fixture builders shared across the Task 0.3/0.4 test suites. */
object RoomTestDatabase {
    fun create(): LedgerlyDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}

fun testAccount(
    id: String = "acct-1",
    now: Long = 1_700_000_000_000L,
    currentBalance: com.amandhakar.ledgerly.model.money.Paise = com.amandhakar.ledgerly.model.money.Paise(100_000),
    deletedAt: Long? = null,
): Account = Account(
    id = id,
    name = "Test Savings",
    type = AccountType.SAVINGS,
    last4 = "1234",
    currency = "INR",
    currentBalance = currentBalance,
    balanceAsOf = now,
    creditLimit = null,
    statementDay = null,
    dueDay = null,
    archived = false,
    createdAt = now,
    updatedAt = now,
    deletedAt = deletedAt,
)
