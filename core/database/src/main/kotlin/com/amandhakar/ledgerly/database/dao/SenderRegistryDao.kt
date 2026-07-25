package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import kotlinx.coroutines.flow.Flow

@Dao
interface SenderRegistryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sender: SenderRegistry)

    @Update
    suspend fun update(sender: SenderRegistry)

    @Query("SELECT * FROM sender_registry WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<SenderRegistry>>

    @Query("SELECT * FROM sender_registry WHERE sender_id = :senderId AND deleted_at IS NULL")
    suspend fun getById(senderId: String): SenderRegistry?

    /** Task 2.5: linking a WALLET account to every raw sender ID already seen for its institution. */
    @Query("SELECT * FROM sender_registry WHERE institution = :institution AND deleted_at IS NULL")
    suspend fun getByInstitution(institution: String): List<SenderRegistry>

    @Query("UPDATE sender_registry SET deleted_at = :deletedAt WHERE sender_id = :senderId")
    suspend fun softDelete(senderId: String, deletedAt: Long)
}
