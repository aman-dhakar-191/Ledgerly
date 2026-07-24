package com.amandhakar.ledgerly.database.dao

import com.amandhakar.ledgerly.database.LedgerlyDatabase
import com.amandhakar.ledgerly.database.RoomTestDatabase
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.GoldenTest
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.TransactionAudit
import com.amandhakar.ledgerly.database.entity.AuditReason
import com.amandhakar.ledgerly.database.entity.rawSms
import com.amandhakar.ledgerly.database.entity.testTransaction
import com.amandhakar.ledgerly.database.testAccount
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 0.4: "Every read query must filter deleted_at IS NULL" — verified for every DAO's every
 * read path, not just a representative one, since a single missed filter is a real data leak
 * (a soft-deleted row silently reappearing in a balance or a review inbox).
 */
@RunWith(RobolectricTestRunner::class)
class SoftDeleteTest {

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
    fun `RawSms soft delete excludes from observeAll, byId, and byStatus`() = runTest {
        val sms = rawSms("sms-1", "hash-1")
        db.rawSmsDao().insert(sms)
        db.rawSmsDao().softDelete("sms-1", now)

        assertThat(db.rawSmsDao().observeAll().first()).isEmpty()
        assertThat(db.rawSmsDao().getById("sms-1")).isNull()
        assertThat(db.rawSmsDao().observeByStatus(sms.parseStatus).first()).isEmpty()
        assertThat(db.rawSmsDao().getByDedupeHash("hash-1")).isNull()
    }

    @Test
    fun `SenderRegistry soft delete excludes from observeAll and byId`() = runTest {
        val sender = SenderRegistry("VM-HDFCBK", "HDFCBK", "HDFC", SenderType.BANK, true, null, now, now, null)
        db.senderRegistryDao().insert(sender)
        db.senderRegistryDao().softDelete("VM-HDFCBK", now)

        assertThat(db.senderRegistryDao().observeAll().first()).isEmpty()
        assertThat(db.senderRegistryDao().getById("VM-HDFCBK")).isNull()
    }

    @Test
    fun `ParserRule soft delete excludes from observeAll, byId, and activeForInstitution`() = runTest {
        db.senderRegistryDao().insert(
            SenderRegistry("VM-HDFCBK", "HDFCBK", "HDFC", SenderType.BANK, true, null, now, now, null),
        )
        val rule = ParserRule(
            "rule-1", "HDFCBK", "Rs\\.(\\d+)", """{"1":"amount"}""", ParserTxnType.DEBIT,
            10, 0.9f, true, "sms-1", 0, 0, 1, now, now, null,
        )
        db.parserRuleDao().insert(rule)
        db.parserRuleDao().softDelete("rule-1", now)

        assertThat(db.parserRuleDao().observeAll().first()).isEmpty()
        assertThat(db.parserRuleDao().getById("rule-1")).isNull()
        assertThat(db.parserRuleDao().getActiveForInstitution("HDFCBK")).isEmpty()
    }

    @Test
    fun `GoldenTest soft delete excludes from observeAll and byRuleId`() = runTest {
        val golden = GoldenTest("golden-1", "body", "{}", "rule-1", now, now, null)
        db.goldenTestDao().insert(golden)
        db.goldenTestDao().softDelete("golden-1", now)

        assertThat(db.goldenTestDao().observeAll().first()).isEmpty()
        assertThat(db.goldenTestDao().getByRuleId("rule-1")).isEmpty()
    }

    @Test
    fun `Account soft delete excludes from observeActive and byId`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        db.accountDao().softDelete(account.id, now)

        assertThat(db.accountDao().observeActive().first()).isEmpty()
        assertThat(db.accountDao().getById(account.id)).isNull()
    }

    @Test
    fun `Transaction soft delete excludes from every read path`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        val transaction = testTransaction(account.id)
        db.transactionDao().insert(transaction)
        db.transactionDao().softDelete(transaction.id, now)

        assertThat(db.transactionDao().getById(transaction.id)).isNull()
        assertThat(db.transactionDao().observeByAccountAndDateRange(account.id, 0, Long.MAX_VALUE).first()).isEmpty()
        assertThat(db.transactionDao().observeByStatus(transaction.status).first()).isEmpty()
        assertThat(db.transactionDao().getByTransferId("transfer-1")).isEmpty()
    }

    @Test
    fun `TransactionAudit soft delete excludes from observeForTransaction`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        val transaction = testTransaction(account.id)
        db.transactionDao().insert(transaction)
        val audit = TransactionAudit("audit-1", transaction.id, "amount", "1", "2", now, AuditReason.USER_EDIT, now, now, null)
        db.transactionAuditDao().insert(audit)
        db.transactionAuditDao().softDelete("audit-1", now)

        assertThat(db.transactionAuditDao().observeForTransaction(transaction.id).first()).isEmpty()
    }

    @Test
    fun `BalanceAnchor soft delete excludes from observeForAccount and getLatestAtOrBefore`() = runTest {
        val account = testAccount()
        db.accountDao().insert(account)
        val anchor = BalanceAnchor("anchor-1", account.id, 100_000L, now, BalanceAnchorSource.OPENING, null, now, now, null)
        db.balanceAnchorDao().insert(anchor)
        db.balanceAnchorDao().softDelete("anchor-1", now)

        assertThat(db.balanceAnchorDao().observeForAccount(account.id).first()).isEmpty()
        assertThat(db.balanceAnchorDao().getLatestAtOrBefore(account.id, now)).isNull()
    }
}
