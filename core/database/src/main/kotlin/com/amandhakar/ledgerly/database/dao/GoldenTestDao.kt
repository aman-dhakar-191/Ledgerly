package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.GoldenTest
import kotlinx.coroutines.flow.Flow

@Dao
interface GoldenTestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(goldenTest: GoldenTest): Long

    /** Import (Task 1.14): re-importing the same exported file must not duplicate rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflicts(goldenTest: GoldenTest): Long

    @Query("SELECT * FROM golden_test WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<GoldenTest>>

    @Query("SELECT * FROM golden_test WHERE rule_id = :ruleId AND deleted_at IS NULL")
    suspend fun getByRuleId(ruleId: String): List<GoldenTest>

    @Query("UPDATE golden_test SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
