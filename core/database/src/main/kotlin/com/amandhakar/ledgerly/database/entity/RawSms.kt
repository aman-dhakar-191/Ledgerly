package com.amandhakar.ledgerly.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable and permanent, per CONTEXT.md invariant #2: stored verbatim, never edited or
 * deleted. Parser fixes are backfilled by re-running over this archive, which is why the unique
 * dedupe index matters more here than idempotency alone would justify — it's the validation
 * corpus for every rule ever generated (docs/parser.md).
 */
@Entity(tableName = "raw_sms", indices = [Index(value = ["dedupe_hash"], unique = true)])
data class RawSms(
    @PrimaryKey val id: String,
    val sender: String,
    val body: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int?,
    @ColumnInfo(name = "dedupe_hash") val dedupeHash: String,
    /** [com.amandhakar.ledgerly.parser.normalizeSender] applied to [sender] — added in Migration(3,4). */
    @ColumnInfo(name = "institution", defaultValue = "''") val institution: String = "",
    @ColumnInfo(name = "parse_status") val parseStatus: ParseStatus,
    /** Set by the hardcoded pre-filter (docs/parser.md) — added in Migration(3,4). */
    @ColumnInfo(name = "parse_class", defaultValue = "'UNKNOWN'") val parseClass: ParseClass = ParseClass.UNKNOWN,
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)
