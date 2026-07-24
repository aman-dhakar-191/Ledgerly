package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceAnchorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(anchor: BalanceAnchor): Long

    @Query("SELECT * FROM balance_anchor WHERE account_id = :accountId AND deleted_at IS NULL ORDER BY as_of DESC")
    fun observeForAccount(accountId: String): Flow<List<BalanceAnchor>>

    /** The reconciliation baseline: the most recent anchor at or before [occurredAt] (docs/schema.md). */
    @Query(
        "SELECT * FROM balance_anchor WHERE account_id = :accountId AND as_of <= :occurredAt " +
            "AND deleted_at IS NULL ORDER BY as_of DESC LIMIT 1",
    )
    suspend fun getLatestAtOrBefore(accountId: String, occurredAt: Long): BalanceAnchor?

    @Query("UPDATE balance_anchor SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
