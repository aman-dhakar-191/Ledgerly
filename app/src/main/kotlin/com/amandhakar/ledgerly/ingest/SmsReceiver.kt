package com.amandhakar.ledgerly.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Task 1.1. Does no parsing itself (docs/parser.md) — a multi-part SMS's fragments are joined back
 * into one body and handed to [RawSmsArchiver]; [SmsParsingWorker] is what actually runs the
 * pre-filter/rule-engine pipeline over `RawSms.parse_status == UNPROCESSED`, out of band.
 *
 * `goAsync()` is required — `onReceive` normally must return almost immediately, but the Room
 * insert is a suspend call. Android gives a `PendingResult` a short grace window (state-dependent,
 * roughly 10s) to finish that work off the main thread before the process can be killed.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var archiver: RawSmsArchiver

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting here
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: return
        val receivedAt = messages[0].timestampMillis
        // SmsMessage carries no subscription info of its own on a multi-SIM device — it's an
        // extra on the broadcast Intent itself.
        val subscriptionId = intent.getIntExtra("subscription", -1).takeIf { it != -1 }
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                archiver.archive(sender, receivedAt, subscriptionId, body)
                SmsParsingWorker.enqueue(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
