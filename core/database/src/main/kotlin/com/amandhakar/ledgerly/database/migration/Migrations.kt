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

        // Not ALTER TABLE ... RENAME COLUMN: that needs SQLite 3.25+ (Android 9/API 28), and this
        // app's minSdk is 26 — recreate the table instead, which every SQLite version supports.
        db.execSQL(
            """
            CREATE TABLE parser_rule_new (
                id TEXT NOT NULL PRIMARY KEY,
                institution TEXT NOT NULL,
                pattern TEXT NOT NULL,
                field_map TEXT NOT NULL,
                txn_type TEXT NOT NULL,
                priority INTEGER NOT NULL,
                confidence REAL NOT NULL,
                active INTEGER NOT NULL,
                created_from_sms_id TEXT NOT NULL,
                match_count INTEGER NOT NULL,
                correction_count INTEGER NOT NULL,
                version INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO parser_rule_new (
                id, institution, pattern, field_map, txn_type, priority, confidence, active,
                created_from_sms_id, match_count, correction_count, version,
                created_at, updated_at, deleted_at
            )
            SELECT
                id, sender_id, pattern, field_map, txn_type, priority, confidence, active,
                created_from_sms_id, match_count, correction_count, version,
                created_at, updated_at, deleted_at
            FROM parser_rule
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE parser_rule")
        db.execSQL("ALTER TABLE parser_rule_new RENAME TO parser_rule")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_parser_rule_institution ON parser_rule(institution)")
    }
}

/**
 * Task 1.13/docs/schema.md: `raw_sms` gains `institution` and `parse_class`, both computed and
 * written by the parsing pipeline (not at archive time — [com.amandhakar.ledgerly.ingest.RawSmsArchiver]
 * still archives verbatim with no parsing). Pre-existing rows backfill to the column defaults
 * (`''` / `UNKNOWN`) pending their next pipeline run, which sets them for real.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE raw_sms ADD COLUMN institution TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE raw_sms ADD COLUMN parse_class TEXT NOT NULL DEFAULT 'UNKNOWN'")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
