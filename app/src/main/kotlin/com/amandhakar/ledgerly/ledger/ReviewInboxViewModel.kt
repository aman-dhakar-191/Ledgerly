package com.amandhakar.ledgerly.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One [Transaction] awaiting review, paired with the [RawSms] it came from and a freshly
 * recomputed [GenericExtraction] — spans/confidence aren't stored on [Transaction] itself (Task
 * 1.13: "shows the raw SMS body with extracted spans highlighted"), so the screen re-derives them
 * from the immutable archive rather than needing new columns for display-only data.
 */
data class ReviewItem(val transaction: Transaction, val rawSms: RawSms?, val extraction: GenericExtraction?)

data class ReviewInboxUiState(val items: List<ReviewItem> = emptyList())

@HiltViewModel
class ReviewInboxViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val rawSmsDao: RawSmsDao,
    private val reviewConfirmationService: ReviewConfirmationService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewInboxUiState())
    val uiState: StateFlow<ReviewInboxUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transactionDao.observeByStatus(TransactionStatus.PENDING_REVIEW).collect { transactions ->
                _uiState.value = ReviewInboxUiState(
                    transactions.sortedByDescending { it.occurredAt }.map { toReviewItem(it) },
                )
            }
        }
    }

    private suspend fun toReviewItem(transaction: Transaction): ReviewItem {
        val rawSms = transaction.rawSmsId?.let { rawSmsDao.getById(it) }
        val extraction = rawSms?.let { GenericExtractor.extract(it.body, it.receivedAt) }
        return ReviewItem(transaction, rawSms, extraction)
    }

    fun confirm(item: ReviewItem, correction: ReviewCorrection) {
        viewModelScope.launch {
            reviewConfirmationService.confirm(item.transaction, correction)
        }
    }

    fun reject(item: ReviewItem) {
        viewModelScope.launch {
            transactionDao.update(
                item.transaction.copy(status = TransactionStatus.REJECTED, updatedAt = System.currentTimeMillis()),
            )
        }
    }

    /** Task 1.13's "ignore all from this sender" bulk action. */
    fun rejectAllFromInstitution(institution: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _uiState.value.items
                .filter { it.rawSms?.institution == institution }
                .forEach { transactionDao.update(it.transaction.copy(status = TransactionStatus.REJECTED, updatedAt = now)) }
        }
    }
}
