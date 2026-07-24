package com.amandhakar.ledgerly.database.entity

import android.database.sqlite.SQLiteConstraintException
import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.RoomTestDatabase
import com.amandhakar.ledgerly.database.testAccount
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 0.3: insert/query round-trip for every Phase 0+1 entity, and the RawSms dedupe uniqueness. */
@RunWith(RobolectricTestRunner::class)
class EntityRoundTripTest {

    private lateinit var db: LedgerlyDatabase
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = RoomTestDatabase.create()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `RawSms round trips and rejects duplicate dedupe hash`() = runTest {
        val sms = RawSms(
            id = "sms-1",
            sender = "VM-HDFCBK",
            body = "Rs.500 debited from A/c XX1234",
            receivedAt = now,
            subscriptionId = 0,
            dedupeHash = "hash-1",
            parseStatus = ParseStatus.UNPROCESSED,
            matchedRuleId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.rawSmsDao().insert(sms)

        val loaded = db.rawSmsDao().getById("sms-1")
        assertThat(loaded).isEqualTo(sms)

        val duplicate = sms.copy(id = "sms-2")
        val thrown = runCatching { db.rawSmsDao().insert(duplicate) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(SQLiteConstraintException::class.java)
    }

    @Test
    fun `SenderRegistry round trips`() = runTest {
        val sender = SenderRegistry(
            senderId = "VM-HDFCBK",
            institution = "HDFCBK",
            label = "HDFC Bank",
            type = SenderType.BANK,
            trusted = true,
            accountId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.senderRegistryDao().insert(sender)
        assertThat(db.senderRegistryDao().getById("VM-HDFCBK")).isEqualTo(sender)
    }

    @Test
    fun `ParserRule round trips`() = runTest {
        val sender = SenderRegistry("VM-HDFCBK", "HDFCBK", "HDFC Bank", SenderType.BANK, true, null, now, now, null)
        db.senderRegistryDao().insert(sender)
        val sms = rawSms("sms-1", "hash-1")
        db.rawSmsDao().insert(sms)

        val rule = ParserRule(
            id = "rule-1",
            institution = "HDFCBK",
            pattern = "Rs\\.(\\d+) debited",
            fieldMap = """{"1":"amount"}""",
            txnType = ParserTxnType.DEBIT,
            priority = 10,
            confidence = 0.9f,
            active = true,
            createdFromSmsId = "sms-1",
            matchCount = 0,
            correctionCount = 0,
            version = 1,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.parserRuleDao().insert(rule)
        assertThat(db.parserRuleDao().getById("rule-1")).isEqualTo(rule)
    }

    @Test
    fun `GoldenTest round trips`() = runTest {
        val golden = GoldenTest(
            id = "golden-1",
            rawBody = "Rs.500 debited from A/c XX1234",
            expectedJson = """{"amount":50000}""",
            ruleId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.goldenTestDao().insert(golden)
        assertThat(db.goldenTestDao().observeAll().first()).containsExactly(golden)
    }

    @Test
    fun `Account round trips`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        assertThat(db.accountDao().getById(account.id)).isEqualTo(account)
    }

    @Test
    fun `Transaction round trips`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)

        val transaction = Transaction(
            id = "txn-1",
            accountId = account.id,
            amount = 50_000L,
            direction = Direction.DEBIT,
            occurredAt = now,
            merchantRaw = "Swiggy",
            balanceAfter = 50_000L,
            rawSmsId = null,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            transferId = null,
            isInternal = false,
            notes = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.transactionDao().insert(transaction)
        assertThat(db.transactionDao().getById("txn-1")).isEqualTo(transaction)
    }

    @Test
    fun `TransactionAudit round trips`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        val transaction = testTransaction(account.id)
        db.transactionDao().insert(transaction)

        val audit = TransactionAudit(
            id = "audit-1",
            transactionId = transaction.id,
            field = "amount",
            oldValue = "40000",
            newValue = "50000",
            changedAt = now,
            reason = AuditReason.USER_EDIT,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.transactionAuditDao().insert(audit)
        assertThat(db.transactionAuditDao().observeForTransaction(transaction.id).first()).containsExactly(audit)
    }

    @Test
    fun `BalanceAnchor round trips`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)

        val anchor = BalanceAnchor(
            id = "anchor-1",
            accountId = account.id,
            balance = 100_000L,
            asOf = now,
            source = BalanceAnchorSource.OPENING,
            note = "Opening balance from statement",
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        db.balanceAnchorDao().insert(anchor)
        assertThat(db.balanceAnchorDao().observeForAccount(account.id).first()).containsExactly(anchor)
    }
}

fun rawSms(id: String, dedupeHash: String, now: Long = 1_700_000_000_000L) = RawSms(
    id = id,
    sender = "VM-HDFCBK",
    body = "Rs.500 debited from A/c XX1234",
    receivedAt = now,
    subscriptionId = 0,
    dedupeHash = dedupeHash,
    parseStatus = ParseStatus.UNPROCESSED,
    matchedRuleId = null,
    createdAt = now,
    updatedAt = now,
    deletedAt = null,
)

fun testTransaction(accountId: String, id: String = "txn-1", now: Long = 1_700_000_000_000L) = Transaction(
    id = id,
    accountId = accountId,
    amount = 50_000L,
    direction = Direction.DEBIT,
    occurredAt = now,
    merchantRaw = "Swiggy",
    balanceAfter = 50_000L,
    rawSmsId = null,
    source = TransactionSource.MANUAL,
    status = TransactionStatus.CONFIRMED,
    transferId = null,
    isInternal = false,
    notes = null,
    createdAt = now,
    updatedAt = now,
    deletedAt = null,
)
