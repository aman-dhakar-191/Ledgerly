package com.amandhakar.ledgerly.ledger

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 1.10: `ledger_start_date`, the boundary the whole initialization flow exists to set.
 * Messages before it stay `RawSms` forever, used only for rule validation — they never become
 * transactions, even after rules exist that could parse them.
 */
@Singleton
class LedgerSettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLedgerStartDate(): Long? = prefs.getLong(KEY_LEDGER_START_DATE, NOT_SET).takeIf { it != NOT_SET }

    fun setLedgerStartDate(epochMillis: Long) {
        prefs.edit { putLong(KEY_LEDGER_START_DATE, epochMillis) }
    }

    /** Task 1.10 step 2's own default: "first of the month, 3 months back." */
    fun defaultLedgerStartDate(now: Long = System.currentTimeMillis()): Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            .toLocalDate()
            .minusMonths(MONTHS_BACK)
            .withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private companion object {
        const val PREFS_NAME = "ledger_settings_prefs"
        const val KEY_LEDGER_START_DATE = "ledger_start_date"
        const val NOT_SET = -1L
        const val MONTHS_BACK = 3L
    }
}
