package com.amandhakar.ledgerly.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amandhakar.ledgerly.BuildConfig
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.update.UpdateInfo

@Composable
fun UpdateScreen(onBack: () -> Unit, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Updates", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Current version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
        )

        when (val current = state) {
            is UpdateUiState.Checking -> {
                CircularProgressIndicator()
                Text("Checking for updates...")
            }

            is UpdateUiState.UpToDate -> {
                Text("You're up to date.")
                Button(onClick = viewModel::checkForUpdate) { Text("Check again") }
            }

            is UpdateUiState.Available -> AvailableUpdate(current.info, onDownload = viewModel::downloadAndVerify)

            is UpdateUiState.Downloading -> {
                CircularProgressIndicator()
                Text("Downloading update...")
            }

            is UpdateUiState.ReadyToInstall -> {
                Text("Downloaded and verified — signature matches this app.")
                Button(onClick = { viewModel.install(current.file) }) { Text("Install") }
            }

            is UpdateUiState.Failed -> {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::checkForUpdate) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun AvailableUpdate(info: UpdateInfo, onDownload: (UpdateInfo) -> Unit) {
    Text("Version ${info.versionName} is available", style = MaterialTheme.typography.titleMedium)
    info.releaseNotes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    // tasks/update-system.md's schema-migration guard: a migration bug on a finance ledger must
    // never be a silent background event, so this has to be seen before the user taps install.
    val targetSchema = info.targetSchemaVersion
    if (targetSchema != null && targetSchema > LedgerlyDatabase.SCHEMA_VERSION) {
        Text(
            "This update changes how your data is stored on disk. It will be migrated " +
                "automatically the first time you open the app afterward.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Button(onClick = { onDownload(info) }, modifier = Modifier.fillMaxWidth()) {
        Text("Download and install")
    }
}
