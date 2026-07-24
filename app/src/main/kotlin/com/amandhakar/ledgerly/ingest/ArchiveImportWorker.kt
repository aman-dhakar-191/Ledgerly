package com.amandhakar.ledgerly.ingest

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Task 1.2, "survive rotation and backgrounding" — a plain screen-scoped coroutine dies with its
 * screen; WorkManager is what actually survives that. Unlike [com.amandhakar.ledgerly.update.android.UpdateCheckWorker],
 * this one needs the app's single [com.amandhakar.ledgerly.database.LedgerlyDatabase] instance
 * (via [ArchiveImporter]/[RawSmsArchiver]), not something cheap to construct standalone — an
 * [EntryPoint] pulls it out of Hilt's `SingletonComponent` without needing the full
 * HiltWorkerFactory/`Configuration.Provider` setup just for this one worker.
 */
class ArchiveImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun archiveImporter(): ArchiveImporter
    }

    override suspend fun doWork(): Result {
        val importer = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java).archiveImporter()
        importer.importAll { imported, total ->
            setProgress(workDataOf(KEY_IMPORTED to imported, KEY_TOTAL to total))
        }
        return Result.success()
    }

    companion object {
        const val KEY_IMPORTED = "imported"
        const val KEY_TOTAL = "total"
        private const val UNIQUE_WORK_NAME = "archive_import"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArchiveImportWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
