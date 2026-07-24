package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.PayeeAllowlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PayeeAllowlistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(payee: PayeeAllowlist): Long

    @Query("SELECT * FROM payee_allowlist WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<PayeeAllowlist>>

    /** Matching is exact against the already-normalised name (docs/schema.md): uppercase, whitespace collapsed. */
    @Query("SELECT * FROM payee_allowlist WHERE normalized_name = :normalizedName AND deleted_at IS NULL")
    suspend fun getByNormalizedName(normalizedName: String): PayeeAllowlist?

    @Query("UPDATE payee_allowlist SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
