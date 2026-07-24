package com.amandhakar.ledgerly.ui.unlock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.crypto.android.CryptoManager
import com.amandhakar.ledgerly.crypto.android.InvalidPassphraseException
import com.amandhakar.ledgerly.crypto.android.PassphraseReentryRequiredException
import com.amandhakar.ledgerly.crypto.android.SecureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val MIN_PASSPHRASE_LENGTH = 12

sealed interface UnlockUiState {
    data object NeedsSetup : UnlockUiState
    data class NeedsUnlock(val passphraseReentryRequired: Boolean = false) : UnlockUiState
    data object Unlocked : UnlockUiState
}

sealed interface UnlockEvent {
    data object WrongPassphrase : UnlockEvent
    data object BiometricFailed : UnlockEvent
    data class SetupFailed(val message: String) : UnlockEvent
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val secureStore: SecureStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UnlockEvent>()
    val events: SharedFlow<UnlockEvent> = _events.asSharedFlow()

    private fun initialState(): UnlockUiState =
        if (!secureStore.isSetUp()) UnlockUiState.NeedsSetup else UnlockUiState.NeedsUnlock()

    fun setupPassphrase(passphrase: CharArray) {
        viewModelScope.launch {
            cryptoManager.setupPassphrase(passphrase)
                .onSuccess { _uiState.value = UnlockUiState.Unlocked }
                .onFailure { _events.emit(UnlockEvent.SetupFailed(it.message ?: "Setup failed")) }
        }
    }

    fun unlockWithBiometric(activity: FragmentActivity) {
        viewModelScope.launch {
            cryptoManager.unlockWithBiometric(activity)
                .onSuccess { _uiState.value = UnlockUiState.Unlocked }
                .onFailure { error ->
                    if (error is PassphraseReentryRequiredException) {
                        _uiState.value = UnlockUiState.NeedsUnlock(passphraseReentryRequired = true)
                    } else {
                        _events.emit(UnlockEvent.BiometricFailed)
                    }
                }
        }
    }

    fun unlockWithPassphrase(passphrase: CharArray) {
        viewModelScope.launch {
            cryptoManager.unlockWithPassphrase(passphrase)
                .onSuccess { _uiState.value = UnlockUiState.Unlocked }
                .onFailure { error ->
                    if (error is InvalidPassphraseException) {
                        _events.emit(UnlockEvent.WrongPassphrase)
                    } else {
                        _events.emit(UnlockEvent.BiometricFailed)
                    }
                }
        }
    }
}
