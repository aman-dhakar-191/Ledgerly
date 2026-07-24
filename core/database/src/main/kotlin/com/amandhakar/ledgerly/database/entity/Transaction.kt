package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `merchant_normalized` and `category_id` (docs/schema.md, tagged Phase 3) are deliberately not
 * columns yet, per "do not build ahead" (docs/phases.md). `schemaDemoNote` exists purely as
 * Task 0.7's worked Migration(1,2) example (a placeholder, not a product field) and should be
 * replaced by the real Phase 3 migration when those columns actually land.
 *
 * Money fields are `Long` paise directly, not the `Paise` value class — see Account.kt's class
 * doc for why (a cross-module Room/KSP value-class crash, not a design preference).
 */
@Entity(
    tableName = "transaction_entity",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
        ),
    ],
    indices = [
        Index(value = ["account_id", "occurred_at"]),
        Index(value = ["status"]),
        Index(value = ["transfer_id"]),
        Index(value = ["raw_sms_id"]),
    ],
)
data class Transaction(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    /** Always positive; direction carries the sign. */
    val amount: Long,
    val direction: Direction,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "merchant_raw") val merchantRaw: String?,
    @ColumnInfo(name = "balance_after") val balanceAfter: Long?,
    @ColumnInfo(name = "raw_sms_id") val rawSmsId: String?,
    val source: TransactionSource,
    val status: TransactionStatus,
    @ColumnInfo(name = "transfer_id") val transferId: String?,
    @ColumnInfo(name = "is_internal") val isInternal: Boolean,
    val notes: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    /** Added in Migration(1,2) — see the class doc above. */
    @ColumnInfo(name = "schema_demo_note", defaultValue = "NULL") val schemaDemoNote: String? = null,
)
