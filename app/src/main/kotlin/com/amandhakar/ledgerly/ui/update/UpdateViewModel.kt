package com.amandhakar.ledgerly.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.update.UpdateChecker
import com.amandhakar.ledgerly.update.UpdateInfo
import com.amandhakar.ledgerly.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data object Downloading : UpdateUiState
    data class ReadyToInstall(val file: File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Checking)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    init {
        checkForUpdate()
    }

    fun checkForUpdate() {
        _uiState.value = UpdateUiState.Checking
        viewModelScope.launch {
            _uiState.value = try {
                val info = updateChecker.checkForUpdate()
                if (info == null) UpdateUiState.UpToDate else UpdateUiState.Available(info)
            } catch (e: IOException) {
                // The reason (timeout, DNS failure, HTTP status) matters - a generic message here
                // makes "no internet" indistinguishable from "GitHub rejected the request", which
                // cost real debugging time before this was surfaced.
                val reason = e.message ?: e::class.simpleName ?: "unknown error"
                UpdateUiState.Failed("Couldn't check for updates ($reason). Check your connection and try again.")
            }
        }
    }

    fun downloadAndVerify(info: UpdateInfo) {
        _uiState.value = UpdateUiState.Downloading
        viewModelScope.launch {
            updateInstaller.download(info)
                .onSuccess { file -> _uiState.value = verifyOrReject(file) }
                .onFailure { _uiState.value = UpdateUiState.Failed("Download failed. Try again.") }
        }
    }

    // Mismatch is loud, not silent (tasks/update-system.md): Android's installer would reject it
    // anyway, but the user should be told an update was rejected rather than see nothing happen.
    private fun verifyOrReject(file: File): UpdateUiState {
        if (updateInstaller.verifySignature(file)) return UpdateUiState.ReadyToInstall(file)
        file.delete()
        return UpdateUiState.Failed("Update rejected: its signature didn't match this app. Not installed.")
    }

    fun install(file: File) = updateInstaller.requestInstall(file)
}
