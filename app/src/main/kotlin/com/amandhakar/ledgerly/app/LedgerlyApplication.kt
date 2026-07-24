package com.amandhakar.ledgerly.app

import android.app.Application
import com.amandhakar.ledgerly.crypto.android.ForegroundActivityHolder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LedgerlyApplication : Application() {

    @Inject lateinit var foregroundActivityHolder: ForegroundActivityHolder

    override fun onCreate() {
        super.onCreate()
        foregroundActivityHolder.attach(this)
    }
}
