package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Every user edit to a transaction writes one of these (CONTEXT.md invariant #5). */
@Entity(tableName = "transaction_audit", indices = [Index(value = ["transaction_id"])])
data class TransactionAudit(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    val field: String,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    val reason: AuditReason,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
