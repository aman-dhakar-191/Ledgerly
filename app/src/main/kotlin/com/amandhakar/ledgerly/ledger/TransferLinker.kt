package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.dao.TransferDao
import com.amandhakar.ledgerly.database.entity.DetectedBy
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.database.entity.Transfer
import com.amandhakar.ledgerly.database.entity.TransferKind
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.first

private const val TRANSFER_WINDOW_MILLIS = 72L * 60 * 60 * 1000

/**
 * Task 2.1/docs/schema.md's Transfer entity: opposite directions, exact amount, both accounts
 * owned by the user, within 72 hours. [findCounterpart] only ever suggests a candidate — every
 * link this produces is confirmed by the user from the transaction detail screen
 * ("auto-detection will miss cases, and a wrong link is worse than none"), so every [link] call is
 * [DetectedBy.MANUAL]. A background pass that links without confirmation (Task 2.2's card-payment
 * matching) is deliberately a separate, narrower matcher, not a call site of this class.
 */
interface TransferDetector {
    suspend fun findCounterpart(txn: Transaction): Transaction?
    suspend fun link(from: Transaction, to: Transaction, kind: TransferKind): Transfer
    suspend fun unlink(transferId: String)
}

class TransferLinker @Inject constructor(
    private val transactionDao: TransactionDao,
    private val transferDao: TransferDao,
    private val accountDao: AccountDao,
) : TransferDetector {

    override suspend fun findCounterpart(txn: Transaction): Transaction? {
        val ownAccountIds = accountDao.observeActive().first().map { it.id }.toSet()
        if (txn.accountId !in ownAccountIds || txn.transferId != null) return null

        val windowStart = txn.occurredAt - TRANSFER_WINDOW_MILLIS
        val windowEnd = txn.occurredAt + TRANSFER_WINDOW_MILLIS
        return ownAccountIds
            .filterNot { it == txn.accountId }
            .flatMap { accountId -> transactionDao.observeByAccountAndDateRange(accountId, windowStart, windowEnd).first() }
            .filter { candidate ->
                candidate.amount == txn.amount &&
                    candidate.direction != txn.direction &&
                    candidate.transferId == null &&
                    candidate.status != TransactionStatus.REJECTED
            }
            .minByOrNull { abs(it.occurredAt - txn.occurredAt) }
    }

    override suspend fun link(from: Transaction, to: Transaction, kind: TransferKind): Transfer {
        val now = System.currentTimeMillis()
        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            fromTxnId = from.id,
            toTxnId = to.id,
            kind = kind,
            detectedBy = DetectedBy.MANUAL,
            confidence = 1f,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transferDao.insert(transfer)
        transactionDao.update(from.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
        transactionDao.update(to.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
        return transfer
    }

    override suspend fun unlink(transferId: String) {
        val now = System.currentTimeMillis()
        transactionDao.getByTransferId(transferId).forEach { txn ->
            transactionDao.update(txn.copy(transferId = null, isInternal = false, updatedAt = now))
        }
        transferDao.softDelete(transferId, now)
    }
}
