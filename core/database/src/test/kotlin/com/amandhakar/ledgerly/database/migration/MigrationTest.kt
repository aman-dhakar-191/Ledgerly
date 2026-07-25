package com.amandhakar.ledgerly.database.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 0.7: v1 -> v2 preserves all rows and values. [MIGRATION_1_2] adds one nullable column to
 * `transaction_entity`; this constructs a real v1 database by hand (as MigrationTestHelper
 * expects, from the exported schema in core/database/schemas/), runs the migration, and checks
 * the pre-existing row survived untouched with the new column defaulting to NULL.
 *
 * `schemas/.../2.json` and `.../3.json` are real KSP-exported schemas. `schemas/.../1.json` is
 * derived from `2.json` by hand (schema_demo_note column removed, version set to 1) rather than
 * independently generated, since this project went straight from version 1 to version 2 in code
 * without ever building version 1 on its own — its `identityHash` is therefore a placeholder, not
 * a real Room hash. That's fine here: MigrationTestHelper.createDatabase() builds the historical
 * database from `createSql`, it doesn't validate identityHash against anything for this
 * synthetic-migration flow.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val testDbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LedgerlyDatabase::class.java,
    )

    @Test
    fun `migrate 1 to 2 preserves all rows and values, new column defaults to null`() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                """
                INSERT INTO account (
                    id, name, type, last4, currency, current_balance, balance_as_of,
                    credit_limit, statement_day, due_day, archived, created_at, updated_at, deleted_at
                ) VALUES (
                    'acct-1', 'Test Savings', 'SAVINGS', '1234', 'INR', 100000, 1700000000000,
                    NULL, NULL, NULL, 0, 1700000000000, 1700000000000, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transaction_entity (
                    id, account_id, amount, direction, occurred_at, merchant_raw, balance_after,
                    raw_sms_id, source, status, transfer_id, is_internal, notes,
                    created_at, updated_at, deleted_at
                ) VALUES (
                    'txn-1', 'acct-1', 50000, 'DEBIT', 1700000000000, 'Swiggy', 50000,
                    NULL, 'MANUAL', 'CONFIRMED', NULL, 0, 'lunch',
                    1700000000000, 1700000000000, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT * FROM transaction_entity WHERE id = 'txn-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("account_id"))).isEqualTo("acct-1")
            assertThat(cursor.getLong(cursor.getColumnIndexOrThrow("amount"))).isEqualTo(50_000L)
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("direction"))).isEqualTo("DEBIT")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("merchant_raw"))).isEqualTo("Swiggy")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("notes"))).isEqualTo("lunch")
            assertThat(cursor.isNull(cursor.getColumnIndexOrThrow("schema_demo_note"))).isTrue()
        }
    }

    /**
     * Task 1.4: `sender_registry` gains `institution`, backfilled from `sender_id` on existing
     * rows; `parser_rule.sender_id` is renamed to `institution` outright. Both migrations from
     * one starting v2 database, since [MIGRATION_2_3] touches both tables in one step.
     */
    @Test
    fun `migrate 2 to 3 adds institution to sender_registry and renames it on parser_rule`() {
        helper.createDatabase(testDbName, 2).apply {
            execSQL(
                """
                INSERT INTO sender_registry (
                    sender_id, label, type, trusted, account_id, created_at, updated_at, deleted_at
                ) VALUES (
                    'AD-ICICIT-S', 'ICICI Bank', 'BANK', 1, NULL, 1700000000000, 1700000000000, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO parser_rule (
                    id, sender_id, pattern, field_map, txn_type, priority, confidence, active,
                    created_from_sms_id, match_count, correction_count, version,
                    created_at, updated_at, deleted_at
                ) VALUES (
                    'rule-1', 'AD-ICICIT-S', 'Rs\.(\d+) debited', '{"1":"amount"}', 'DEBIT', 10, 0.9, 1,
                    'sms-1', 0, 0, 1, 1700000000000, 1700000000000, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 3, true, MIGRATION_2_3)

        migrated.query("SELECT * FROM sender_registry WHERE sender_id = 'AD-ICICIT-S'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("institution"))).isEqualTo("AD-ICICIT-S")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("label"))).isEqualTo("ICICI Bank")
        }
        migrated.query("SELECT * FROM parser_rule WHERE id = 'rule-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("institution"))).isEqualTo("AD-ICICIT-S")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("pattern"))).isEqualTo("Rs\\.(\\d+) debited")
        }
    }

    /**
     * Task 1.13's pipeline needs `raw_sms.institution`/`parse_class` to persist what the pre-filter
     * and [com.amandhakar.ledgerly.parser.normalizeSender] already computed, rather than recomputing
     * on every read. Pre-existing rows backfill to the column defaults, same as any other
     * not-yet-reprocessed archive row.
     */
    @Test
    fun `migrate 3 to 4 adds institution and parse_class to raw_sms, defaulting existing rows`() {
        helper.createDatabase(testDbName, 3).apply {
            execSQL(
                """
                INSERT INTO raw_sms (
                    id, sender, body, received_at, subscription_id, dedupe_hash,
                    parse_status, matched_rule_id, created_at, updated_at, deleted_at
                ) VALUES (
                    'sms-1', 'AD-ICICIT-S', 'Rs.500 debited', 1700000000000, 1, 'hash-1',
                    'UNPROCESSED', NULL, 1700000000000, 1700000000000, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, MIGRATION_3_4)

        migrated.query("SELECT * FROM raw_sms WHERE id = 'sms-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("sender"))).isEqualTo("AD-ICICIT-S")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("institution"))).isEqualTo("")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("parse_class"))).isEqualTo("UNKNOWN")
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("parse_status"))).isEqualTo("UNPROCESSED")
        }
    }

    /** Task 1.12/docs/schema.md: a fresh table, not an alteration - just needs to exist and accept a row. */
    @Test
    fun `migrate 4 to 5 creates the payee_allowlist table`() {
        helper.createDatabase(testDbName, 4).apply { close() }

        val migrated = helper.runMigrationsAndValidate(testDbName, 5, true, MIGRATION_4_5)

        migrated.execSQL(
            """
            INSERT INTO payee_allowlist (id, normalized_name, account_id, confirmed_at, created_at, updated_at, deleted_at)
            VALUES ('payee-1', 'AMAN DHAKAR', NULL, 1700000000000, 1700000000000, 1700000000000, NULL)
            """.trimIndent(),
        )
        migrated.query("SELECT * FROM payee_allowlist WHERE id = 'payee-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("normalized_name"))).isEqualTo("AMAN DHAKAR")
        }
    }

    /** Task 2.1/docs/schema.md: a fresh table, not an alteration - just needs to exist and accept a row. */
    @Test
    fun `migrate 5 to 6 creates the transfer table`() {
        helper.createDatabase(testDbName, 5).apply { close() }

        val migrated = helper.runMigrationsAndValidate(testDbName, 6, true, MIGRATION_5_6)

        migrated.execSQL(
            """
            INSERT INTO transfer (id, from_txn_id, to_txn_id, kind, detected_by, confidence, created_at, updated_at, deleted_at)
            VALUES ('transfer-1', 'txn-1', 'txn-2', 'ACCOUNT_TO_ACCOUNT', 'MANUAL', 1.0, 1700000000000, 1700000000000, NULL)
            """.trimIndent(),
        )
        migrated.query("SELECT * FROM transfer WHERE id = 'transfer-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(cursor.getColumnIndexOrThrow("kind"))).isEqualTo("ACCOUNT_TO_ACCOUNT")
        }
    }
}
