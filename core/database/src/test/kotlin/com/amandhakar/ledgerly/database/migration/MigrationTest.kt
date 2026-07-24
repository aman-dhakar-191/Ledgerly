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
 * `schemas/.../2.json` is the real KSP-exported schema for the current entities. `schemas/.../1.json`
 * is derived from it (schema_demo_note column removed, version set to 1) rather than independently
 * generated, since this project went straight from version 1 to version 2 in code without ever
 * building version 1 on its own — its `identityHash` is therefore a placeholder, not a real Room
 * hash. That's fine here: MigrationTestHelper.createDatabase() builds the historical database from
 * `createSql`, it doesn't validate identityHash against anything for this synthetic-migration flow.
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
}
