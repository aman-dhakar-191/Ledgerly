package com.amandhakar.ledgerly.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.amandhakar.ledgerly.crypto.android.ForegroundActivityHolder
import com.amandhakar.ledgerly.ingest.MissedSmsCatchUpWorker
import com.amandhakar.ledgerly.update.android.NotificationChannels
import com.amandhakar.ledgerly.update.android.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LedgerlyApplication : Application() {

    @Inject lateinit var foregroundActivityHolder: ForegroundActivityHolder

    override fun onCreate() {
        super.onCreate()
        foregroundActivityHolder.attach(this)

        NotificationChannels.createAll(this)
        // schedule() is KEEP, so calling it on every process start doesn't reset an
        // already-running daily timer; checkNow() covers "just opened the app", since a periodic
        // request's first run can otherwise be delayed by hours (tasks/update-system.md).
        UpdateCheckWorker.schedule(this)
        UpdateCheckWorker.checkNow(this)

        // Also re-armed here (not just after the SMS permission screen), for resilience if
        // WorkManager's own persisted schedule was ever cleared. A no-op if permission was never
        // granted — MissedSmsCatchUpWorker.doWork() checks that itself.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            MissedSmsCatchUpWorker.schedule(this)
        }
    }
}
