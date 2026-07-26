package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import java.util.UUID
import kotlinx.coroutines.flow.first

private const val CASH_ACCOUNT_NAME = "Cash"

/**
 * Task 2.7/tasks/phase-2.md: cash has no institution or sender of its own to link like WALLET/BNPL
 * (Task 2.5/2.6) - there is exactly one CASH account per ledger, the "Cash - unallocated" bucket
 * every ATM withdrawal feeds, auto-created on first use rather than requiring the user to add it
 * manually first. `balanceAsOf = 0` so the account starts with no freshness cursor to be older than.
 */
suspend fun ensureCashAccount(accountDao: AccountDao, currency: String, now: Long): Account {
    accountDao.observeActive().first().find { it.type == AccountType.CASH }?.let { return it }
    val account = Account(
        id = UUID.randomUUID().toString(),
        name = CASH_ACCOUNT_NAME,
        type = AccountType.CASH,
        last4 = null,
        currency = currency,
        currentBalance = 0,
        balanceAsOf = 0,
        creditLimit = null,
        statementDay = null,
        dueDay = null,
        archived = false,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
    )
    accountDao.insert(account)
    return account
}

/**
 * Task 2.7: unlike [reanchorAccount] - built for a literal SMS-stated balance, where an
 * out-of-order older message must never regress the cache - cash has no stated balance to trust or
 * distrust, only a running total of withdrawals. Every withdrawal's contribution is always added,
 * regardless of processing order; only `balanceAsOf` (the freshness cursor) is clamped to never
 * move backward.
 */
suspend fun creditCashAccount(
    accountDao: AccountDao,
    balanceAnchorDao: BalanceAnchorDao,
    cashAccount: Account,
    amount: Long,
    asOf: Long,
) {
    val newBalance = cashAccount.currentBalance + amount
    val now = System.currentTimeMillis()
    balanceAnchorDao.insert(
        BalanceAnchor(
            id = UUID.randomUUID().toString(),
            accountId = cashAccount.id,
            balance = newBalance,
            asOf = asOf,
            source = BalanceAnchorSource.SMS_DERIVED,
            note = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ),
    )
    accountDao.update(cashAccount.copy(currentBalance = newBalance, balanceAsOf = maxOf(cashAccount.balanceAsOf, asOf), updatedAt = now))
}
