package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.5: a WALLET account carries no `last4`, so [SmsParsingPipeline.resolveAccount]'s only
 * path to it is `SenderRegistry.accountId` - this is the mechanism [AccountsViewModel.createAccount]
 * calls to finally set that link.
 */
@RunWith(RobolectricTestRunner::class)
class WalletSenderLinkingTest {

    private lateinit var db: LedgerlyDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun untrustedSender(senderId: String, institution: String) {
        db.senderRegistryDao().insert(
            SenderRegistry(
                senderId = senderId,
                institution = institution,
                label = institution,
                type = SenderType.UNKNOWN,
                trusted = false,
                accountId = null,
                createdAt = 0,
                updatedAt = 0,
                deletedAt = null,
            ),
        )
    }

    @Test
    fun `every raw sender already seen for the institution is linked to the account`() = runTest {
        untrustedSender("JX-JUSPAY-S", "JUSPAY")
        untrustedSender("AX-JUSPAY-S", "JUSPAY")
        untrustedSender("VM-ZOMATO-S", "ZOMATO")

        linkSendersToAccount(db.senderRegistryDao(), "juspay", "wallet-1", now = 1_000L)

        assertThat(db.senderRegistryDao().getById("JX-JUSPAY-S")!!.accountId).isEqualTo("wallet-1")
        assertThat(db.senderRegistryDao().getById("AX-JUSPAY-S")!!.accountId).isEqualTo("wallet-1")
        assertThat(db.senderRegistryDao().getById("VM-ZOMATO-S")!!.accountId).isNull()
    }

    @Test
    fun `an institution with no senders seen yet links nothing and does not throw`() = runTest {
        linkSendersToAccount(db.senderRegistryDao(), "JUSPAY", "wallet-1", now = 1_000L)
    }
}
