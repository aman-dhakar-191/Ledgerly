package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.model.money.Paise
import com.amandhakar.ledgerly.parser.AccountSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val suggestions: List<AccountSuggestion> = emptyList(),
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountDao: AccountDao,
    private val balanceAnchorDao: BalanceAnchorDao,
    private val accountSuggestor: AccountSuggestor,
    private val senderRegistryDao: SenderRegistryDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    // Raw archive scan, independent of which accounts already exist - re-filtered below every time
    // either side changes, so an account added from a suggestion drops out immediately instead of
    // waiting for a fresh loadSuggestions() call that may never come.
    private val _rawSuggestions = MutableStateFlow<List<AccountSuggestion>>(emptyList())

    init {
        viewModelScope.launch {
            combine(accountDao.observeActive(), _rawSuggestions) { accounts, rawSuggestions ->
                val known = accounts.mapNotNull { it.last4 }.toSet()
                AccountsUiState(accounts = accounts, suggestions = rawSuggestions.filterNot { it.last4 in known })
            }.collect { _uiState.value = it }
        }
    }

    fun loadSuggestions() {
        viewModelScope.launch {
            _rawSuggestions.value = accountSuggestor.suggest()
        }
    }

    /**
     * Task 1.9: "`BalanceAnchor` CRUD; `source = OPENING` for the initial one." [openingBalance] and
     * [openingAsOf] become both the account's first anchor and its denormalised
     * `current_balance`/`balance_as_of` cache (docs/schema.md) — there are no transactions yet to
     * have moved it since.
     *
     * Task 2.5: [institution] links every already-seen sender for a WALLET account, which carries
     * no `last4` for [SmsParsingPipeline.resolveAccount]'s usual match — the sender's own default
     * account (docs/schema.md) is the only way a wallet message can ever resolve to an account.
     */
    @Suppress("LongParameterList") // one field per Account column the add-account form actually fills in
    fun createAccount(
        name: String,
        type: AccountType,
        last4: String?,
        currency: String,
        openingBalance: Paise,
        openingAsOf: Long,
        creditLimit: Paise?,
        institution: String? = null,
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val accountId = UUID.randomUUID().toString()
            accountDao.insert(
                Account(
                    id = accountId,
                    name = name,
                    type = type,
                    last4 = last4,
                    currency = currency,
                    currentBalance = openingBalance.value,
                    balanceAsOf = openingAsOf,
                    creditLimit = creditLimit?.value,
                    statementDay = null,
                    dueDay = null,
                    archived = false,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
            balanceAnchorDao.insert(
                BalanceAnchor(
                    id = UUID.randomUUID().toString(),
                    accountId = accountId,
                    balance = openingBalance.value,
                    asOf = openingAsOf,
                    source = BalanceAnchorSource.OPENING,
                    note = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
            if (institution != null) linkSendersToAccount(senderRegistryDao, institution, accountId, now)
        }
    }
}
