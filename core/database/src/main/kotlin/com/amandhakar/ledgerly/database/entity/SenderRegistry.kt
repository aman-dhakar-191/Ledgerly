package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Only trusted senders are parsed at all (docs/parser.md); untrusted ones stay IGNORED.
 *
 * [senderId] is the raw telecom-route ID (`AD-ICICIT-S`), kept for provenance. [institution] is
 * [com.amandhakar.ledgerly.parser.normalizeSender] applied to it (`ICICIT`) — rules and trust key
 * on this, never on [senderId], since the corpus has 13+ raw senders for one ICICI institution
 * (docs/corpus-findings.md §1).
 */
@Entity(tableName = "sender_registry", indices = [Index(value = ["institution"])])
data class SenderRegistry(
    @PrimaryKey @ColumnInfo(name = "sender_id") val senderId: String,
    val institution: String,
    val label: String,
    val type: SenderType,
    val trusted: Boolean,
    @ColumnInfo(name = "account_id") val accountId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
