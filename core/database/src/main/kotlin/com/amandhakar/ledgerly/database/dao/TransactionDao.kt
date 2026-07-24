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

    /**
     * Task 1.11's reconciliation baseline: the signed sum of every `CONFIRMED` transaction for
     * this account strictly after the anchor and at or before the transaction under test. Signed
     * so the caller can add it straight to the anchor's balance — `DEBIT` already comes back
     * negative, `CREDIT` positive.
     */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN -amount ELSE amount END), 0) " +
            "FROM transaction_entity WHERE account_id = :accountId AND status = 'CONFIRMED' " +
            "AND occurred_at > :anchorAsOf AND occurred_at <= :occurredAt AND deleted_at IS NULL",
    )
    suspend fun getSignedSumSinceAnchor(accountId: String, anchorAsOf: Long, occurredAt: Long): Long
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
