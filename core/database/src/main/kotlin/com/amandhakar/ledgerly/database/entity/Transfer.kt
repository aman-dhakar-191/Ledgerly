package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * docs/schema.md's Transfer entity (Phase 2). One movement of the user's own money between two
 * of their own accounts, expressed as a link between the two [Transaction] rows it produced —
 * [toTxnId] is null when only one leg ever produced an SMS (docs/parser.md's "one-sided transfers
 * must still be marked": the visible leg gets `is_internal = true` without a Transfer row at all
 * in that case, so a null [toTxnId] here only ever means "the counterpart was seen but is a
 * different transaction we chose not to auto-link" - Task 2.2's card-payment matching is the one
 * case that currently produces that).
 */
@Entity(
    tableName = "transfer",
    foreignKeys = [
        ForeignKey(entity = Transaction::class, parentColumns = ["id"], childColumns = ["from_txn_id"]),
        ForeignKey(entity = Transaction::class, parentColumns = ["id"], childColumns = ["to_txn_id"]),
    ],
    indices = [Index(value = ["from_txn_id"]), Index(value = ["to_txn_id"])],
)
data class Transfer(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_txn_id") val fromTxnId: String,
    @ColumnInfo(name = "to_txn_id") val toTxnId: String?,
    val kind: TransferKind,
    @ColumnInfo(name = "detected_by") val detectedBy: DetectedBy,
    val confidence: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
