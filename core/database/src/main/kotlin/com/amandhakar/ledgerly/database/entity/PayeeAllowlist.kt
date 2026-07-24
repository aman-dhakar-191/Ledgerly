package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Task 1.12: payee names that identify the user's own accounts (docs/schema.md). Self-transfers
 * appear as ordinary UPI payments with the user's own name as merchant — frequent and large enough
 * that counting them as expenses inflates spending severely.
 *
 * Entries are added only by explicit user confirmation from the review inbox — never inferred from
 * a resembling surname (docs/corpus-findings.md §9: `KIRAN DHAKER`/`RAHUL DHAKAR` are genuine
 * outgoing transfers to family, not internal movements, despite resembling `AMAN DHAKAR`).
 */
@Entity(tableName = "payee_allowlist", indices = [Index(value = ["normalized_name"], unique = true)])
data class PayeeAllowlist(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    /** The user's own account this payee maps to, if known. */
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "confirmed_at") val confirmedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
