package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-asserted true balance for an account at a point in time (docs/schema.md). Opening
 * balances and later drift corrections are the same entity; reconciliation runs forward from the
 * most recent anchor at or before a transaction, not from account creation, so drift resets
 * instead of compounding.
 *
 * `balance` is `Long` paise directly, not the `Paise` value class — see Account.kt's class doc
 * for why (a cross-module Room/KSP value-class crash, not a design preference).
 */
@Entity(
    tableName = "balance_anchor",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
        ),
    ],
    indices = [Index(value = ["account_id", "as_of"])],
)
data class BalanceAnchor(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    /** Paise; asserted truth, not derived. */
    val balance: Long,
    @ColumnInfo(name = "as_of") val asOf: Long,
    val source: BalanceAnchorSource,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
