package com.amandhakar.ledgerly.update.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.amandhakar.ledgerly.R

object NotificationChannels {
    const val UPDATE_AVAILABLE = "update_available"

    /** CONTEXT.md invariant #12: this channel's notifications never carry financial data. */
    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            UPDATE_AVAILABLE,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
