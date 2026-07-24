package com.amandhakar.ledgerly.ingest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

private const val MESSAGE_COUNT = 5_000

/** A fixed inbox of [MESSAGE_COUNT] messages, standing in for `content://sms/inbox`. */
class FakeSmsInboxProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.SUBSCRIPTION_ID),
        )
        repeat(MESSAGE_COUNT) { i ->
            cursor.addRow(arrayOf("AD-ICICIT-S", "message number $i", (1_700_000_000_000L + i), 1))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Task 1.2's own test list: "5,000-message import completes; re-run inserts zero rows." */
@RunWith(RobolectricTestRunner::class)
class ArchiveImporterTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var importer: ArchiveImporter

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(FakeSmsInboxProvider::class.java).create("sms")

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = ArchiveImporter(
            ApplicationProvider.getApplicationContext(),
            RawSmsArchiver(db.rawSmsDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `importing the full inbox archives every message`() = runTest {
        importer.importAll()

        assertThat(db.rawSmsDao().observeAll().first()).hasSize(MESSAGE_COUNT)
    }

    @Test
    fun `running the import twice inserts nothing new`() = runTest {
        importer.importAll()
        importer.importAll()

        assertThat(db.rawSmsDao().observeAll().first()).hasSize(MESSAGE_COUNT)
    }

    @Test
    fun `progress callback reports the correct total and a final call matching it`() = runTest {
        var lastImported = 0
        var reportedTotal = 0
        importer.importAll { imported, total ->
            lastImported = imported
            reportedTotal = total
        }

        assertThat(reportedTotal).isEqualTo(MESSAGE_COUNT)
        assertThat(lastImported).isEqualTo(MESSAGE_COUNT)
    }
}
