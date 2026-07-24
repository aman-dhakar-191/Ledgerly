package com.amandhakar.ledgerly.ui.senders

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
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.ledger.SenderClassificationViewModel

private val CLASSIFICATIONS = listOf(
    SenderType.BANK to true,
    SenderType.CARD to true,
    SenderType.OTP to false,
    SenderType.PROMO to false,
    SenderType.SPAM to false,
)

/**
 * docs/parser.md's new-institution prompt: "New institution {id}. Bank / Card / OTP / Spam?" —
 * only BANK/CARD become trusted; the rest stay untrusted and their archive is left `IGNORED`
 * forever, same outcome as never classifying them at all.
 */
@Composable
fun SenderClassificationScreen(onBack: () -> Unit, viewModel: SenderClassificationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Classify senders", style = MaterialTheme.typography.headlineSmall)

        if (state.pendingSenders.isEmpty()) {
            Text("Nothing waiting — every sender seen so far has been classified.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.pendingSenders, key = { it.senderId }) { sender ->
                    SenderRow(sender, onClassify = { type, trusted -> viewModel.classify(sender, type, trusted) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SenderRow(sender: SenderRegistry, onClassify: (SenderType, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("New institution: ${sender.institution}", style = MaterialTheme.typography.titleMedium)
        Text("Raw sender: ${sender.senderId}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CLASSIFICATIONS.forEach { (type, trusted) ->
                TextButton(onClick = { onClassify(type, trusted) }) { Text(type.name) }
            }
        }
    }
}
