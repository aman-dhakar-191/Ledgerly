package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** Task 1.17's DAO-touching half — [DataExportFormat.kt] does the actual string building. */
class DataExporter @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val balanceAnchorDao: BalanceAnchorDao,
) {
    suspend fun exportCsv(): String {
        val accounts = accountDao.observeActive().first()
        val transactions = accounts.flatMap { transactionDao.observeByAccountAndDateRange(it.id, 0L, Long.MAX_VALUE).first() }
        return buildTransactionsCsv(transactions, accounts)
    }

    suspend fun exportJson(): String {
        val accounts = accountDao.observeActive().first()
        val transactions = accounts.flatMap { transactionDao.observeByAccountAndDateRange(it.id, 0L, Long.MAX_VALUE).first() }
        val anchors = accounts.flatMap { balanceAnchorDao.observeForAccount(it.id).first() }
        return buildExportJson(accounts, transactions, anchors)
    }
}
