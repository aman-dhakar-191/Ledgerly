package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Rules are data, learned at runtime — no bank formats are hardcoded (docs/parser.md). */
@Entity(tableName = "parser_rule", indices = [Index(value = ["sender_id"])])
data class ParserRule(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    val pattern: String,
    @ColumnInfo(name = "field_map") val fieldMap: String,
    @ColumnInfo(name = "txn_type") val txnType: ParserTxnType,
    val priority: Int,
    val confidence: Float,
    val active: Boolean,
    @ColumnInfo(name = "created_from_sms_id") val createdFromSmsId: String,
    @ColumnInfo(name = "match_count") val matchCount: Int,
    @ColumnInfo(name = "correction_count") val correctionCount: Int,
    val version: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
