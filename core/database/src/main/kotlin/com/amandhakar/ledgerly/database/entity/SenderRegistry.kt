package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Only trusted senders are parsed at all (docs/parser.md); untrusted ones stay IGNORED. */
@Entity(tableName = "sender_registry")
data class SenderRegistry(
    @PrimaryKey @ColumnInfo(name = "sender_id") val senderId: String,
    val label: String,
    val type: SenderType,
    val trusted: Boolean,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
