package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SenderClassificationUiState(val pendingSenders: List<SenderRegistry> = emptyList())

/**
 * docs/parser.md's sender-trust gate: "institution in SenderRegistry? no -> prompt: 'New
 * institution {id}. Bank / Card / OTP / Spam?' untrusted -> store RawSms, mark IGNORED, stop."
 * [SmsParsingPipeline] auto-registers a sender as untrusted/[SenderType.UNKNOWN] the first time it
 * sees one; this screen is where the user actually resolves that gate.
 */
@HiltViewModel
class SenderClassificationViewModel @Inject constructor(
    private val senderRegistryDao: SenderRegistryDao,
    private val smsParsingPipeline: SmsParsingPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SenderClassificationUiState())
    val uiState: StateFlow<SenderClassificationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            senderRegistryDao.observeAll().collect { all ->
                _uiState.value = SenderClassificationUiState(pendingSenders = all.filter { !it.trusted })
            }
        }
    }

    /** Trusting a sender re-runs the pipeline over its previously-`IGNORED` archive, not just future messages. */
    fun classify(sender: SenderRegistry, type: SenderType, trusted: Boolean) {
        viewModelScope.launch {
            senderRegistryDao.update(
                sender.copy(type = type, trusted = trusted, updatedAt = System.currentTimeMillis()),
            )
            if (trusted) smsParsingPipeline.reprocessInstitution(sender.institution)
        }
    }
}
