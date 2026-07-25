package com.amandhakar.ledgerly.ui.senders

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
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.ledger.PendingInstitution
import com.amandhakar.ledgerly.ledger.SenderClassificationViewModel

private val CLASSIFICATIONS = listOf(
    SenderType.BANK to true,
    SenderType.CARD to true,
    SenderType.OTP to false,
    SenderType.PROMO to false,
    SenderType.SPAM to false,
    SenderType.UNKNOWN to false,
)

/** Button label per type — everything but UNKNOWN reads fine as its enum name. */
private fun SenderType.label(): String = if (this == SenderType.UNKNOWN) "NOT FINANCIAL" else name

/**
 * docs/parser.md's new-institution prompt: "New institution {id}. Bank / Card / OTP / Spam?" —
 * only BANK/CARD become trusted; the rest stay untrusted and their archive is left `IGNORED`
 * forever, same outcome as never classifying them at all.
 *
 * NOT FINANCIAL (found in live use, not the corpus): the pre-filter's TRANSACTION default is
 * content-only, so any alphanumeric sender whose message happens to use money language — a
 * government agency's challan-paid notice, a telecom's recharge receipt — reaches this screen
 * exactly like a real bank. SPAM/PROMO already behave identically (untrusted, archive stays
 * IGNORED forever), but calling a government agency "SPAM" is just wrong; this gives it an
 * honest label without changing behavior.
 */
@Composable
fun SenderClassificationScreen(onBack: () -> Unit, viewModel: SenderClassificationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Classify senders", style = MaterialTheme.typography.headlineSmall)

        if (state.pendingInstitutions.isEmpty()) {
            Text("Nothing waiting — every sender seen so far has been classified.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.pendingInstitutions, key = { it.institution }) { institution ->
                    InstitutionRow(institution, onClassify = { type, trusted -> viewModel.classify(institution, type, trusted) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun InstitutionRow(institution: PendingInstitution, onClassify: (SenderType, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("New institution: ${institution.institution}", style = MaterialTheme.typography.titleMedium)
        Text("Raw senders: ${institution.senderIds.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        institution.sampleMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CLASSIFICATIONS.forEach { (type, trusted) ->
                TextButton(onClick = { onClassify(type, trusted) }) { Text(type.label()) }
            }
        }
    }
}
