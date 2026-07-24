package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.amandhakar.ledgerly.model.money.Paise

@Entity(tableName = "account")
data class Account(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val last4: String?,
    /** ISO 4217, default INR. */
    val currency: String,
    @ColumnInfo(name = "current_balance") val currentBalance: Paise,
    @ColumnInfo(name = "balance_as_of") val balanceAsOf: Long,
    @ColumnInfo(name = "credit_limit") val creditLimit: Paise?,
    @ColumnInfo(name = "statement_day") val statementDay: Int?,
    @ColumnInfo(name = "due_day") val dueDay: Int?,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
