package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LedgerUiState(
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val transactions: List<Transaction> = emptyList(),
)

/** Task 1.15: minimal ledger UI — account list, per-account transaction list, manual entry, edit. */
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val transactionAuditDao: TransactionAuditDao,
    private val transactionEditor: TransactionEditor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LedgerUiState())
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            accountDao.observeActive().collect { accounts ->
                _uiState.value = _uiState.value.copy(accounts = accounts)
            }
        }
    }

    fun selectAccount(accountId: String?) {
        _uiState.value = _uiState.value.copy(selectedAccountId = accountId, transactions = emptyList())
        if (accountId == null) return
        viewModelScope.launch {
            transactionDao.observeByAccountAndDateRange(accountId, 0L, Long.MAX_VALUE).collect { transactions ->
                _uiState.value = _uiState.value.copy(transactions = transactions)
            }
        }
    }

    @Suppress("LongParameterList") // one field per manual-entry form field
    fun addManualTransaction(
        accountId: String,
        amount: Long,
        direction: Direction,
        merchant: String?,
        occurredAt: Long,
        notes: String?,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            transactionDao.insert(
                Transaction(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    amount = amount,
                    direction = direction,
                    occurredAt = occurredAt,
                    merchantRaw = merchant,
                    balanceAfter = null,
                    rawSmsId = null,
                    source = TransactionSource.MANUAL,
                    status = TransactionStatus.CONFIRMED,
                    transferId = null,
                    isInternal = false,
                    notes = notes,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
    }

    fun editTransaction(transaction: Transaction, correction: ReviewCorrection) {
        viewModelScope.launch { transactionEditor.edit(transaction, correction) }
    }

    fun auditHistory(transactionId: String): Flow<List<TransactionAudit>> =
        transactionAuditDao.observeForTransaction(transactionId)
}
