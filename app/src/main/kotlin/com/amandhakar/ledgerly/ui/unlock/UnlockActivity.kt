package com.amandhakar.ledgerly.ui.unlock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * FragmentActivity (not plain ComponentActivity) because [androidx.biometric.BiometricPrompt]
 * requires one. FLAG_SECURE per tasks/phase-0.md Task 0.8 — this window shows a passphrase and,
 * later, financial data, so it must not appear in screenshots or the recent-apps thumbnail.
 */
@AndroidEntryPoint
class UnlockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val openUpdate = intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)
        setContent {
            UnlockScreen(activity = this, openUpdate = openUpdate)
        }
    }

    companion object {
        /** Set by [com.amandhakar.ledgerly.update.UpdateCheckWorker]'s notification tap intent. */
        const val EXTRA_OPEN_UPDATE = "com.amandhakar.ledgerly.extra.OPEN_UPDATE"
    }
}
