package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Query("SELECT * FROM transaction_entity WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): Transaction?

    @Query(
        "SELECT * FROM transaction_entity WHERE account_id = :accountId " +
            "AND occurred_at BETWEEN :from AND :to AND deleted_at IS NULL ORDER BY occurred_at DESC",
    )
    fun observeByAccountAndDateRange(accountId: String, from: Long, to: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transaction_entity WHERE status = :status AND deleted_at IS NULL")
    fun observeByStatus(status: TransactionStatus): Flow<List<Transaction>>

    @Query("SELECT * FROM transaction_entity WHERE raw_sms_id = :rawSmsId AND deleted_at IS NULL")
    suspend fun getByRawSmsId(rawSmsId: String): Transaction?

    @Query("SELECT * FROM transaction_entity WHERE transfer_id = :transferId AND deleted_at IS NULL")
    suspend fun getByTransferId(transferId: String): List<Transaction>

    @Query("UPDATE transaction_entity SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
