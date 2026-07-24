package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.GoldenTestDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.GoldenTest
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.MatchOutcome
import com.amandhakar.ledgerly.parser.generateRule
import com.amandhakar.ledgerly.parser.matchWithTimeout
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Task 1.13: "Edit inline; confirm generates a rule (Task 1.7) and a golden test." A field the
 * user left equal to what [GenericExtractor] produced anchors the generated rule (Task 1.7's
 * "over-specific... a rule that matches too broadly corrupts the ledger" — a corrected field can't
 * be trusted to recur, so it's kept out of the pattern rather than generalised from one example).
 *
 * Rule activation additionally requires (docs/parser.md's rule validation): no timeout on its own
 * source message, and no conflict with a message another active rule has already claimed. Failing
 * either just means no rule this round, not a blocked confirmation — the transaction and golden
 * test are written regardless.
 */
class ReviewConfirmationService @Inject constructor(
    private val transactionDao: TransactionDao,
    private val rawSmsDao: RawSmsDao,
    private val parserRuleDao: ParserRuleDao,
    private val goldenTestDao: GoldenTestDao,
    private val transactionAuditDao: TransactionAuditDao,
) {
    suspend fun confirm(transaction: Transaction, correction: ReviewCorrection) {
        val now = System.currentTimeMillis()
        writeTransactionEditAudit(transactionAuditDao, transaction, correction, now)
        transactionDao.update(
            transaction.copy(
                amount = correction.amount,
                direction = correction.direction,
                merchantRaw = correction.merchant,
                occurredAt = correction.occurredAt,
                balanceAfter = correction.balanceAfter,
                status = TransactionStatus.CONFIRMED,
                updatedAt = now,
            ),
        )

        val rawSms = transaction.rawSmsId?.let { rawSmsDao.getById(it) } ?: return
        val extraction = GenericExtractor.extract(rawSms.body, rawSms.receivedAt)
        val ruleId = generateAndActivateRule(rawSms, extraction, correction)

        goldenTestDao.insert(
            GoldenTest(
                id = UUID.randomUUID().toString(),
                rawBody = anonymize(rawSms.body, extraction.accountLast4.span),
                expectedJson = encodeExpectation(correction),
                ruleId = ruleId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this validation gate
    private suspend fun generateAndActivateRule(
        rawSms: RawSms,
        extraction: GenericExtraction,
        correction: ReviewCorrection,
    ): String? {
        val confirmedFields = confirmedSpans(extraction, correction)
        if (confirmedFields.isEmpty()) return null

        val candidate = generateRule(rawSms.body, confirmedFields)
        val pattern = runCatching { Regex(candidate.pattern) }.getOrNull() ?: return null
        if (matchWithTimeout(pattern, rawSms.body) !is MatchOutcome.Matched) return null

        val conflict = rawSmsDao.observeAll().first().any { other ->
            other.institution == rawSms.institution && other.matchedRuleId != null &&
                matchWithTimeout(pattern, other.body) is MatchOutcome.Matched
        }
        if (conflict) return null

        val ruleId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        parserRuleDao.insert(
            ParserRule(
                id = ruleId,
                institution = rawSms.institution,
                pattern = candidate.pattern,
                fieldMap = encodeFieldMap(candidate.fieldMap),
                txnType = if (correction.direction == Direction.DEBIT) ParserTxnType.DEBIT else ParserTxnType.CREDIT,
                priority = 0,
                confidence = 1f,
                active = true,
                createdFromSmsId = rawSms.id,
                matchCount = 1,
                correctionCount = 0,
                version = 1,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
        rawSmsDao.update(rawSms.copy(matchedRuleId = ruleId, updatedAt = now))
        return ruleId
    }

    /** Only fields the user left as-extracted are trustworthy enough to anchor a rule on. */
    private fun confirmedSpans(extraction: GenericExtraction, correction: ReviewCorrection): Map<String, IntRange> {
        val fields = mutableMapOf<String, IntRange>()
        if (extraction.amount.value == correction.amount) extraction.amount.span?.let { fields["amount"] = it }
        if (extraction.merchant.value == correction.merchant) extraction.merchant.span?.let { fields["merchant"] = it }
        if (extraction.occurredAt.value == correction.occurredAt) extraction.occurredAt.span?.let { fields["occurredAt"] = it }
        if (extraction.balanceAfter.value == correction.balanceAfter) {
            extraction.balanceAfter.span?.let { fields["balanceAfter"] = it }
        }
        return fields
    }
}

private fun encodeFieldMap(fieldMap: Map<String, Int>): String =
    fieldMap.entries.joinToString(prefix = "{", postfix = "}") { (name, group) -> "\"$name\":$group" }

private fun encodeExpectation(correction: ReviewCorrection): String {
    val merchantJson = correction.merchant?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
    val balanceJson = correction.balanceAfter?.toString() ?: "null"
    return """{"amount":${correction.amount},"direction":"${correction.direction}",""" +
        """"merchant":$merchantJson,"occurredAt":${correction.occurredAt},"balanceAfter":$balanceJson}"""
}

/** Task 1.14's anonymisation rule: digits in the account-number span become `X`; amounts and merchant are kept as-is. */
private fun anonymize(body: String, accountSpan: IntRange?): String {
    if (accountSpan == null) return body
    val masked = body.substring(accountSpan.first, accountSpan.last + 1).map { if (it.isDigit()) 'X' else it }.joinToString("")
    return body.substring(0, accountSpan.first) + masked + body.substring(accountSpan.last + 1)
}
