package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.dao.TransferDao
import com.amandhakar.ledgerly.database.entity.DetectedBy
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.Transfer
import com.amandhakar.ledgerly.database.entity.TransferKind
import com.amandhakar.ledgerly.parser.TxnClass
import com.amandhakar.ledgerly.parser.classifyTransaction
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import com.amandhakar.ledgerly.parser.Direction as ParserDirection

private const val REFUND_WINDOW_MILLIS = 90L * 24 * 60 * 60 * 1000

/**
 * Task 2.8/docs/corpus-findings.md §6: a refund is not income, it nets against the spend it
 * refunds - matched by same account + same merchant + a still-unlinked prior debit whose amount
 * can cover the refund (supports partial refunds), most recent first within 90 days
 * (tasks/phase-2.md). The same match also covers "failed-payment reversals" (a debit followed by
 * an equal credit within days) - both shapes classify as [TxnClass.REVERSAL] and an equal amount
 * already satisfies "the spend's amount covers the refund," so no separate path is needed.
 *
 * No match leaves the credit standalone: [SmsParsingPipeline] already marks every
 * [TxnClass.REVERSAL] credit `isInternal` regardless of whether this matcher finds anything, so an
 * unmatched refund still never counts as income - this class only ever adds the link, never the
 * "not income" marking itself.
 */
class RefundMatcher @Inject constructor(
    private val transactionDao: TransactionDao,
    private val transferDao: TransferDao,
) {
    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    suspend fun tryMatch(transaction: Transaction, sms: RawSms) {
        if (transaction.transferId != null) return
        if (transaction.direction != Direction.CREDIT) return
        if (classifyTransaction(sms.body, ParserDirection.CREDIT) != TxnClass.REVERSAL) return
        val merchant = transaction.merchantRaw ?: return

        val spend = findOriginalSpend(transaction, merchant) ?: return
        link(spend, transaction)
    }

    private suspend fun findOriginalSpend(refund: Transaction, merchant: String): Transaction? {
        val windowStart = refund.occurredAt - REFUND_WINDOW_MILLIS
        return transactionDao.observeByAccountAndDateRange(refund.accountId, windowStart, refund.occurredAt).first()
            .filter { it.direction == Direction.DEBIT && it.transferId == null }
            .filter { it.merchantRaw?.trim().equals(merchant.trim(), ignoreCase = true) }
            .filter { it.amount >= refund.amount }
            .maxByOrNull { it.occurredAt }
    }

    private suspend fun link(spend: Transaction, refund: Transaction) {
        val now = System.currentTimeMillis()
        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            fromTxnId = spend.id,
            toTxnId = refund.id,
            kind = TransferKind.REFUND,
            detectedBy = DetectedBy.AUTO,
            confidence = 1f,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transferDao.insert(transfer)
        // The spend stays a real (now-reduced) expense - only the refund itself is never income.
        transactionDao.update(spend.copy(transferId = transfer.id, updatedAt = now))
        transactionDao.update(refund.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
    }
}
