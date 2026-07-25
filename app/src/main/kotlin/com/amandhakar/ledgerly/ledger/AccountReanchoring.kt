package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import java.util.UUID

/**
 * docs/corpus-findings.md §2: "each successful reconciliation re-anchors the account." Shared by
 * every path that can produce a [com.amandhakar.ledgerly.parser.ReconciliationResult.Confirmed] —
 * [SmsParsingPipeline]'s Tier-1 matches and [ReviewConfirmationService]'s manual confirmations both
 * need the same account cache update, not two copies that can drift.
 */
suspend fun reanchorAccount(accountDao: AccountDao, balanceAnchorDao: BalanceAnchorDao, account: Account, newBalance: Long, asOf: Long) {
    if (asOf < account.balanceAsOf) return // an out-of-order older message must not regress the cache
    val now = System.currentTimeMillis()
    balanceAnchorDao.insert(
        BalanceAnchor(
            id = UUID.randomUUID().toString(),
            accountId = account.id,
            balance = newBalance,
            asOf = asOf,
            source = BalanceAnchorSource.SMS_DERIVED,
            note = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ),
    )
    accountDao.update(account.copy(currentBalance = newBalance, balanceAsOf = asOf, updatedAt = now))
}
