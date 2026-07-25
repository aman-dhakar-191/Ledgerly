package com.amandhakar.ledgerly.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amandhakar.ledgerly.database.entity.CardStatement
import kotlinx.coroutines.flow.Flow

@Dao
interface CardStatementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(cardStatement: CardStatement): Long

    @Query("SELECT * FROM card_statement WHERE raw_sms_id = :rawSmsId AND deleted_at IS NULL")
    suspend fun getByRawSmsId(rawSmsId: String): CardStatement?

    /** The most recent statement strictly before [statementDate] - the reconciliation window's start. */
    @Query(
        "SELECT * FROM card_statement WHERE account_id = :accountId AND statement_date < :statementDate " +
            "AND deleted_at IS NULL ORDER BY statement_date DESC LIMIT 1",
    )
    suspend fun getLatestBefore(accountId: String, statementDate: Long): CardStatement?

    @Query("SELECT * FROM card_statement WHERE account_id = :accountId AND deleted_at IS NULL ORDER BY statement_date DESC")
    fun observeForAccount(accountId: String): Flow<List<CardStatement>>
}
