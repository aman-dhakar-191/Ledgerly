package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.CardStatementDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.parser.NetWorthComponent
import com.amandhakar.ledgerly.parser.computeNetWorth
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** tasks/phase-2.md's own explanation text for an account this session found no data for at all. */
const val NO_BALANCE_DATA_REASON = "No balance data yet"

data class ExcludedAccount(val accountId: String, val name: String, val reason: String)

data class NetWorthReport(val total: Long, val staleAccountIds: Set<String>, val excluded: List<ExcludedAccount>)

/**
 * Task 2.9: `NetWorth = Σ asset accounts + Σ cash and wallet balances − Σ card outstanding −
 * Σ BNPL outstanding`, "derived, not stored." `Account.current_balance` is already signed for this
 * (docs/schema.md: negative for liabilities) for every type this gathers - except BNPL, which
 * [SmsParsingPipeline] never reanchors from individual axio spends (unlike CREDIT_CARD's `Avl
 * Limit` signal, Task 2.3) - so a BNPL account's outstanding comes from its latest [CardStatement]
 * instead. `LOAN` is excluded entirely: "loans in Phase 7" (tasks/phase-2.md), not part of this
 * formula yet.
 */
class NetWorthCalculator @Inject constructor(
    private val accountDao: AccountDao,
    private val balanceAnchorDao: BalanceAnchorDao,
    private val cardStatementDao: CardStatementDao,
) {
    suspend fun compute(now: Long = System.currentTimeMillis()): NetWorthReport {
        val components = mutableListOf<NetWorthComponent>()
        val excluded = mutableListOf<ExcludedAccount>()
        accountDao.observeActive().first()
            .filter { it.type != AccountType.LOAN }
            .forEach { account ->
                val component = resolveComponent(account)
                if (component == null) {
                    excluded += ExcludedAccount(account.id, account.name, NO_BALANCE_DATA_REASON)
                } else {
                    components += component
                }
            }
        val result = computeNetWorth(components, now)
        return NetWorthReport(result.total, result.staleAccountIds, excluded)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    private suspend fun resolveComponent(account: Account): NetWorthComponent? {
        if (account.type == AccountType.BNPL) {
            val statement = cardStatementDao.observeForAccount(account.id).first().firstOrNull()
            if (statement != null) return NetWorthComponent(account.id, -statement.totalDue, statement.statementDate)
        }
        val hasAnchor = balanceAnchorDao.observeForAccount(account.id).first().isNotEmpty()
        if (!hasAnchor) return null
        return NetWorthComponent(account.id, account.currentBalance, account.balanceAsOf)
    }
}
