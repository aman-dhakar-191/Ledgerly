package com.amandhakar.ledgerly.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.ledger.AccountReconciliation
import com.amandhakar.ledgerly.ledger.CorrectnessDashboardViewModel
import com.amandhakar.ledgerly.ledger.CorrectnessReport
import com.amandhakar.ledgerly.ledger.StatementMismatch
import com.amandhakar.ledgerly.model.money.Paise
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Task 2.10: "One screen answering: can I trust these numbers?" - a health check, not analytics. */
@Composable
fun CorrectnessDashboardScreen(onBack: () -> Unit, viewModel: CorrectnessDashboardViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsState()
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Correctness dashboard", style = MaterialTheme.typography.headlineSmall)

        val current = report
        if (current == null) {
            Text("Loading...", style = MaterialTheme.typography.bodyMedium)
        } else {
            DashboardContent(current)
        }
    }
}

@Composable
private fun DashboardContent(report: CorrectnessReport) {
    Text("Pending review: ${report.pendingReviewCount}", style = MaterialTheme.typography.bodyMedium)
    Text("Unmatched transfers: ${report.unmatchedTransferCount}", style = MaterialTheme.typography.bodyMedium)

    Text("Reconciliation by account", style = MaterialTheme.typography.titleSmall)
    LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(report.accountReconciliations) { ReconciliationRow(it) }
    }

    Text("Statement mismatches", style = MaterialTheme.typography.titleSmall)
    if (report.statementMismatches.isEmpty()) {
        Text("None", style = MaterialTheme.typography.bodySmall)
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(report.statementMismatches) { MismatchRow(it) }
        }
    }
}

@Composable
private fun ReconciliationRow(reconciliation: AccountReconciliation) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(reconciliation.accountName)
            Text(
                formatLastReconciled(reconciliation.lastReconciledAt) + if (reconciliation.stale) " (stale)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun MismatchRow(mismatch: StatementMismatch) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mismatch.accountName)
            Text(Paise(mismatch.amount).format(), style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

private fun formatLastReconciled(epochMillis: Long?): String {
    if (epochMillis == null) return "never"
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}
