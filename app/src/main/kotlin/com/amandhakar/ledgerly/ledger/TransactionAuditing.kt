package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
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
 *
 * Returns whether anything actually changed, so the caller knows whether to also count this as a
 * correction against whichever [com.amandhakar.ledgerly.database.entity.ParserRule] produced the
 * transaction (docs/parser.md's rule health: "correction_count increments when the user edits a
 * transaction that a rule produced").
 */
suspend fun writeTransactionEditAudit(
    transactionAuditDao: TransactionAuditDao,
    transaction: Transaction,
    correction: ReviewCorrection,
    now: Long,
): Boolean {
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
    return changes.isNotEmpty()
}

/**
 * docs/parser.md's rule health: a correction against whichever rule produced [transaction], found
 * via the archive it came from — `RawSms.matchedRuleId`, not `Transaction` itself, since that's
 * where a match is actually recorded (Tier 2 suggestions never set it).
 */
@Suppress("ReturnCount") // guard-clause style is clearer than nesting for this lookup chain
suspend fun maybeIncrementRuleCorrection(
    rawSmsDao: RawSmsDao,
    parserRuleDao: ParserRuleDao,
    transaction: Transaction,
    now: Long,
) {
    val rawSms = transaction.rawSmsId?.let { rawSmsDao.getById(it) } ?: return
    val ruleId = rawSms.matchedRuleId ?: return
    val rule = parserRuleDao.getById(ruleId) ?: return
    parserRuleDao.update(rule.copy(correctionCount = rule.correctionCount + 1, updatedAt = now))
}
