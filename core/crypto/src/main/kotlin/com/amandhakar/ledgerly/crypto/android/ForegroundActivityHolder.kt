package com.amandhakar.ledgerly.crypto.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the currently resumed [FragmentActivity] so [CryptoManagerImpl] can show a
 * [androidx.biometric.BiometricPrompt] from a background-triggered operation (e.g. wrapping the
 * Master Key during [CryptoManager.setupPassphrase], which the given interface does not thread an
 * Activity through). The Keystore key requires a fresh authentication for *every* use — encrypt
 * (wrap) as well as decrypt (unwrap), per `setUserAuthenticationParameters(0, ...)` in
 * docs/crypto.md — so this applies uniformly, not only to [CryptoManager.unlockWithBiometric].
 */
@Singleton
class ForegroundActivityHolder @Inject constructor() : Application.ActivityLifecycleCallbacks {

    private var resumed: WeakReference<FragmentActivity>? = null

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun current(): FragmentActivity? = resumed?.get()

    override fun onActivityResumed(activity: Activity) {
        if (activity is FragmentActivity) resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
