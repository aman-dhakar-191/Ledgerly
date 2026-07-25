package com.amandhakar.ledgerly.ui.review

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.ledger.ReviewCorrection
import com.amandhakar.ledgerly.ledger.ReviewInboxViewModel
import com.amandhakar.ledgerly.ledger.ReviewItem
import com.amandhakar.ledgerly.model.money.Paise
import com.amandhakar.ledgerly.parser.ExtractedField
import com.amandhakar.ledgerly.parser.GenericExtraction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CONFIDENT_HIGHLIGHT = SpanStyle(background = Color(0x3300C853))
private val LOW_CONFIDENCE_HIGHLIGHT = SpanStyle(background = Color(0x33FF6D00))
private const val CONFIDENT_THRESHOLD = 0.9f

/**
 * Task 1.13, "the screen that determines whether this app gets used or abandoned." Every
 * [com.amandhakar.ledgerly.database.entity.TransactionStatus.PENDING_REVIEW] transaction, newest
 * first, with the raw SMS body highlighted by field confidence and one-tap confirm/reject.
 */
@Composable
fun ReviewInboxScreen(onBack: () -> Unit, viewModel: ReviewInboxViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Review inbox", style = MaterialTheme.typography.headlineSmall)

        if (state.items.isEmpty()) {
            Text(
                "Nothing to review — every message the parser could suggest a transaction from has been confirmed.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.items, key = { it.transaction.id }) { item ->
                    ReviewItemCard(
                        item = item,
                        onConfirm = { correction -> viewModel.confirm(item, correction) },
                        onReject = { viewModel.reject(item) },
                        onRejectAllFromSender = {
                            item.rawSms?.institution?.let { viewModel.rejectAllFromInstitution(it) }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Suppress("LongMethod") // one field per editable transaction attribute; splitting would scatter one cohesive form
@Composable
private fun ReviewItemCard(
    item: ReviewItem,
    onConfirm: (ReviewCorrection) -> Unit,
    onReject: () -> Unit,
    onRejectAllFromSender: () -> Unit,
) {
    val transaction = item.transaction
    var amountText by remember(transaction.id) { mutableStateOf(Paise(transaction.amount).format("").trim()) }
    var direction by remember(transaction.id) { mutableStateOf(transaction.direction) }
    var merchantText by remember(transaction.id) { mutableStateOf(transaction.merchantRaw.orEmpty()) }
    var balanceText by remember(transaction.id) {
        mutableStateOf(transaction.balanceAfter?.let { Paise(it).format("").trim() }.orEmpty())
    }
    var markInternalTransfer by remember(transaction.id) { mutableStateOf(false) }

    val amount = Paise.fromRupeeString(amountText)
    val balance = if (balanceText.isBlank()) null else Paise.fromRupeeString(balanceText)
    val canConfirm = amount != null && (balanceText.isBlank() || balance != null)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.rawSms != null && item.extraction != null) {
            Text(
                highlightedBody(item.rawSms.body, item.extraction),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(formatEpochMillis(transaction.occurredAt), style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { direction = Direction.DEBIT }) {
                Text("DEBIT", style = directionLabelStyle(direction == Direction.DEBIT))
            }
            TextButton(onClick = { direction = Direction.CREDIT }) {
                Text("CREDIT", style = directionLabelStyle(direction == Direction.CREDIT))
            }
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount") },
            isError = amount == null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = merchantText,
            onValueChange = { merchantText = it },
            label = { Text("Merchant") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = balanceText,
            onValueChange = { balanceText = it },
            label = { Text("Balance after (optional)") },
            isError = balanceText.isNotBlank() && balance == null,
            modifier = Modifier.fillMaxWidth(),
        )

        Row {
            Checkbox(
                checked = markInternalTransfer,
                onCheckedChange = { markInternalTransfer = it },
                enabled = merchantText.isNotBlank(),
            )
            Text(
                "This is a transfer to my own account",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = canConfirm,
                onClick = {
                    onConfirm(
                        ReviewCorrection(
                            amount = amount!!.value,
                            direction = direction,
                            merchant = merchantText.ifBlank { null },
                            occurredAt = transaction.occurredAt,
                            balanceAfter = balance?.value,
                            markInternalTransfer = markInternalTransfer,
                        ),
                    )
                },
            ) { Text("Confirm") }
            TextButton(onClick = onReject) { Text("Reject") }
            TextButton(onClick = onRejectAllFromSender) { Text("Ignore all from this sender") }
        }
    }
}

@Composable
private fun directionLabelStyle(selected: Boolean) =
    if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall

private fun highlightedBody(body: String, extraction: GenericExtraction): AnnotatedString {
    val fields: List<ExtractedField<*>> = listOf(
        extraction.amount,
        extraction.direction,
        extraction.accountLast4,
        extraction.balanceAfter,
        extraction.merchant,
        extraction.occurredAt,
        extraction.reference,
    )
    return buildAnnotatedString {
        append(body)
        fields.forEach { field ->
            val span = field.span ?: return@forEach
            val style = if (field.confidence >= CONFIDENT_THRESHOLD) CONFIDENT_HIGHLIGHT else LOW_CONFIDENCE_HIGHLIGHT
            addStyle(style, span.first, span.last + 1)
        }
    }
}

private fun formatEpochMillis(epochMillis: Long): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}
