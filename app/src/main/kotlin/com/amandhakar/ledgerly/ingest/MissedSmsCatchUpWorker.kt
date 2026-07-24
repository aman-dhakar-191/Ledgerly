package com.amandhakar.ledgerly.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Task 1.16: "Every 6 hours, WorkManager scans the SMS inbox for messages newer than the last
 * processed timestamp and ingests any that are missing" (docs/parser.md) — covers whatever the
 * live [SmsReceiver] missed to battery optimisation, doze, or an OEM killing the process before
 * the broadcast was delivered. A no-op, not a failure, if SMS permission was never granted or
 * nothing has ever been imported yet — there's nothing to catch up from.
 */
class MissedSmsCatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun archiveImporter(): ArchiveImporter
        fun lastProcessedStore(): LastProcessedStore
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting here
    override suspend fun doWork(): Result {
        val granted = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.success()

        val entry = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java)
        val since = entry.lastProcessedStore().getLastProcessedAt() ?: return Result.success()
        entry.archiveImporter().importSince(since)
        SmsParsingWorker.enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "missed_sms_catch_up"
        private const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MissedSmsCatchUpWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
