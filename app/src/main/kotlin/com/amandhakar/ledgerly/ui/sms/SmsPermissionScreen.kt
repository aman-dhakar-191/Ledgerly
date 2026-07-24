package com.amandhakar.ledgerly.ui.sms

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amandhakar.ledgerly.ingest.ArchiveImportWorker

private const val RATIONALE = "Ledgerly reads your bank and card SMS to build your ledger " +
    "automatically. Messages are processed entirely on this device and never leave it. " +
    "You can decline and enter transactions manually instead — nothing else in the app " +
    "requires this permission."

/**
 * Task 1.1: "Graceful degradation: if permission is denied, the app still works with manual
 * entry" — [onDone] fires either way, granted or declined, so the caller never blocks on this.
 */
@Composable
fun SmsPermissionScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var deniedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            ArchiveImportWorker.enqueue(context)
            onDone()
        } else {
            deniedOnce = true
        }
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            ArchiveImportWorker.enqueue(context)
            onDone()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import transactions from SMS", style = MaterialTheme.typography.headlineSmall)
        Text(RATIONALE, style = MaterialTheme.typography.bodyMedium)

        if (deniedOnce) {
            Text(
                "Permission declined — you can turn this on later from system Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Grant access")
        }

        TextButton(onClick = onDone) {
            Text("Not now — I'll enter transactions manually")
        }
    }
}
