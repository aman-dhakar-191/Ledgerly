package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.BalanceAnchor
import com.amandhakar.ledgerly.database.entity.Transaction
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Task 1.17: "CSV and JSON, to a user-chosen location via ACTION_CREATE_DOCUMENT... the only
 * recovery path if the device, the app, or the signing key is lost" (docs/signing.md). Pure string
 * building, kept separate from the DAO/SAF wiring ([DataExporter]) so it's testable without
 * Robolectric — no `kotlinx.serialization` dependency in `:app` for what's ultimately a handful of
 * flat records; hand-rolled escaping is simpler than adding it for this alone.
 */
private val CSV_HEADER = listOf(
    "id", "account_id", "account_name", "amount", "currency", "direction", "occurred_at",
    "merchant", "status", "source", "is_internal", "notes",
)

fun buildTransactionsCsv(transactions: List<Transaction>, accounts: List<Account>): String {
    val accountById = accounts.associateBy { it.id }
    val rows = transactions.sortedBy { it.occurredAt }.map { txn ->
        val account = accountById[txn.accountId]
        listOf(
            txn.id,
            txn.accountId,
            account?.name.orEmpty(),
            txn.amount.toString(),
            account?.currency.orEmpty(),
            txn.direction.name,
            formatIso(txn.occurredAt),
            txn.merchantRaw.orEmpty(),
            txn.status.name,
            txn.source.name,
            txn.isInternal.toString(),
            txn.notes.orEmpty(),
        ).joinToString(",") { csvField(it) }
    }
    return (listOf(CSV_HEADER.joinToString(",")) + rows).joinToString("\n")
}

fun buildExportJson(accounts: List<Account>, transactions: List<Transaction>, anchors: List<BalanceAnchor>): String {
    val accountsJson = accounts.joinToString(",", prefix = "[", postfix = "]") { accountJson(it) }
    val transactionsJson = transactions.joinToString(",", prefix = "[", postfix = "]") { transactionJson(it) }
    val anchorsJson = anchors.joinToString(",", prefix = "[", postfix = "]") { anchorJson(it) }
    return """{"accounts":$accountsJson,"transactions":$transactionsJson,"balanceAnchors":$anchorsJson}"""
}

private fun accountJson(account: Account) = """{"id":${jsonString(account.id)},"name":${jsonString(account.name)},""" +
    """"type":${jsonString(account.type.name)},"last4":${jsonNullableString(account.last4)},""" +
    """"currency":${jsonString(account.currency)},"currentBalance":${account.currentBalance},""" +
    """"balanceAsOf":${account.balanceAsOf}}"""

private fun transactionJson(txn: Transaction) = """{"id":${jsonString(txn.id)},"accountId":${jsonString(txn.accountId)},""" +
    """"amount":${txn.amount},"direction":${jsonString(txn.direction.name)},"occurredAt":${txn.occurredAt},""" +
    """"merchant":${jsonNullableString(txn.merchantRaw)},"balanceAfter":${txn.balanceAfter ?: "null"},""" +
    """"status":${jsonString(txn.status.name)},"source":${jsonString(txn.source.name)},""" +
    """"isInternal":${txn.isInternal},"notes":${jsonNullableString(txn.notes)}}"""

private fun anchorJson(anchor: BalanceAnchor) = """{"id":${jsonString(anchor.id)},"accountId":${jsonString(anchor.accountId)},""" +
    """"balance":${anchor.balance},"asOf":${anchor.asOf},"source":${jsonString(anchor.source.name)}}"""

private fun jsonString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
private fun jsonNullableString(value: String?) = value?.let { jsonString(it) } ?: "null"

private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

private fun formatIso(epochMillis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))
