package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.GoldenTestDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.PayeeAllowlistDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.TransactionAuditDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.GoldenTest
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.MatchOutcome
import com.amandhakar.ledgerly.parser.ReconciliationResult
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
@Suppress("LongParameterList") // one DAO per entity this service touches, matching codebase precedent
class ReviewConfirmationService @Inject constructor(
    private val transactionDao: TransactionDao,
    private val rawSmsDao: RawSmsDao,
    private val parserRuleDao: ParserRuleDao,
    private val goldenTestDao: GoldenTestDao,
    private val transactionAuditDao: TransactionAuditDao,
    private val payeeAllowlistDao: PayeeAllowlistDao,
    private val accountDao: AccountDao,
    private val balanceAnchorDao: BalanceAnchorDao,
    private val transactionReconciler: TransactionReconciler,
    private val smsParsingPipeline: SmsParsingPipeline,
) {
    suspend fun confirm(transaction: Transaction, correction: ReviewCorrection) {
        val now = System.currentTimeMillis()
        val changed = writeTransactionEditAudit(transactionAuditDao, transaction, correction, now)
        if (changed) maybeIncrementRuleCorrection(rawSmsDao, parserRuleDao, transaction, now)

        // Task 1.12: the only place a new PayeeAllowlist entry is ever created - explicit user
        // confirmation from the review inbox, never inferred from a resembling name.
        if (correction.markInternalTransfer && correction.merchant != null) {
            confirmAllowlistedPayee(payeeAllowlistDao, correction.merchant, now)
        }
        val isInternal = correction.markInternalTransfer || isAllowlistedPayee(payeeAllowlistDao, correction.merchant)

        transactionDao.update(
            transaction.copy(
                amount = correction.amount,
                direction = correction.direction,
                merchantRaw = correction.merchant,
                occurredAt = correction.occurredAt,
                balanceAfter = correction.balanceAfter,
                isInternal = isInternal,
                status = TransactionStatus.CONFIRMED,
                updatedAt = now,
            ),
        )
        reconcileAndReanchor(transaction, correction)

        val rawSms = transaction.rawSmsId?.let { rawSmsDao.getById(it) } ?: return
        val extraction = GenericExtractor.extract(rawSms.body, rawSms.receivedAt)
        val rule = generateAndActivateRule(rawSms, extraction, correction)

        // A confirmed message is resolved for good — PARSED keeps it out of Task 1.7's backfill
        // scan (REVIEW/FAILED only), which must never touch an already-user-confirmed transaction.
        rawSmsDao.update(
            rawSms.copy(parseStatus = ParseStatus.PARSED, matchedRuleId = rule?.id ?: rawSms.matchedRuleId, updatedAt = now),
        )

        goldenTestDao.insert(
            GoldenTest(
                id = UUID.randomUUID().toString(),
                rawBody = anonymize(rawSms.body, extraction.accountLast4.span),
                expectedJson = encodeExpectation(correction),
                ruleId = rule?.id,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )

        // docs/parser.md's backfill: "when a rule is added ... re-run over RawSms where
        // parse_status IN (REVIEW, FAILED) for that sender" - other pending messages this exact
        // shape already covers shouldn't wait for their own individual confirmation.
        if (rule != null) smsParsingPipeline.backfillRule(rule)
    }

    /**
     * A confirmed transaction that carries a stated balance must reconcile and re-anchor exactly
     * like a Tier-1 rule match does (docs/corpus-findings.md §2) - without this, the account's
     * cached balance only ever advances via *other* messages a newly-generated rule happens to
     * backfill, never via the confirmation that generated the rule in the first place.
     */
    private suspend fun reconcileAndReanchor(transaction: Transaction, correction: ReviewCorrection) {
        val balanceAfter = correction.balanceAfter ?: return
        val reconciliation = transactionReconciler.reconcile(
            transaction.accountId,
            correction.occurredAt,
            correction.amount,
            correction.direction.toParserDirection(),
            balanceAfter,
        )
        if (reconciliation is ReconciliationResult.Confirmed) {
            val account = accountDao.getById(transaction.accountId) ?: return
            reanchorAccount(accountDao, balanceAnchorDao, account, reconciliation.newBalance, correction.occurredAt)
        }
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this validation gate
    private suspend fun generateAndActivateRule(
        rawSms: RawSms,
        extraction: GenericExtraction,
        correction: ReviewCorrection,
    ): ParserRule? {
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

        val now = System.currentTimeMillis()
        val rule = ParserRule(
            id = UUID.randomUUID().toString(),
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
        )
        parserRuleDao.insert(rule)
        return rule
    }

    /** Only fields the user left as-extracted are trustworthy enough to anchor a rule on. */
    private fun confirmedSpans(extraction: GenericExtraction, correction: ReviewCorrection): Map<String, IntRange> {
        val candidates = mutableListOf<Pair<String, IntRange>>()
        if (extraction.amount.value == correction.amount) extraction.amount.span?.let { candidates += "amount" to it }
        if (extraction.merchant.value == correction.merchant) extraction.merchant.span?.let { candidates += "merchant" to it }
        if (extraction.occurredAt.value == correction.occurredAt) extraction.occurredAt.span?.let { candidates += "occurredAt" to it }
        if (extraction.balanceAfter.value == correction.balanceAfter) {
            extraction.balanceAfter.span?.let { candidates += "balanceAfter" to it }
        }
        return nonOverlapping(candidates)
    }
}

/**
 * [generateRule] requires non-overlapping spans, but [GenericExtractor]'s fields are each found by
 * an independent regex search over the whole body (docs/parser.md) - nothing stops two of them
 * matching over the same text for an unusual message shape (observed in production: a `StringIndex
 * OutOfBoundsException` crash from [generateRule] itself, on a real user's confirmation). Rather
 * than crash, keep spans greedily by earliest start and drop any later one that overlaps what's
 * already kept - one less-specific rule beats none.
 */
private fun nonOverlapping(candidates: List<Pair<String, IntRange>>): Map<String, IntRange> {
    val kept = mutableListOf<IntRange>()
    val result = mutableMapOf<String, IntRange>()
    candidates.sortedBy { it.second.first }.forEach { (name, span) ->
        if (kept.none { it.first <= span.last && span.first <= it.last }) {
            kept += span
            result[name] = span
        }
    }
    return result
}

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
