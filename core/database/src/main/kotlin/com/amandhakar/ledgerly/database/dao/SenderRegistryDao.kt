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

    /**
     * Task 2.5/2.6: linking a WALLET/BNPL account to every raw sender ID already seen for its
     * institution. Case-insensitive: [com.amandhakar.ledgerly.parser.normalizeSender] preserves the
     * raw sender ID's own casing rather than normalising it (`axioFS` mixed-case vs `JUSPAY`
     * all-caps), so an exact-case match would silently miss senders whose casing the user didn't
     * happen to guess when typing the institution into the add-account form.
     */
    @Query("SELECT * FROM sender_registry WHERE institution = :institution COLLATE NOCASE AND deleted_at IS NULL")
    suspend fun getByInstitution(institution: String): List<SenderRegistry>

    @Query("UPDATE sender_registry SET deleted_at = :deletedAt WHERE sender_id = :senderId")
    suspend fun softDelete(senderId: String, deletedAt: Long)
}
