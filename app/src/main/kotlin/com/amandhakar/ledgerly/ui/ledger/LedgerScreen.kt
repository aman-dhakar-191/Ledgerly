package com.amandhakar.ledgerly.ui.ledger

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import com.amandhakar.ledgerly.database.entity.TransferKind
import com.amandhakar.ledgerly.ledger.LedgerViewModel
import com.amandhakar.ledgerly.ledger.ReviewCorrection
import com.amandhakar.ledgerly.model.money.Paise
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val DAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy")
private val DATETIME_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/** Task 1.15. Minimal — account list -> per-account transaction list -> transaction detail. */
@Composable
fun LedgerScreen(onBack: () -> Unit, viewModel: LedgerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var showAddForm by remember { mutableStateOf(false) }

    val selectedAccount = state.accounts.find { it.id == state.selectedAccountId }

    // One level at a time, matching what's actually on screen - the system back button must do
    // exactly what the in-app "Back" button does, never fall through and close the app.
    val handleBack: () -> Unit = {
        when {
            selectedTransaction != null -> selectedTransaction = null
            showAddForm -> showAddForm = false
            selectedAccount != null -> viewModel.selectAccount(null)
            else -> onBack()
        }
    }
    BackHandler(onBack = handleBack)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = handleBack) { Text("Back") }
        }

        when {
            selectedTransaction != null -> TransactionDetail(
                transaction = selectedTransaction!!,
                viewModel = viewModel,
                onSaved = { selectedTransaction = null },
            )
            showAddForm && selectedAccount != null -> ManualEntryForm(
                account = selectedAccount,
                onSubmit = { amount, direction, merchant, occurredAt, notes ->
                    viewModel.addManualTransaction(selectedAccount.id, amount, direction, merchant, occurredAt, notes)
                    showAddForm = false
                },
            )
            selectedAccount != null -> TransactionListView(
                account = selectedAccount,
                transactions = state.transactions,
                onAddManual = { showAddForm = true },
                onSelectTransaction = { selectedTransaction = it },
            )
            else -> AccountListView(accounts = state.accounts, onSelectAccount = { viewModel.selectAccount(it.id) })
        }
    }
}

@Composable
private fun AccountListView(accounts: List<Account>, onSelectAccount: (Account) -> Unit) {
    Text("Ledger", style = MaterialTheme.typography.headlineSmall)
    if (accounts.isEmpty()) {
        Text("No accounts yet — add one from the Accounts screen first.", style = MaterialTheme.typography.bodyMedium)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(accounts, key = { it.id }) { account ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                TextButton(onClick = { onSelectAccount(account) }) {
                    Column {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${Paise(account.currentBalance).format(account.currency)} · last reconciled " +
                                formatDay(account.balanceAsOf),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TransactionListView(
    account: Account,
    transactions: List<Transaction>,
    onAddManual: () -> Unit,
    onSelectTransaction: (Transaction) -> Unit,
) {
    Text(account.name, style = MaterialTheme.typography.headlineSmall)
    Button(onClick = onAddManual) { Text("Add transaction manually") }

    if (transactions.isEmpty()) {
        Text("No transactions yet for this account.", style = MaterialTheme.typography.bodyMedium)
    }
    val grouped = transactions.sortedByDescending { it.occurredAt }.groupBy { formatDay(it.occurredAt) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        grouped.forEach { (day, dayTransactions) ->
            item { Text(day, style = MaterialTheme.typography.titleSmall) }
            items(dayTransactions, key = { it.id }) { transaction ->
                TextButton(onClick = { onSelectTransaction(transaction) }) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(transaction.merchantRaw ?: transaction.direction.name)
                        val sign = if (transaction.direction == Direction.DEBIT) "-" else "+"
                        Text("$sign${Paise(transaction.amount).format(account.currency)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionDetail(transaction: Transaction, viewModel: LedgerViewModel, onSaved: () -> Unit) {
    var amountText by remember(transaction.id) { mutableStateOf(Paise(transaction.amount).format("").trim()) }
    var direction by remember(transaction.id) { mutableStateOf(transaction.direction) }
    var merchantText by remember(transaction.id) { mutableStateOf(transaction.merchantRaw.orEmpty()) }
    val amount = Paise.fromRupeeString(amountText)
    val audits by viewModel.auditHistory(transaction.id).collectAsState(initial = emptyList<TransactionAudit>())

    Text("Transaction detail", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("Amount") }, isError = amount == null)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { direction = Direction.DEBIT }) { Text("DEBIT") }
        TextButton(onClick = { direction = Direction.CREDIT }) { Text("CREDIT") }
    }
    OutlinedTextField(value = merchantText, onValueChange = { merchantText = it }, label = { Text("Merchant") })
    Button(
        enabled = amount != null,
        onClick = {
            viewModel.editTransaction(
                transaction,
                ReviewCorrection(
                    amount = amount!!.value,
                    direction = direction,
                    merchant = merchantText.ifBlank { null },
                    occurredAt = transaction.occurredAt,
                    balanceAfter = transaction.balanceAfter,
                ),
            )
            onSaved()
        },
    ) { Text("Save") }

    TransferSection(transaction, viewModel, onChanged = onSaved)

    Text("Edit history", style = MaterialTheme.typography.titleSmall)
    if (audits.isEmpty()) {
        Text("No edits yet.", style = MaterialTheme.typography.bodySmall)
    }
    audits.forEach { audit ->
        Text(
            "${audit.field}: ${audit.oldValue} -> ${audit.newValue} (${formatDateTime(audit.changedAt)})",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Task 2.1: manual link/unlink only — [LedgerViewModel.findTransferCounterpart] is a suggestion,
 * never applied without this screen's explicit "Link" tap.
 */
@Composable
private fun TransferSection(transaction: Transaction, viewModel: LedgerViewModel, onChanged: () -> Unit) {
    var candidate by remember(transaction.id) { mutableStateOf<Transaction?>(null) }
    var searched by remember(transaction.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val transferId = transaction.transferId
    Text("Transfer", style = MaterialTheme.typography.titleSmall)
    if (transferId != null) {
        Text("Linked as a transfer.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { viewModel.unlinkTransfer(transferId, onChanged) }) { Text("Unlink") }
    } else {
        candidate?.let {
            Text(
                "Possible counterpart: ${it.merchantRaw.orEmpty()} ${Paise(it.amount).format("")} on ${formatDay(it.occurredAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { viewModel.linkTransfer(transaction, it, TransferKind.ACCOUNT_TO_ACCOUNT, onChanged) }) {
                Text("Link")
            }
        }
        if (candidate == null && searched) {
            Text("No matching counterpart found.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = {
                scope.launch {
                    candidate = viewModel.findTransferCounterpart(transaction)
                    searched = true
                }
            },
        ) { Text("Find transfer counterpart") }
    }
}

@Composable
private fun ManualEntryForm(
    account: Account,
    onSubmit: (amount: Long, direction: Direction, merchant: String?, occurredAt: Long, notes: String?) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Direction.DEBIT) }
    var merchantText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    val amount = Paise.fromRupeeString(amountText)

    Text("Add transaction · ${account.name}", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(
        value = amountText,
        onValueChange = { amountText = it },
        label = { Text("Amount") },
        isError = amountText.isNotEmpty() && amount == null,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { direction = Direction.DEBIT }) { Text("DEBIT") }
        TextButton(onClick = { direction = Direction.CREDIT }) { Text("CREDIT") }
    }
    OutlinedTextField(value = merchantText, onValueChange = { merchantText = it }, label = { Text("Merchant (optional)") })
    OutlinedTextField(value = notesText, onValueChange = { notesText = it }, label = { Text("Notes (optional)") })
    Button(
        enabled = amount != null,
        onClick = {
            onSubmit(
                amount!!.value,
                direction,
                merchantText.ifBlank { null },
                System.currentTimeMillis(),
                notesText.ifBlank { null },
            )
        },
    ) { Text("Save") }
}

private fun formatDay(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DAY_FORMATTER)

private fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DATETIME_FORMATTER)
