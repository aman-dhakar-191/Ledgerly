package com.amandhakar.ledgerly.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.ParseClass
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 2.2/docs/corpus-findings.md §2a's own test list, exercised against a real in-memory Room
 * database. `InfoBIL*INFT*FGR6` is the corpus's actual (varying-suffix) narration text.
 */
@RunWith(RobolectricTestRunner::class)
class CardPaymentMatcherTest {

    private lateinit var db: LedgerlyDatabase
    private lateinit var matcher: CardPaymentMatcher

    // 2025-07-23 03:20 UTC - well clear of a day boundary in any zone the test JVM might use.
    private val baseTime = 1_753_240_800_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        matcher = CardPaymentMatcher(db.transactionDao(), db.rawSmsDao(), db.accountDao(), db.transferDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun account(id: String, type: AccountType) = Account(
        id = id,
        name = "Test $id",
        type = type,
        last4 = null,
        currency = "INR",
        currentBalance = 0,
        balanceAsOf = 0,
        creditLimit = null,
        statementDay = null,
        dueDay = null,
        archived = false,
        createdAt = 0,
        updatedAt = 0,
        deletedAt = null,
    ).also { db.accountDao().insert(it) }

    private suspend fun rawSms(id: String, body: String, receivedAt: Long) = RawSms(
        id = id,
        sender = "AD-ICICIT-S",
        body = body,
        receivedAt = receivedAt,
        subscriptionId = null,
        dedupeHash = id,
        institution = "ICICIT",
        parseStatus = ParseStatus.PARSED,
        parseClass = ParseClass.TRANSACTION,
        matchedRuleId = null,
        createdAt = receivedAt,
        updatedAt = receivedAt,
        deletedAt = null,
    ).also { db.rawSmsDao().insert(it) }

    @Suppress("LongParameterList") // one field per typed value the test needs to vary
    private suspend fun transaction(
        id: String,
        accountId: String,
        amount: Long,
        direction: Direction,
        occurredAt: Long,
        rawSmsId: String?,
    ) = Transaction(
        id = id,
        accountId = accountId,
        amount = amount,
        direction = direction,
        occurredAt = occurredAt,
        merchantRaw = null,
        balanceAfter = null,
        rawSmsId = rawSmsId,
        source = if (rawSmsId != null) TransactionSource.SMS_GENERIC else TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        transferId = null,
        isInternal = false,
        notes = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        deletedAt = null,
    ).also { db.transactionDao().insert(it) }

    @Test
    fun `matched pair produces one transfer and zero expense`() = runTest {
        account("bank", AccountType.SAVINGS)
        account("card", AccountType.CREDIT_CARD)
        rawSms("sms-1", "ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26 InfoBIL*INFT*FGR6.Avl Bal Rs. 8,611.98.", baseTime)
        val debit = transaction("txn-debit", "bank", 217_000, Direction.DEBIT, baseTime, "sms-1")
        val credit = transaction("txn-credit", "card", 217_000, Direction.CREDIT, baseTime + 20 * 60_000, null)

        matcher.tryMatch(debit)

        val linkedDebit = db.transactionDao().getById(debit.id)!!
        val linkedCredit = db.transactionDao().getById(credit.id)!!
        assertThat(linkedDebit.transferId).isNotNull()
        assertThat(linkedDebit.isInternal).isTrue()
        assertThat(linkedCredit.transferId).isEqualTo(linkedDebit.transferId)
        assertThat(linkedCredit.isInternal).isTrue()
        assertThat(db.transferDao().observeAll().first().single().kind.name).isEqualTo("CARD_PAYMENT")
    }

    @Test
    fun `InfoBIL INFT debit with no counterpart stays a normal expense`() = runTest {
        account("bank", AccountType.SAVINGS)
        rawSms("sms-1", "ICICI Bank Acc XX924 debited Rs. 500.00 on 23-Jul-26 InfoBIL*INFT*ABCD.Avl Bal Rs. 1,000.00.", baseTime)
        val debit = transaction("txn-debit", "bank", 50_000, Direction.DEBIT, baseTime, "sms-1")

        matcher.tryMatch(debit)

        val result = db.transactionDao().getById(debit.id)!!
        assertThat(result.transferId).isNull()
        assertThat(result.isInternal).isFalse()
    }

    @Test
    fun `two same-amount payments on one day to different cards match correctly`() = runTest {
        account("bank", AccountType.SAVINGS)
        account("card-a", AccountType.CREDIT_CARD)
        account("card-b", AccountType.CREDIT_CARD)
        rawSms("sms-1", "ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26 InfoBIL*INFT*FGR6.Avl Bal Rs. 8,611.98.", baseTime)
        rawSms(
            "sms-2",
            "ICICI Bank Acc XX924 debited Rs. 2,170.00 on 23-Jul-26 InfoBIL*INFT*ZZZ1.Avl Bal Rs. 6,441.98.",
            baseTime + 3600_000,
        )
        val debitA = transaction("txn-debit-a", "bank", 217_000, Direction.DEBIT, baseTime, "sms-1")
        val debitB = transaction("txn-debit-b", "bank", 217_000, Direction.DEBIT, baseTime + 3600_000, "sms-2")
        val creditA = transaction("txn-credit-a", "card-a", 217_000, Direction.CREDIT, baseTime + 10 * 60_000, null)
        val creditB = transaction("txn-credit-b", "card-b", 217_000, Direction.CREDIT, baseTime + 3600_000 + 10 * 60_000, null)

        matcher.tryMatch(debitA)
        matcher.tryMatch(debitB)

        val linkedA = db.transactionDao().getById(debitA.id)!!
        val linkedB = db.transactionDao().getById(debitB.id)!!
        assertThat(linkedA.transferId).isNotEqualTo(linkedB.transferId)
        assertThat(db.transactionDao().getById(creditA.id)!!.transferId).isEqualTo(linkedA.transferId)
        assertThat(db.transactionDao().getById(creditB.id)!!.transferId).isEqualTo(linkedB.transferId)
    }
}
