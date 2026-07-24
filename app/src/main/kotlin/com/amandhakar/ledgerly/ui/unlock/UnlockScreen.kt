package com.amandhakar.ledgerly.ui.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amandhakar.ledgerly.ingest.LastProcessedStore
import com.amandhakar.ledgerly.ui.sms.SmsPermissionScreen
import com.amandhakar.ledgerly.ui.update.UpdateScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Must be shown verbatim (docs/crypto.md) and cannot be skipped. */
private const val RECOVERY_WARNING = "Your passphrase is the only way to recover your data. It " +
    "cannot be reset — not by Google, not by this app, not by anyone. If you lose both your " +
    "passphrase and this device, your backups are permanently unreadable. Write it down and " +
    "store it somewhere physical."

@Composable
fun UnlockScreen(
    activity: FragmentActivity,
    openUpdate: Boolean = false,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // CryptoManagerImpl.lock() zeroes the master key on background (ProcessLifecycleOwner.onStop),
    // but this Activity doesn't get recreated on a plain resume, so without this the same
    // "Unlocked" screen would just keep showing after backgrounding and reopening the app —
    // refreshLockState() is what actually notices the key is gone and routes back to the gate.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel by rememberUpdatedState(viewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentViewModel.refreshLockState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            is UnlockUiState.NeedsSetup -> PassphraseSetupScreen(onSubmit = viewModel::setupPassphrase)
            is UnlockUiState.NeedsUnlock -> UnlockGateScreen(
                activity = activity,
                passphraseReentryRequired = current.passphraseReentryRequired,
                viewModel = viewModel,
            )
            is UnlockUiState.Unlocked -> HomeScreen(openUpdate = openUpdate)
        }
    }
}

/**
 * Placeholder home — Task 1.15 builds the real ledger UI. The only thing that lives here today is
 * the entry point into the dedicated update screen (Task 1.3), reached either by tapping "Check
 * for updates" or by tapping an update-available notification (`openUpdate`).
 */
@Composable
private fun HomeScreen(openUpdate: Boolean) {
    var showUpdateScreen by remember { mutableStateOf(openUpdate) }
    var showSmsPermissionScreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var lastProcessedAt by remember { mutableStateOf<Long?>(null) }

    // Re-read whenever the SMS permission/import flow closes, since that's when a fresh import or
    // the periodic catch-up worker (Task 1.16) may have just advanced the mark.
    LaunchedEffect(showSmsPermissionScreen) {
        if (!showSmsPermissionScreen) {
            lastProcessedAt = LastProcessedStore(context).getLastProcessedAt()
        }
    }

    if (showUpdateScreen) {
        UpdateScreen(onBack = { showUpdateScreen = false })
    } else if (showSmsPermissionScreen) {
        SmsPermissionScreen(onDone = { showSmsPermissionScreen = false })
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Unlocked")
            Button(onClick = { showUpdateScreen = true }) { Text("Check for updates") }
            Button(onClick = { showSmsPermissionScreen = true }) { Text("Import transactions from SMS") }
            Text(
                "Last SMS processed: ${formatLastProcessed(lastProcessedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatLastProcessed(epochMillis: Long?): String {
    if (epochMillis == null) return "never"
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun PassphraseSetupScreen(onSubmit: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }

    val score = PassphraseStrength.score(passphrase)
    val longEnough = passphrase.length >= MIN_PASSPHRASE_LENGTH
    val strongEnough = score >= PassphraseStrength.MIN_SCORE
    val matches = passphrase.isNotEmpty() && passphrase == confirmPassphrase
    val canContinue = longEnough && strongEnough && matches && acknowledged

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up your passphrase", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase (min $MIN_PASSPHRASE_LENGTH characters)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        LinearProgressIndicator(progress = { (score + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        Text(strengthLabel(score), style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = { confirmPassphrase = it },
            label = { Text("Confirm passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            isError = confirmPassphrase.isNotEmpty() && !matches,
        )

        Text(RECOVERY_WARNING, style = MaterialTheme.typography.bodyMedium)

        Column {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                Text("I have written this down and understand it cannot be recovered")
            }
        }

        Button(
            onClick = { onSubmit(passphrase.toCharArray()) },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

private fun strengthLabel(score: Int): String = when (score) {
    0 -> "Very weak"
    1 -> "Weak"
    2 -> "Fair"
    3 -> "Good"
    else -> "Strong"
}

@Composable
private fun UnlockGateScreen(
    activity: FragmentActivity,
    passphraseReentryRequired: Boolean,
    viewModel: UnlockViewModel,
) {
    var showPassphraseField by remember { mutableStateOf(passphraseReentryRequired) }
    var passphrase by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!passphraseReentryRequired) {
            viewModel.unlockWithBiometric(activity)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            errorText = when (event) {
                UnlockEvent.WrongPassphrase -> "Incorrect passphrase"
                UnlockEvent.BiometricFailed -> "Biometric authentication failed"
                is UnlockEvent.SetupFailed -> event.message
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Unlock Ledgerly", style = MaterialTheme.typography.headlineSmall)

        if (passphraseReentryRequired) {
            Text("Please re-enter your passphrase (30-day security check)")
        }

        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!passphraseReentryRequired) {
            Button(onClick = { viewModel.unlockWithBiometric(activity) }, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock with biometrics")
            }
            TextButton(onClick = { showPassphraseField = true }) {
                Text("Use passphrase instead")
            }
        }

        if (showPassphraseField) {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.unlockWithPassphrase(passphrase.toCharArray()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlock")
            }
        }
    }
}
