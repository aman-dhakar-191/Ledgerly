package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.PayeeAllowlistDao
import com.amandhakar.ledgerly.database.entity.PayeeAllowlist
import com.amandhakar.ledgerly.parser.normalizePayeeName
import java.util.UUID

/**
 * Task 1.12: "On allowlist match -> Transaction.is_internal = true, excluded from income/expense
 * totals." Matching an *already-confirmed* entry is safe to do automatically at write time — it's
 * adding a *new* entry that requires explicit user confirmation (docs/parser.md), never done here.
 */
suspend fun isAllowlistedPayee(payeeAllowlistDao: PayeeAllowlistDao, merchant: String?): Boolean {
    if (merchant == null) return false
    return payeeAllowlistDao.getByNormalizedName(normalizePayeeName(merchant)) != null
}

/** Idempotent: confirming the same payee twice does not create a duplicate row. */
suspend fun confirmAllowlistedPayee(payeeAllowlistDao: PayeeAllowlistDao, merchant: String, now: Long) {
    val normalized = normalizePayeeName(merchant)
    if (payeeAllowlistDao.getByNormalizedName(normalized) != null) return
    payeeAllowlistDao.insert(
        PayeeAllowlist(
            id = UUID.randomUUID().toString(),
            normalizedName = normalized,
            accountId = null,
            confirmedAt = now,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ),
    )
}
