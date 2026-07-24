package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(audit: TransactionAudit): Long

    @Query("SELECT * FROM transaction_audit WHERE transaction_id = :transactionId AND deleted_at IS NULL ORDER BY changed_at DESC")
    fun observeForTransaction(transactionId: String): Flow<List<TransactionAudit>>

    @Query("UPDATE transaction_audit SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
