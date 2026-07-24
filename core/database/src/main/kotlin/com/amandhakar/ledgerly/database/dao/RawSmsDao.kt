package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import kotlinx.coroutines.flow.Flow

@Dao
interface RawSmsDao {
    /** Aborts on a duplicate `dedupe_hash` — idempotent ingest (CONTEXT.md invariant #7). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rawSms: RawSms): Long

    @Query("SELECT * FROM raw_sms WHERE deleted_at IS NULL ORDER BY received_at DESC")
    fun observeAll(): Flow<List<RawSms>>

    @Query("SELECT * FROM raw_sms WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): RawSms?

    @Query("SELECT * FROM raw_sms WHERE dedupe_hash = :dedupeHash AND deleted_at IS NULL")
    suspend fun getByDedupeHash(dedupeHash: String): RawSms?

    @Query("SELECT * FROM raw_sms WHERE parse_status = :status AND deleted_at IS NULL")
    fun observeByStatus(status: ParseStatus): Flow<List<RawSms>>

    @Query("UPDATE raw_sms SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
