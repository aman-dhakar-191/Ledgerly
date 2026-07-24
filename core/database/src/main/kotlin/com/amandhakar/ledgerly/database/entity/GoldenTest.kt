package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per user confirmation; the full set runs in CI on every parser change (docs/parser.md). */
@Entity(tableName = "golden_test")
data class GoldenTest(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "raw_body") val rawBody: String,
    @ColumnInfo(name = "expected_json") val expectedJson: String,
    @ColumnInfo(name = "rule_id") val ruleId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
