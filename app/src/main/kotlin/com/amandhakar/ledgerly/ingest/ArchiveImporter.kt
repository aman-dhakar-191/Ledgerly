package com.amandhakar.ledgerly.ingest

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Task 1.2: one-time bootstrap over the existing inbox. Import everything, as far back as the
 * inbox goes — raw text is cheap and it's the validation corpus for rule generation (Task 1.7).
 * No parsing here either, same as [SmsReceiver]; this only archives via [RawSmsArchiver], whose
 * dedupe_hash uniqueness is what makes running this twice insert nothing new.
 */
class ArchiveImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archiver: RawSmsArchiver,
) {
    /** [onProgress] fires every [PROGRESS_BATCH_SIZE] messages, plus once at the end. */
    suspend fun importAll(onProgress: suspend (imported: Int, total: Int) -> Unit = { _, _ -> }) {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val total = cursor.count
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val subscriptionIndex = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

            var imported = 0
            while (cursor.moveToNext()) {
                val sender = cursor.getString(addressIndex) ?: continue
                val body = cursor.getString(bodyIndex).orEmpty()
                val receivedAt = cursor.getLong(dateIndex)
                val subscriptionId = if (subscriptionIndex >= 0) cursor.getInt(subscriptionIndex) else null

                archiver.archive(sender, receivedAt, subscriptionId, body)
                imported++
                if (imported % PROGRESS_BATCH_SIZE == 0) onProgress(imported, total)
            }
            onProgress(imported, total)
        }
    }

    private companion object {
        const val PROGRESS_BATCH_SIZE = 200
    }
}
