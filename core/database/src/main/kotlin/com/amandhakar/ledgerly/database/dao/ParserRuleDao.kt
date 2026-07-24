package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.amandhakar.ledgerly.database.entity.ParserRule
import kotlinx.coroutines.flow.Flow

@Dao
interface ParserRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: ParserRule): Long

    @Update
    suspend fun update(rule: ParserRule)

    @Query("SELECT * FROM parser_rule WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<ParserRule>>

    @Query("SELECT * FROM parser_rule WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): ParserRule?

    @Query(
        "SELECT * FROM parser_rule WHERE institution = :institution AND active = 1 AND deleted_at IS NULL " +
            "ORDER BY priority DESC, LENGTH(pattern) DESC",
    )
    suspend fun getActiveForInstitution(institution: String): List<ParserRule>

    @Query("UPDATE parser_rule SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)
}
