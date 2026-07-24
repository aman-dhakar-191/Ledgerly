package com.amandhakar.ledgerly.ingest

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.parser.computeDedupeHash
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 1.1's own test list: "duplicate SMS inserts once ... missing permission does not crash."
 * The "receiver returns fast" requirement is a `goAsync()`/coroutine-dispatch property of
 * [SmsReceiver] itself, not something a unit test observes here — this covers the archiving logic
 * [SmsReceiver] delegates to.
 */
@RunWith(RobolectricTestRunner::class)
class RawSmsArchiverTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var archiver: RawSmsArchiver

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        archiver = RawSmsArchiver(db.rawSmsDao(), LastProcessedStore(ApplicationProvider.getApplicationContext()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `archiving a message stores its sender and body verbatim`() = runTest {
        archiver.archive("AD-ICICIT-S", 1_700_000_000_000L, subscriptionId = 1, body = "test message")

        val stored = db.rawSmsDao().getByDedupeHash(
            computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "test message"),
        )
        assertThat(stored?.sender).isEqualTo("AD-ICICIT-S")
        assertThat(stored?.body).isEqualTo("test message")
        assertThat(stored?.receivedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `the same message archived twice is stored once`() = runTest {
        repeat(2) {
            archiver.archive("AD-ICICIT-S", 1_700_000_000_000L, subscriptionId = 1, body = "duplicate message")
        }

        assertThat(db.rawSmsDao().observeAll().first()).hasSize(1)
    }

    @Test
    fun `two different messages are both stored`() = runTest {
        archiver.archive("AD-ICICIT-S", 1_700_000_000_000L, subscriptionId = 1, body = "message one")
        archiver.archive("AD-ICICIT-S", 1_700_000_001_000L, subscriptionId = 1, body = "message two")

        val first = db.rawSmsDao().getByDedupeHash(
            computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "message one"),
        )
        val second = db.rawSmsDao().getByDedupeHash(
            computeDedupeHash("AD-ICICIT-S", 1_700_000_001_000L, "message two"),
        )
        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(first!!.id).isNotEqualTo(second!!.id)
    }

    @Test
    fun `archived message defaults to UNPROCESSED status`() = runTest {
        archiver.archive("AD-ICICIT-S", 1_700_000_000_000L, subscriptionId = null, body = "no subscription id")

        val stored = db.rawSmsDao().getByDedupeHash(
            computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "no subscription id"),
        )
        assertThat(stored?.parseStatus).isEqualTo(ParseStatus.UNPROCESSED)
        assertThat(stored?.subscriptionId).isNull()
    }

    @Test
    fun `archiving advances the last-processed-at mark, used by Task 1_16's catch-up worker`() = runTest {
        val store = LastProcessedStore(ApplicationProvider.getApplicationContext())
        val archiverWithStore = RawSmsArchiver(db.rawSmsDao(), store)

        archiverWithStore.archive("AD-ICICIT-S", 1_700_000_000_000L, subscriptionId = 1, body = "message one")
        archiverWithStore.archive("AD-ICICIT-S", 1_700_000_001_000L, subscriptionId = 1, body = "message two")

        assertThat(store.getLastProcessedAt()).isEqualTo(1_700_000_001_000L)
    }
}
