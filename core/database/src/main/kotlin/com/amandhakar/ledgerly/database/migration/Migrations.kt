package com.amandhakar.ledgerly.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Worked example for Task 0.7: adds one nullable column to `transaction_entity`. Real schema
 * changes (Phase 3's `merchant_normalized`/`category_id`, etc.) get their own migrations later —
 * `fallbackToDestructiveMigration` is forbidden (tasks/phase-0.md), so every version bump ships
 * with one of these.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transaction_entity ADD COLUMN schema_demo_note TEXT DEFAULT NULL")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
