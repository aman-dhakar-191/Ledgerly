package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Task 2.4/docs/corpus-findings.md §6: a statement message is never a transaction - it sets the
 * card's due amounts and date, and is the input to the reconciliation check that compares
 * `sum(card transactions since last statement)` against [totalDue].
 */
@Entity(
    tableName = "card_statement",
    foreignKeys = [
        ForeignKey(entity = Account::class, parentColumns = ["id"], childColumns = ["account_id"]),
    ],
    indices = [Index(value = ["account_id", "statement_date"])],
)
data class CardStatement(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "total_due") val totalDue: Long,
    @ColumnInfo(name = "minimum_due") val minimumDue: Long,
    @ColumnInfo(name = "due_date") val dueDate: Long,
    /** When the statement message itself arrived/occurred - the reconciliation window's edge. */
    @ColumnInfo(name = "statement_date") val statementDate: Long,
    @ColumnInfo(name = "raw_sms_id") val rawSmsId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
