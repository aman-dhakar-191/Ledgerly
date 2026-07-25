package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.Transfer
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transfer: Transfer): Long

    @Query("SELECT * FROM transfer WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): Transfer?

    @Query("SELECT * FROM transfer WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<Transfer>>

    @Query("UPDATE transfer SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
