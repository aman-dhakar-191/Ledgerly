package com.amandhakar.ledgerly.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amandhakar.ledgerly.ledger.SmsParsingPipeline
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Runs [SmsParsingPipeline] over whatever `RawSms` rows are still `UNPROCESSED` — enqueued after
 * anything that can add new archive rows ([ArchiveImportWorker], [MissedSmsCatchUpWorker],
 * [SmsReceiver]), same EntryPoint pattern as those workers for the same reason: this needs the
 * app's single [com.amandhakar.ledgerly.database.LedgerlyDatabase] instance, not something cheap to
 * construct standalone.
 */
class SmsParsingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun smsParsingPipeline(): SmsParsingPipeline
    }

    override suspend fun doWork(): Result {
        val pipeline = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).smsParsingPipeline()
        pipeline.processUnprocessed()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sms_parsing"

        /** APPEND_OR_REPLACE: several archive sources can trigger this in quick succession; each run picks up whatever is still pending. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsParsingWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
