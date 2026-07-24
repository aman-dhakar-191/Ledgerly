package com.amandhakar.ledgerly.ingest

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 1.16's low-water mark: the newest `receivedAt` seen by [RawSmsArchiver.archive] so far,
 * across both the live [SmsReceiver] and [ArchiveImporter]. [MissedSmsCatchUpWorker] scans the
 * inbox for anything newer than this instead of re-scanning the whole thing every 6 hours, and the
 * home screen surfaces it as the "is background ingestion actually running" signal Task 1.16 calls
 * for.
 */
@Singleton
class LastProcessedStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastProcessedAt(): Long? = prefs.getLong(KEY_LAST_PROCESSED_AT, NOT_SET).takeIf { it != NOT_SET }

    /** Only ever moves forward — archiving an older message (e.g. a backfill) must not rewind it. */
    fun recordProcessed(receivedAt: Long) {
        if (receivedAt > (getLastProcessedAt() ?: Long.MIN_VALUE)) {
            prefs.edit { putLong(KEY_LAST_PROCESSED_AT, receivedAt) }
        }
    }

    private companion object {
        const val PREFS_NAME = "sms_ingest_prefs"
        const val KEY_LAST_PROCESSED_AT = "last_processed_at"
        const val NOT_SET = -1L
    }
}
