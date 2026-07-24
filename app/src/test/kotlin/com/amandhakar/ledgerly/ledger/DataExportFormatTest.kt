package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.BalanceAnchorSource
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Task 1.17: pure CSV/JSON building, no Android/Robolectric needed to exercise it. */
class DataExportFormatTest {

    private val account = Account(
        id = "acct-1",
        name = "Test Savings",
        type = AccountType.SAVINGS,
        last4 = "1234",
        currency = "INR",
        currentBalance = 100_000,
        balanceAsOf = 1_700_000_000_000L,
        creditLimit = null,
        statementDay = null,
        dueDay = null,
        archived = false,
        createdAt = 0,
        updatedAt = 0,
        deletedAt = null,
    )

    private fun transaction(merchant: String?, notes: String?) = Transaction(
        id = "txn-1",
        accountId = "acct-1",
        amount = 50_000L,
        direction = Direction.DEBIT,
        occurredAt = 1_700_000_000_000L,
        merchantRaw = merchant,
        balanceAfter = 50_000L,
        rawSmsId = null,
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        transferId = null,
        isInternal = false,
        notes = notes,
        createdAt = 0,
        updatedAt = 0,
        deletedAt = null,
    )

    @Test
    fun `csv has a header row and one row per transaction`() {
        val csv = buildTransactionsCsv(listOf(transaction("Swiggy", "lunch")), listOf(account))
        val lines = csv.lines()

        assertThat(lines).hasSize(2)
        assertThat(lines[0]).startsWith("id,account_id,account_name")
        assertThat(lines[1]).contains("Test Savings")
        assertThat(lines[1]).contains("Swiggy")
        assertThat(lines[1]).contains("DEBIT")
    }

    @Test
    fun `a merchant containing a comma is quoted in csv`() {
        val csv = buildTransactionsCsv(listOf(transaction("Amazon, Inc", null)), listOf(account))

        assertThat(csv).contains("\"Amazon, Inc\"")
    }

    @Test
    fun `a merchant containing a quote is escaped in csv`() {
        val csv = buildTransactionsCsv(listOf(transaction("Joe's \"Deli\"", null)), listOf(account))

        assertThat(csv).contains("\"Joe's \"\"Deli\"\"\"")
    }

    @Test
    fun `a null merchant renders as an empty csv field, not the literal null`() {
        val csv = buildTransactionsCsv(listOf(transaction(null, null)), listOf(account))

        assertThat(csv).doesNotContain("null")
    }

    @Test
    fun `json includes accounts, transactions and balance anchors`() {
        val anchor = BalanceAnchor(
            id = "anchor-1",
            accountId = "acct-1",
            balance = 100_000,
            asOf = 1_700_000_000_000L,
            source = BalanceAnchorSource.OPENING,
            note = null,
            createdAt = 0,
            updatedAt = 0,
            deletedAt = null,
        )

        val json = buildExportJson(listOf(account), listOf(transaction("Swiggy", null)), listOf(anchor))

        assertThat(json).contains("\"accounts\":[")
        assertThat(json).contains("\"transactions\":[")
        assertThat(json).contains("\"balanceAnchors\":[")
        assertThat(json).contains("\"name\":\"Test Savings\"")
        assertThat(json).contains("\"merchant\":\"Swiggy\"")
        assertThat(json).contains("\"balance\":100000")
    }

    @Test
    fun `json escapes embedded quotes in string fields`() {
        val json = buildExportJson(listOf(account), listOf(transaction("Joe's \"Deli\"", null)), emptyList())

        assertThat(json).contains("\\\"Deli\\\"")
    }

    @Test
    fun `empty lists produce valid, empty json arrays`() {
        val json = buildExportJson(emptyList(), emptyList(), emptyList())

        assertThat(json).isEqualTo("""{"accounts":[],"transactions":[],"balanceAnchors":[]}""")
    }
}
