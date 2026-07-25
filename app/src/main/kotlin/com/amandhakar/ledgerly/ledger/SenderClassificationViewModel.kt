package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.entity.SenderType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One row per institution, not per raw sender ID — docs/corpus-findings.md §1's 13+ raw senders
 * for one ICICI institution must not become 13+ separate classification prompts. [sampleMessage] is
 * the most recent raw body from any of [senderIds] — the sender ID alone ("FNCEGR") often isn't
 * enough to tell what an institution even is. */
data class PendingInstitution(val institution: String, val senderIds: List<String>, val sampleMessage: String?)

data class SenderClassificationUiState(val pendingInstitutions: List<PendingInstitution> = emptyList())

/**
 * docs/parser.md's sender-trust gate: "institution in SenderRegistry? no -> prompt: 'New
 * institution {id}. Bank / Card / OTP / Spam?' untrusted -> store RawSms, mark IGNORED, stop."
 * [SmsParsingPipeline] auto-registers a sender as untrusted/[SenderType.UNKNOWN] the first time it
 * sees one; this screen is where the user actually resolves that gate.
 */
@HiltViewModel
class SenderClassificationViewModel @Inject constructor(
    private val senderRegistryDao: SenderRegistryDao,
    private val rawSmsDao: RawSmsDao,
    private val smsParsingPipeline: SmsParsingPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SenderClassificationUiState())
    val uiState: StateFlow<SenderClassificationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            senderRegistryDao.observeAll().collect { all ->
                // Untrusted alone isn't "still pending" - OTP/PROMO/SPAM/NOT_FINANCIAL are all
                // untrusted too, by design, once classified. UNKNOWN is the one type nothing ever
                // sets except SmsParsingPipeline's first-sight default, so it's the only reliable
                // "never actually resolved this" signal (found in live use: every classify button
                // except BANK/CARD looked like it did nothing, because the row never left this list).
                val pending = all.filter { !it.trusted && it.type == SenderType.UNKNOWN }
                    .groupBy { it.institution }
                    .map { (institution, rows) ->
                        val senderIds = rows.map { it.senderId }
                        PendingInstitution(institution, senderIds, rawSmsDao.getMostRecentBySenders(senderIds)?.body)
                    }
                _uiState.value = SenderClassificationUiState(pendingInstitutions = pending)
            }
        }
    }

    /**
     * Applies one classification to every raw sender ID sharing [institution] - the user is
     * classifying the institution, not any one telecom route it happens to arrive from. Trusting it
     * re-runs the pipeline over its previously-`IGNORED` archive, not just future messages.
     */
    fun classify(institution: PendingInstitution, type: SenderType, trusted: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            senderRegistryDao.observeAll().first()
                .filter { it.institution == institution.institution && !it.trusted }
                .forEach { row -> senderRegistryDao.update(row.copy(type = type, trusted = trusted, updatedAt = now)) }
            if (trusted) smsParsingPipeline.reprocessInstitution(institution.institution)
        }
    }
}
