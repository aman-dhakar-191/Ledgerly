package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Money fields are `Long` paise directly (never Double/Float), not the `Paise` value class —
 * Room's KSP processor cannot reliably inspect a value class declared in a different Gradle
 * module (`:core:model`) than the one processing it (`:core:database`); it crashes with
 * `getValueClassUnderlyingProperty: List has more than one element` regardless of the value
 * class's own shape. Wrap/unwrap with `Paise(...)`/`.value` at the call site.
 */
@Entity(tableName = "account")
data class Account(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val last4: String?,
    /** ISO 4217, default INR. */
    val currency: String,
    @ColumnInfo(name = "current_balance") val currentBalance: Long,
    @ColumnInfo(name = "balance_as_of") val balanceAsOf: Long,
    @ColumnInfo(name = "credit_limit") val creditLimit: Long?,
    @ColumnInfo(name = "statement_day") val statementDay: Int?,
    @ColumnInfo(name = "due_day") val dueDay: Int?,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
