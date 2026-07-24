package com.amandhakar.ledgerly.update

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amandhakar.ledgerly.R
import com.amandhakar.ledgerly.ui.unlock.UnlockActivity
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Adapted from the user's Glimpse `UpdateCheckWorker` (tasks/update-system.md): same shape —
 * `checkNow()` for app open since a periodic request's first run can be delayed by hours,
 * `ExistingPeriodicWorkPolicy.KEEP` so repeated `schedule()` calls don't reset the timer, and
 * notify-once-per-`versionCode` via a plain SharedPreferences dedupe. No Hilt injection here: the
 * two things this worker needs, [GithubUpdateChecker] and [Context], are cheap to construct
 * directly, so there's no Configuration.Provider/HiltWorkerFactory ceremony to wire up for it.
 *
 * What's different from Glimpse: no `FirebaseAuth` gate (no Firebase before Phase 5),
 * `NetworkType.CONNECTED` constraint (an offline run is a wasted run, not just a failed one), and
 * the notification carries only the app name and version — never anything from the ledger.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val updateChecker: UpdateChecker = GithubUpdateChecker()

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting here
    override suspend fun doWork(): Result {
        val info = try {
            updateChecker.checkForUpdate()
        } catch (
            @Suppress("SwallowedException") // a network failure means retry, not a logged cause
            e: IOException,
        ) {
            return Result.retry()
        }
        if (info == null) return Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotifiedVersionCode = prefs.getInt(KEY_LAST_NOTIFIED_VERSION_CODE, 0)
        if (info.versionCode <= lastNotifiedVersionCode) return Result.success()

        showUpdateAvailableNotification(info)
        prefs.edit { putInt(KEY_LAST_NOTIFIED_VERSION_CODE, info.versionCode) }
        return Result.success()
    }

    private fun showUpdateAvailableNotification(info: UpdateInfo) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val contentIntent = Intent(context, UnlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(UnlockActivity.EXTRA_OPEN_UPDATE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.UPDATE_AVAILABLE)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_body, info.versionName))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 4001
        private const val UNIQUE_WORK_NAME = "update_check"
        private const val PREFS_NAME = "update_check_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION_CODE = "last_notified_version_code"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun checkNow(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
