package com.amandhakar.ledgerly.ingest

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LastProcessedStoreTest {

    private val store = LastProcessedStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `starts unset`() {
        assertThat(store.getLastProcessedAt()).isNull()
    }

    @Test
    fun `recording a timestamp makes it readable`() {
        store.recordProcessed(1_700_000_000_000L)

        assertThat(store.getLastProcessedAt()).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a later timestamp advances the mark`() {
        store.recordProcessed(1_700_000_000_000L)
        store.recordProcessed(1_700_000_001_000L)

        assertThat(store.getLastProcessedAt()).isEqualTo(1_700_000_001_000L)
    }

    @Test
    fun `an out-of-order older timestamp does not rewind the mark`() {
        store.recordProcessed(1_700_000_001_000L)
        store.recordProcessed(1_700_000_000_000L)

        assertThat(store.getLastProcessedAt()).isEqualTo(1_700_000_001_000L)
    }
}
