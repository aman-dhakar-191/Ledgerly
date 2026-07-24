package com.amandhakar.ledgerly.database.di

import android.content.Context
import androidx.room.Room
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.GoldenTestDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.migration.ALL_MIGRATIONS
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    @Suppress("SpreadOperator") // ALL_MIGRATIONS is a handful of entries; called once at app startup
    fun provideDatabase(@ApplicationContext context: Context): LedgerlyDatabase =
        Room.databaseBuilder(context, LedgerlyDatabase::class.java, LedgerlyDatabase.DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun provideRawSmsDao(db: LedgerlyDatabase): RawSmsDao = db.rawSmsDao()
    @Provides fun provideSenderRegistryDao(db: LedgerlyDatabase): SenderRegistryDao = db.senderRegistryDao()
    @Provides fun provideParserRuleDao(db: LedgerlyDatabase): ParserRuleDao = db.parserRuleDao()
    @Provides fun provideGoldenTestDao(db: LedgerlyDatabase): GoldenTestDao = db.goldenTestDao()
    @Provides fun provideAccountDao(db: LedgerlyDatabase): AccountDao = db.accountDao()
    @Provides fun provideTransactionDao(db: LedgerlyDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideTransactionAuditDao(db: LedgerlyDatabase): TransactionAuditDao = db.transactionAuditDao()
    @Provides fun provideBalanceAnchorDao(db: LedgerlyDatabase): BalanceAnchorDao = db.balanceAnchorDao()
}
