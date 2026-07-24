package com.amandhakar.ledgerly.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.GoldenTestDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.GoldenTest
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionAudit

/**
 * Room database name is fixed as `ledgerly.db` (CONTEXT.md) and must not change once encrypted
 * data exists. `fallbackToDestructiveMigration` is forbidden (tasks/phase-0.md) — every schema
 * change ships with a real, tested [androidx.room.migration.Migration].
 */
@Database(
    entities = [
        RawSms::class,
        SenderRegistry::class,
        ParserRule::class,
        GoldenTest::class,
        Account::class,
        Transaction::class,
        TransactionAudit::class,
        BalanceAnchor::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LedgerlyDatabase : RoomDatabase() {
    abstract fun rawSmsDao(): RawSmsDao
    abstract fun senderRegistryDao(): SenderRegistryDao
    abstract fun parserRuleDao(): ParserRuleDao
    abstract fun goldenTestDao(): GoldenTestDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionAuditDao(): TransactionAuditDao
    abstract fun balanceAnchorDao(): BalanceAnchorDao

    companion object {
        const val DATABASE_NAME = "ledgerly.db"
    }
}
