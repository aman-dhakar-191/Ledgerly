package com.amandhakar.ledgerly.ledger

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgerSettingsStoreTest {

    private val store = LedgerSettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `starts unset`() {
        assertThat(store.getLedgerStartDate()).isNull()
    }

    @Test
    fun `set then get round-trips`() {
        store.setLedgerStartDate(1_700_000_000_000L)

        assertThat(store.getLedgerStartDate()).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `default is the first of the month, three months back`() {
        val now = zonedMillis(2026, 7, 24)

        val default = store.defaultLedgerStartDate(now)

        assertThat(toLocalDate(default)).isEqualTo(LocalDate.of(2026, 4, 1))
    }

    private fun zonedMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun toLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}
