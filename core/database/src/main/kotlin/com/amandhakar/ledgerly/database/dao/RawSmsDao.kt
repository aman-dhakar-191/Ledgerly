package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import kotlinx.coroutines.flow.Flow

@Dao
interface RawSmsDao {
    /** Aborts on a duplicate `dedupe_hash` — idempotent ingest (CONTEXT.md invariant #7). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rawSms: RawSms): Long

    /** The parsing pipeline updating `institution`/`parse_status`/`parse_class`/`matched_rule_id` post-archive. */
    @Update
    suspend fun update(rawSms: RawSms)

    @Query("SELECT * FROM raw_sms WHERE deleted_at IS NULL ORDER BY received_at DESC")
    fun observeAll(): Flow<List<RawSms>>

    @Query("SELECT * FROM raw_sms WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): RawSms?

    @Query("SELECT * FROM raw_sms WHERE dedupe_hash = :dedupeHash AND deleted_at IS NULL")
    suspend fun getByDedupeHash(dedupeHash: String): RawSms?

    /** A sample body for the sender-classification screen (Task 1.4) - the sender ID alone doesn't tell the user what the sender is. */
    @Query("SELECT * FROM raw_sms WHERE sender IN (:senders) AND deleted_at IS NULL ORDER BY received_at DESC LIMIT 1")
    suspend fun getMostRecentBySenders(senders: List<String>): RawSms?

    @Query("SELECT * FROM raw_sms WHERE parse_status = :status AND deleted_at IS NULL")
    fun observeByStatus(status: ParseStatus): Flow<List<RawSms>>

    /** A one-shot snapshot for the parsing pipeline to iterate — a `Flow` would re-fire mid-batch as it writes. */
    @Query("SELECT * FROM raw_sms WHERE parse_status = :status AND deleted_at IS NULL ORDER BY received_at ASC")
    suspend fun getByStatus(status: ParseStatus): List<RawSms>

    @Query("UPDATE raw_sms SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
