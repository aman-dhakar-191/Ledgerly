package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Transaction
import javax.inject.Inject

/**
 * Task 1.15's "transaction detail with edit and audit history" — editing an already-`CONFIRMED`
 * transaction from the ledger itself, as opposed to [ReviewConfirmationService]'s first
 * confirmation out of the review inbox. No rule generation or golden test here: those only make
 * sense the first time a message's shape is confirmed, not on every later correction.
 */
class TransactionEditor @Inject constructor(
    private val transactionDao: TransactionDao,
    private val transactionAuditDao: TransactionAuditDao,
) {
    suspend fun edit(transaction: Transaction, correction: ReviewCorrection) {
        val now = System.currentTimeMillis()
        writeTransactionEditAudit(transactionAuditDao, transaction, correction, now)
        transactionDao.update(
            transaction.copy(
                amount = correction.amount,
                direction = correction.direction,
                merchantRaw = correction.merchant,
                occurredAt = correction.occurredAt,
                balanceAfter = correction.balanceAfter,
                updatedAt = now,
            ),
        )
    }
}
