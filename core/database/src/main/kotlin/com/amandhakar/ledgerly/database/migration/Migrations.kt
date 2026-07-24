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

/**
 * Task 1.4's schema correction: rules and sender trust key on the normalised institution
 * (`ICICIT`), not the raw telecom-route sender ID (`AD-ICICIT-S`) — docs/corpus-findings.md §1
 * found 13+ raw senders carrying one institution's format. `sender_registry` gains a new
 * `institution` column; `parser_rule.sender_id` is renamed to `institution` outright, since no
 * rule has ever been allowed to key on the raw ID.
 *
 * Pre-existing rows backfill `institution = sender_id` — the closest available value pending a
 * real re-normalisation pass on next app launch. Harmless here: Phase 1 hasn't shipped, so no
 * installed copy of this database contains real sender data yet.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sender_registry ADD COLUMN institution TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE sender_registry SET institution = sender_id")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sender_registry_institution ON sender_registry(institution)")

        db.execSQL("ALTER TABLE parser_rule RENAME COLUMN sender_id TO institution")
        db.execSQL("DROP INDEX IF EXISTS index_parser_rule_sender_id")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_parser_rule_institution ON parser_rule(institution)")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
