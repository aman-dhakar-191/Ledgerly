package com.amandhakar.ledgerly.app

import android.app.Application
import com.amandhakar.ledgerly.crypto.android.ForegroundActivityHolder
import com.amandhakar.ledgerly.update.NotificationChannels
import com.amandhakar.ledgerly.update.UpdateCheckWorker
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
    }
}
