package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.entity.AuditReason
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import java.util.UUID

/**
 * CONTEXT.md invariant #5: "Every user edit to a transaction writes an audit row." Shared by
 * [ReviewConfirmationService] (a `PENDING_REVIEW` transaction's first confirmation) and
 * [TransactionEditor] (editing an already-`CONFIRMED` one from the ledger) — same diff, same
 * reason, different callers.
 */
suspend fun writeTransactionEditAudit(
    transactionAuditDao: TransactionAuditDao,
    transaction: Transaction,
    correction: ReviewCorrection,
    now: Long,
) {
    val changes = listOf(
        "amount" to (transaction.amount.toString() to correction.amount.toString()),
        "direction" to (transaction.direction.toString() to correction.direction.toString()),
        "merchant_raw" to (transaction.merchantRaw.orEmpty() to correction.merchant.orEmpty()),
        "occurred_at" to (transaction.occurredAt.toString() to correction.occurredAt.toString()),
        "balance_after" to (transaction.balanceAfter?.toString().orEmpty() to correction.balanceAfter?.toString().orEmpty()),
    ).filter { (_, values) -> values.first != values.second }

    changes.forEach { (field, values) ->
        transactionAuditDao.insert(
            TransactionAudit(
                id = UUID.randomUUID().toString(),
                transactionId = transaction.id,
                field = field,
                oldValue = values.first,
                newValue = values.second,
                changedAt = now,
                reason = AuditReason.USER_EDIT,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }
}
