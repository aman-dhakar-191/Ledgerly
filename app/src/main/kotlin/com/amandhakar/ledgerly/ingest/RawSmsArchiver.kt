package com.amandhakar.ledgerly.ingest

import android.database.sqlite.SQLiteConstraintException
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.parser.computeDedupeHash
import java.util.UUID
import javax.inject.Inject

/**
 * The actual "store this message" logic behind [SmsReceiver], kept separate from it so it's
 * testable against a real (in-memory) Room database rather than needing a Robolectric-shadowed
 * `SmsMessage`/broadcast `Intent` just to exercise the dedupe path.
 */
class RawSmsArchiver @Inject constructor(
    private val rawSmsDao: RawSmsDao,
    private val lastProcessedStore: LastProcessedStore,
) {

    /** Idempotent: a duplicate `dedupe_hash` (same sender/timestamp/body) inserts once. */
    suspend fun archive(sender: String, receivedAt: Long, subscriptionId: Int?, body: String) {
        val now = System.currentTimeMillis()
        val rawSms = RawSms(
            id = UUID.randomUUID().toString(),
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
            dedupeHash = computeDedupeHash(sender, receivedAt, body),
            parseStatus = ParseStatus.UNPROCESSED,
            matchedRuleId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        try {
            rawSmsDao.insert(rawSms)
        } catch (
            @Suppress("SwallowedException") // the unique dedupe_hash index is what makes this safe
            e: SQLiteConstraintException,
        ) {
            // Already archived — a re-delivered broadcast or overlap with the archive-import scan.
        }
        lastProcessedStore.recordProcessed(receivedAt)
    }
}
