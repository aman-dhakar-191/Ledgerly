package com.amandhakar.ledgerly.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.CardStatementDao
import com.amandhakar.ledgerly.database.dao.GoldenTestDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.PayeeAllowlistDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.dao.TransferDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.CardStatement
import com.amandhakar.ledgerly.database.entity.GoldenTest
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.PayeeAllowlist
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import com.amandhakar.ledgerly.database.entity.Transfer

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
        PayeeAllowlist::class,
        Transfer::class,
        CardStatement::class,
    ],
    version = 7,
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
    abstract fun payeeAllowlistDao(): PayeeAllowlistDao
    abstract fun transferDao(): TransferDao
    abstract fun cardStatementDao(): CardStatementDao

    companion object {
        const val DATABASE_NAME = "ledgerly.db"

        /**
         * Kept in sync with `version` above by hand rather than read via reflection — the update
         * screen's schema-migration guard (tasks/update-system.md) needs the *running* app's
         * schema version as a plain constant, not the `@Database` annotation on the class it's
         * currently running.
         */
        const val SCHEMA_VERSION = 7
    }
}
