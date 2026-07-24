package com.amandhakar.ledgerly.ui.initialization

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.ledger.InitializationViewModel
import com.amandhakar.ledgerly.model.money.Paise
import com.amandhakar.ledgerly.parser.AnchorPrefill
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Task 1.10: pick `ledger_start_date`, then confirm or override the auto-detected opening balance
 * for each account. "Parse forward from ledger_start_date" (step 6) has nothing to run yet — there
 * is no ingest pipeline wired from `RawSms` to `Transaction` until the rule engine is connected.
 */
@Composable
fun InitializationScreen(onBack: () -> Unit, viewModel: InitializationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    var dateText by remember(state.ledgerStartDate) { mutableStateOf(toLocalDate(state.ledgerStartDate).format(dateFormatter)) }
    var dateError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Set up your ledger", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Messages before this date are kept for rule validation but never become transactions.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = dateText,
            onValueChange = { dateText = it },
            label = { Text("Ledger start date (yyyy-MM-dd)") },
            isError = dateError,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val parsed = runCatching { LocalDate.parse(dateText, dateFormatter) }.getOrNull()
                if (parsed == null) {
                    dateError = true
                } else {
                    dateError = false
                    viewModel.setLedgerStartDate(parsed.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                }
            },
        ) { Text("Apply date") }

        if (state.accounts.isEmpty()) {
            Text("No accounts yet — add one first from the Accounts screen.", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.accounts) { account ->
                AccountAnchorRow(
                    account = account,
                    prefillLoaded = account.id in state.prefills,
                    prefill = state.prefills[account.id],
                    anchored = account.id in state.anchoredAccountIds,
                    onConfirm = { viewModel.confirmPrefill(account) },
                    onOverride = { balance -> viewModel.overridePrefill(account, balance.value) },
                )
            }
        }
    }
}

@Composable
private fun AccountAnchorRow(
    account: Account,
    prefillLoaded: Boolean,
    prefill: AnchorPrefill?,
    anchored: Boolean,
    onConfirm: () -> Unit,
    onOverride: (Paise) -> Unit,
) {
    var overrideText by remember { mutableStateOf("") }
    val overrideAmount = Paise.fromRupeeString(overrideText)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(account.name, style = MaterialTheme.typography.titleMedium)
        when {
            anchored -> Text("Opening balance set.", style = MaterialTheme.typography.bodySmall)
            !prefillLoaded -> Text("Checking the archive...", style = MaterialTheme.typography.bodySmall)
            prefill != null -> {
                Text(
                    "Detected: ${Paise(prefill.balance).format(account.currency)} as of ${formatDate(prefill.asOf)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onConfirm) { Text("Confirm") }
            }
            else -> Text("No balance found in the archive — enter one manually.", style = MaterialTheme.typography.bodySmall)
        }

        if (!anchored && prefillLoaded && prefill == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = overrideText, onValueChange = { overrideText = it }, label = { Text("Opening balance") })
                Button(onClick = { overrideAmount?.let(onOverride) }, enabled = overrideAmount != null) { Text("Save") }
            }
        }
    }
    HorizontalDivider()
}

private fun toLocalDate(epochMillis: Long): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatDate(epochMillis: Long): String =
    toLocalDate(epochMillis).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
