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
 * Task 1.1. Does no parsing (docs/parser.md) — a multi-part SMS's fragments are joined back into
 * one body and handed to [RawSmsArchiver]. Nothing past that: the actual pre-filter/rule-engine
 * pipeline runs later, out of band, over `RawSms.parse_status == UNPROCESSED` (Task 1.7 onward
 * isn't wired to Room yet, so there's no follow-up work to enqueue here today).
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
        val subscriptionId = runCatching { messages[0].subscriptionId }.getOrNull()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                archiver.archive(sender, receivedAt, subscriptionId, body)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
