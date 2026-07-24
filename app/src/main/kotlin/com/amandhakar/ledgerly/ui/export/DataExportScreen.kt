package com.amandhakar.ledgerly.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.ledger.DataExportViewModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Task 1.17: "CSV and JSON, to a user-chosen location via ACTION_CREATE_DOCUMENT... the only
 * recovery path if the device, the app, or the signing key is lost" (docs/signing.md).
 * `ActivityResultContracts.CreateDocument` is the Compose-idiomatic wrapper around that intent.
 */
// one launcher per SAF operation (export/import x2 formats); splitting would scatter one screen
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun DataExportScreen(onBack: () -> Unit, viewModel: DataExportViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf<String?>(null) }
    val today = Instant.now().atZone(ZoneId.systemDefault()).format(FILENAME_FORMATTER)

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val csv = viewModel.exportCsv()
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it).use { writer -> writer.write(csv) } }
            statusText = "Exported CSV"
        }
    }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.exportJson()
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it).use { writer -> writer.write(json) } }
            statusText = "Exported JSON"
        }
    }
    val goldenTestExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.exportGoldenTests()
            context.contentResolver.openOutputStream(uri)?.use { OutputStreamWriter(it).use { writer -> writer.write(json) } }
            statusText = "Exported golden tests"
        }
    }
    val goldenTestImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            }
            statusText = if (json == null) {
                "Could not read the selected file"
            } else {
                runCatching { viewModel.importGoldenTests(json) }
                    .fold({ "Imported $it golden test(s)" }, { "That file isn't a valid golden test export" })
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Export data", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Until cloud backup ships, this export is the only way to recover your ledger if this " +
                "device, the app, or its signing key is lost. Store it somewhere safe.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { csvLauncher.launch("ledgerly-export-$today.csv") }) { Text("Export as CSV") }
        Button(onClick = { jsonLauncher.launch("ledgerly-export-$today.json") }) { Text("Export as JSON") }

        Text(
            "Golden tests are the parser's regression suite (Task 1.14) — export them so the " +
                "corpus survives a reinstall, then import on the new install.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { goldenTestExportLauncher.launch("ledgerly-golden-tests-$today.json") }) {
            Text("Export golden tests")
        }
        Button(onClick = { goldenTestImportLauncher.launch(arrayOf("application/json")) }) {
            Text("Import golden tests")
        }
        statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
