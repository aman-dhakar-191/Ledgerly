package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.parser.AnchorPrefill
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class InitializationUiState(
    val ledgerStartDate: Long = 0L,
    val accounts: List<Account> = emptyList(),
    /** Null value = no qualifying message found; account not yet in the map = still loading. */
    val prefills: Map<String, AnchorPrefill?> = emptyMap(),
    val anchoredAccountIds: Set<String> = emptySet(),
)

/**
 * Task 1.10: the setup flow's steps 2, 4 and 5 — "user picks ledger_start_date," "pre-fill a
 * BalanceAnchor per account from the earliest post-start message carrying a balance," and "user
 * confirms or overrides." Step 1 (archive import) is Task 1.2/[com.amandhakar.ledgerly.ingest.ArchiveImportWorker],
 * step 3 (account detection) is [AccountsViewModel]; step 6 ("parse forward") has nothing to do yet
 * until the rule engine and generic extractor are wired into a real ingest pipeline.
 */
@HiltViewModel
class InitializationViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val balanceAnchorDao: BalanceAnchorDao,
    private val accountSuggestor: AccountSuggestor,
    private val ledgerSettingsStore: LedgerSettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        InitializationUiState(ledgerStartDate = ledgerSettingsStore.getLedgerStartDate() ?: ledgerSettingsStore.defaultLedgerStartDate()),
    )
    val uiState: StateFlow<InitializationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val accounts = accountDao.observeActive().first()
            _uiState.value = _uiState.value.copy(accounts = accounts)
            loadPrefills()
        }
    }

    fun setLedgerStartDate(epochMillis: Long) {
        _uiState.value = _uiState.value.copy(ledgerStartDate = epochMillis, prefills = emptyMap())
        loadPrefills()
    }

    private fun loadPrefills() {
        viewModelScope.launch {
            val ledgerStartDate = _uiState.value.ledgerStartDate
            val prefills = _uiState.value.accounts.associate { account ->
                account.id to accountSuggestor.prefillAnchor(account, ledgerStartDate)
            }
            _uiState.value = _uiState.value.copy(prefills = prefills)
        }
    }

    /** Confirms the auto-detected pre-fill as-is: `source = SMS_DERIVED`. */
    fun confirmPrefill(account: Account) {
        val prefill = _uiState.value.prefills[account.id] ?: return
        writeAnchor(account, prefill.balance, prefill.asOf, BalanceAnchorSource.SMS_DERIVED)
    }

    /** The user overrides with their own balance: `source = OPENING`, dated to ledger_start_date. */
    fun overridePrefill(account: Account, balance: Long) {
        writeAnchor(account, balance, _uiState.value.ledgerStartDate, BalanceAnchorSource.OPENING)
    }

    private fun writeAnchor(account: Account, balance: Long, asOf: Long, source: BalanceAnchorSource) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            balanceAnchorDao.insert(
                BalanceAnchor(
                    id = UUID.randomUUID().toString(),
                    accountId = account.id,
                    balance = balance,
                    asOf = asOf,
                    source = source,
                    note = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
            accountDao.update(account.copy(currentBalance = balance, balanceAsOf = asOf, updatedAt = now))
            ledgerSettingsStore.setLedgerStartDate(_uiState.value.ledgerStartDate)
            _uiState.value = _uiState.value.copy(anchoredAccountIds = _uiState.value.anchoredAccountIds + account.id)
        }
    }
}
