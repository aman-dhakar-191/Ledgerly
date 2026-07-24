package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amandhakar.ledgerly.database.entity.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Query("SELECT * FROM account WHERE deleted_at IS NULL AND archived = 0")
    fun observeActive(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): Account?

    @Query("UPDATE account SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
