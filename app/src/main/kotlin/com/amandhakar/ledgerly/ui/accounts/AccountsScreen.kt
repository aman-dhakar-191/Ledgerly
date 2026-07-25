package com.amandhakar.ledgerly.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.ledger.AccountsViewModel
import com.amandhakar.ledgerly.model.money.Paise
import com.amandhakar.ledgerly.parser.AccountSuggestion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AccountsScreen(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showAddForm by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<AccountSuggestion?>(null) }

    LaunchedEffect(Unit) { viewModel.loadSuggestions() }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)

        if (showAddForm) {
            AddAccountForm(
                prefill = prefill,
                onSubmit = { name, type, last4, currency, opening, creditLimit ->
                    viewModel.createAccount(name, type, last4, currency, opening, System.currentTimeMillis(), creditLimit)
                    showAddForm = false
                    prefill = null
                },
                onCancel = { showAddForm = false; prefill = null },
            )
        } else {
            if (state.accounts.isEmpty()) {
                Text(
                    "No accounts yet. Add one manually, or from a combination seen in your SMS archive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.accounts) { account -> AccountRow(account) }
            }

            if (state.suggestions.isNotEmpty()) {
                Text("Suggested from your SMS archive", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.suggestions) { suggestion ->
                        SuggestionRow(suggestion, onAdd = { prefill = suggestion; showAddForm = true })
                    }
                }
            }

            Button(onClick = { showAddForm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add account manually")
            }
        }
    }
}

@Composable
private fun AccountRow(account: Account) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(account.name, style = MaterialTheme.typography.titleMedium)
        Text(
            "${Paise(account.currentBalance).format(account.currency)} · balance as of " +
                formatEpochMillis(account.balanceAsOf),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    HorizontalDivider()
}

@Composable
private fun SuggestionRow(suggestion: AccountSuggestion, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("${suggestion.institution} ···· ${suggestion.last4} (${suggestion.messageCount} messages)")
        TextButton(onClick = onAdd) { Text("Add") }
    }
}

@Composable
private fun AddAccountForm(
    prefill: AccountSuggestion?,
    onSubmit: (name: String, type: AccountType, last4: String?, currency: String, opening: Paise, creditLimit: Paise?) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(prefill?.let { "${it.institution} ···· ${it.last4}" }.orEmpty()) }
    var type by remember { mutableStateOf(AccountType.SAVINGS) }
    var last4 by remember { mutableStateOf(prefill?.last4.orEmpty()) }
    var currency by remember { mutableStateOf("INR") }
    var openingBalanceText by remember { mutableStateOf("") }
    var creditLimitText by remember { mutableStateOf("") }

    val opening = Paise.fromRupeeString(openingBalanceText)
    val creditLimit = if (type == AccountType.CREDIT_CARD) Paise.fromRupeeString(creditLimitText) else null
    val canSubmit = name.isNotBlank() && opening != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add account", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountType.entries.forEach { candidate ->
                val style = if (candidate == type) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall
                TextButton(onClick = { type = candidate }) {
                    Text(candidate.name, style = style)
                }
            }
        }

        OutlinedTextField(value = last4, onValueChange = { last4 = it }, label = { Text("Last 4 digits (optional)") })
        OutlinedTextField(value = currency, onValueChange = { currency = it }, label = { Text("Currency") })
        OutlinedTextField(
            value = openingBalanceText,
            onValueChange = { openingBalanceText = it },
            label = { Text("Opening balance") },
            isError = openingBalanceText.isNotEmpty() && opening == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (type == AccountType.CREDIT_CARD) {
            OutlinedTextField(
                value = creditLimitText,
                onValueChange = { creditLimitText = it },
                label = { Text("Credit limit") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSubmit(name, type, last4.ifBlank { null }, currency, opening!!, creditLimit) },
                enabled = canSubmit,
            ) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun formatEpochMillis(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}
