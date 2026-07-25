package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.PayeeAllowlistDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.model.money.Paise
import com.amandhakar.ledgerly.parser.GeneratedRule
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.MatchOutcome
import com.amandhakar.ledgerly.parser.ParseClass
import com.amandhakar.ledgerly.parser.ReconciliationResult
import com.amandhakar.ledgerly.parser.capturedFields
import com.amandhakar.ledgerly.parser.classify
import com.amandhakar.ledgerly.parser.isPersonalNumber
import com.amandhakar.ledgerly.parser.matchWithTimeout
import com.amandhakar.ledgerly.parser.normalizeSender
import com.amandhakar.ledgerly.parser.outstandingFromAvailableLimit
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import com.amandhakar.ledgerly.database.entity.Direction as EntityDirection
import com.amandhakar.ledgerly.database.entity.ParseClass as EntityParseClass
import com.amandhakar.ledgerly.parser.Direction as ParserDirection

/**
 * docs/parser.md's Flow diagram, wired to real data:
 * ```
 * pre-filter -> sender trust gate -> ledger_start_date gate
 *   -> active ParserRule for institution?
 *        yes -> match by priority desc; match -> reconcile -> CONFIRMED/flagged; no match -> REVIEW
 *        no  -> Tier 2 (generic extractor) -> PENDING_REVIEW
 * ```
 * `is_internal` (Task 1.12) is only ever set here from an *already-confirmed*
 * [com.amandhakar.ledgerly.database.entity.PayeeAllowlist] match — a new allowlist entry is still
 * only ever added by explicit user confirmation from the review inbox, never inferred here.
 */
@Suppress("LongParameterList") // one dependency per DAO/collaborator this pipeline actually needs
class SmsParsingPipeline @Inject constructor(
    private val rawSmsDao: RawSmsDao,
    private val senderRegistryDao: SenderRegistryDao,
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val parserRuleDao: ParserRuleDao,
    private val balanceAnchorDao: BalanceAnchorDao,
    private val payeeAllowlistDao: PayeeAllowlistDao,
    private val transactionReconciler: TransactionReconciler,
    private val ledgerSettingsStore: LedgerSettingsStore,
    private val cardPaymentMatcher: CardPaymentMatcher,
) {
    suspend fun processUnprocessed() {
        rawSmsDao.getByStatus(ParseStatus.UNPROCESSED).forEach { processOne(it) }
    }

    /**
     * Re-runs the pipeline over [institution]'s previously-`IGNORED` archive — e.g. after
     * [SenderClassificationViewModel] flips it from untrusted to trusted, or a non-transaction
     * classification turns out to have been wrong. Messages ignored for other reasons (OTP,
     * declined, pre-ledger-start) just get re-ignored identically; harmless, not free, but this is
     * a rare, user-triggered, personal-inbox-scale operation.
     */
    suspend fun reprocessInstitution(institution: String) {
        rawSmsDao.getByStatus(ParseStatus.IGNORED)
            .filter { normalizeSender(it.sender) == institution }
            .forEach { processOne(it) }
    }

    /**
     * docs/parser.md's backfill: "When a rule is added or changed, re-run over RawSms where
     * parse_status IN (REVIEW, FAILED) for that sender." [applyRule] itself refuses to touch an
     * already-`CONFIRMED` transaction, so this can never clobber a user's own confirmation.
     */
    suspend fun backfillRule(rule: ParserRule) {
        (rawSmsDao.getByStatus(ParseStatus.REVIEW) + rawSmsDao.getByStatus(ParseStatus.FAILED))
            .filter { it.institution == rule.institution }
            .forEach { sms ->
                val sender = senderRegistryDao.getById(sms.sender) ?: return@forEach
                applyRule(sms, rule.institution, sender, rule)
            }
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    private suspend fun processOne(sms: RawSms) {
        val institution = normalizeSender(sms.sender)

        // A personal contact's number is not an institution, no matter what the message body says
        // (classify()'s TRANSACTION default is content-only and can't tell "bank debited Rs. 500"
        // apart from a friend saying "I'll send you 500") — never surface it as a sender to classify.
        if (isPersonalNumber(sms.sender)) {
            markTerminal(sms, institution, ParseClass.UNKNOWN, ParseStatus.IGNORED)
            return
        }

        val parseClass = classify(sms.body)

        if (parseClass != ParseClass.TRANSACTION) {
            markTerminal(sms, institution, parseClass, ParseStatus.IGNORED)
            return
        }

        val sender = ensureSenderRegistered(sms.sender, institution)
        if (!sender.trusted) {
            markTerminal(sms, institution, parseClass, ParseStatus.IGNORED)
            return
        }

        val ledgerStartDate = ledgerSettingsStore.getLedgerStartDate()
        if (ledgerStartDate == null || sms.receivedAt < ledgerStartDate) {
            // Task 1.10: messages before ledger_start_date stay RawSms, never become transactions.
            markTerminal(sms, institution, parseClass, ParseStatus.IGNORED)
            return
        }

        val activeRules = parserRuleDao.getActiveForInstitution(institution)
        if (activeRules.isNotEmpty()) {
            // Tier 2 is a seed, never a fallback: if every active rule fails, this goes to review
            // as-is, it is not re-parsed by the generic extractor (docs/parser.md).
            val matched = activeRules.any { rule -> applyRule(sms, institution, sender, rule) }
            if (!matched) markTerminal(sms, institution, parseClass, ParseStatus.REVIEW)
            return
        }

        processTier2(sms, institution, parseClass, sender)
    }

    private suspend fun processTier2(sms: RawSms, institution: String, parseClass: ParseClass, sender: SenderRegistry) {
        val extraction = GenericExtractor.extract(sms.body, sms.receivedAt)
        val amount = extraction.amount.value
        val direction = extraction.direction.value
        val account = if (amount != null && direction != null) resolveAccount(sender, extraction) else null

        if (amount == null || direction == null || account == null) {
            // Not enough to suggest a transaction at all — the review inbox offers manual entry.
            markTerminal(sms, institution, parseClass, ParseStatus.REVIEW)
            return
        }

        val transaction = writeGenericTransaction(sms, account, extraction, amount, direction.toEntityDirection())
        if (transaction != null) cardPaymentMatcher.tryMatch(transaction)
        maybeReanchorCreditCardOutstanding(account, extraction, extraction.occurredAt.value ?: sms.receivedAt)
        markTerminal(sms, institution, parseClass, ParseStatus.REVIEW)
    }

    /**
     * Tier 1: tries [rule] against [sms]. Returns true iff the pattern matched — a match that
     * reconciliation flags as a [ReconciliationResult.Mismatch] still counts as "the rule matched,"
     * per docs/parser.md ("no match -> PENDING_REVIEW; do NOT fall to generic" is about the regex
     * not matching at all, not about a balance disagreement).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // guard-clause style is clearer than nesting for this validation-heavy path
    private suspend fun applyRule(sms: RawSms, institution: String, sender: SenderRegistry, rule: ParserRule): Boolean {
        val pattern = runCatching { Regex(rule.pattern) }.getOrNull() ?: return false
        val match = matchWithTimeout(pattern, sms.body)
        if (match !is MatchOutcome.Matched) return false

        val existing = transactionDao.getByRawSmsId(sms.id)
        // Already resolved by the user — the pattern still "matched" but must never be touched.
        if (existing?.status == TransactionStatus.CONFIRMED) return true

        val captured = capturedFields(GeneratedRule(rule.pattern, decodeFieldMap(rule.fieldMap)), match.result)
        val amount = captured["amount"]?.let { Paise.fromRupeeString(it)?.value }
        val direction = rule.txnType.toParserDirectionOrNull()
        val now = System.currentTimeMillis()
        parserRuleDao.update(rule.copy(matchCount = rule.matchCount + 1, updatedAt = now))
        if (amount == null || direction == null) return false

        val extraction = GenericExtractor.extract(sms.body, sms.receivedAt)
        val account = resolveAccount(sender, extraction)
        if (account == null) {
            markTerminal(sms, institution, ParseClass.TRANSACTION, ParseStatus.REVIEW, rule.id)
            return true
        }

        val merchant = captured["merchant"] ?: extraction.merchant.value
        val occurredAt = extraction.occurredAt.value ?: sms.receivedAt
        val balanceAfter = captured["balanceAfter"]?.let { Paise.fromRupeeString(it)?.value } ?: extraction.balanceAfter.value

        val reconciliation = balanceAfter?.let { transactionReconciler.reconcile(account.id, occurredAt, amount, direction, it) }
        val status = if (reconciliation is ReconciliationResult.Mismatch) TransactionStatus.PENDING_REVIEW else TransactionStatus.CONFIRMED

        val transaction =
            writeRuleTransaction(sms, existing, account, amount, direction.toEntityDirection(), merchant, occurredAt, balanceAfter, status)
        if (reconciliation is ReconciliationResult.Confirmed) {
            reanchorAccount(accountDao, balanceAnchorDao, account, reconciliation.newBalance, occurredAt)
        }
        cardPaymentMatcher.tryMatch(transaction)
        maybeReanchorCreditCardOutstanding(account, extraction, occurredAt)

        val terminalStatus = if (status == TransactionStatus.CONFIRMED) ParseStatus.PARSED else ParseStatus.REVIEW
        markTerminal(sms, institution, ParseClass.TRANSACTION, terminalStatus, rule.id)
        return true
    }

    private suspend fun ensureSenderRegistered(rawSender: String, institution: String): SenderRegistry {
        senderRegistryDao.getById(rawSender)?.let { return it }
        val now = System.currentTimeMillis()
        val fresh = SenderRegistry(
            senderId = rawSender,
            institution = institution,
            label = institution,
            type = SenderType.UNKNOWN,
            trusted = false,
            accountId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        senderRegistryDao.insert(fresh)
        return fresh
    }

    /** Match on last4 first; a sender's own default account (docs/schema.md) covers formats with no visible number. */
    private suspend fun resolveAccount(sender: SenderRegistry, extraction: GenericExtraction): Account? {
        val last4 = extraction.accountLast4.value
        if (last4 != null) {
            val match = accountDao.observeActive().first().find { it.last4 == last4 }
            if (match != null) return match
        }
        return sender.accountId?.let { accountDao.getById(it) }
    }

    private suspend fun writeGenericTransaction(
        sms: RawSms,
        account: Account,
        extraction: GenericExtraction,
        amount: Long,
        direction: EntityDirection,
    ): Transaction? {
        if (transactionDao.getByRawSmsId(sms.id) != null) return null
        val now = System.currentTimeMillis()
        val transaction = Transaction(
            id = UUID.randomUUID().toString(),
            accountId = account.id,
            amount = amount,
            direction = direction,
            occurredAt = extraction.occurredAt.value ?: sms.receivedAt,
            merchantRaw = extraction.merchant.value,
            balanceAfter = extraction.balanceAfter.value,
            rawSmsId = sms.id,
            source = TransactionSource.SMS_GENERIC,
            status = TransactionStatus.PENDING_REVIEW,
            transferId = null,
            isInternal = isAllowlistedPayee(payeeAllowlistDao, extraction.merchant.value),
            notes = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transactionDao.insert(transaction)
        return transaction
    }

    /** Inserts a fresh Tier-1 transaction, or updates a Tier-2 suggestion the rule now supersedes. */
    @Suppress("LongParameterList") // one field per typed value the rule/reconciliation actually produced
    private suspend fun writeRuleTransaction(
        sms: RawSms,
        existing: Transaction?,
        account: Account,
        amount: Long,
        direction: EntityDirection,
        merchant: String?,
        occurredAt: Long,
        balanceAfter: Long?,
        status: TransactionStatus,
    ): Transaction {
        val now = System.currentTimeMillis()
        val isInternal = isAllowlistedPayee(payeeAllowlistDao, merchant)
        if (existing != null) {
            val updated = existing.copy(
                accountId = account.id,
                amount = amount,
                direction = direction,
                occurredAt = occurredAt,
                merchantRaw = merchant,
                balanceAfter = balanceAfter,
                source = TransactionSource.SMS_RULE,
                status = status,
                isInternal = isInternal,
                updatedAt = now,
            )
            transactionDao.update(updated)
            return updated
        }
        val created = Transaction(
            id = UUID.randomUUID().toString(),
            accountId = account.id,
            amount = amount,
            direction = direction,
            occurredAt = occurredAt,
            merchantRaw = merchant,
            balanceAfter = balanceAfter,
            rawSmsId = sms.id,
            source = TransactionSource.SMS_RULE,
            status = status,
            transferId = null,
            isInternal = isInternal,
            notes = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transactionDao.insert(created)
        return created
    }

    /**
     * Task 2.3: `Avl Limit` (docs/corpus-findings.md §8) reconciles a CREDIT_CARD account's
     * outstanding balance only via its own `credit_limit` - never the bank-account reconciliation
     * path, which expects a literal stated balance. Silently a no-op until the user has entered a
     * credit limit for this card (tasks/phase-2.md: "prompt for it once per card").
     */
    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this validation-heavy path
    private suspend fun maybeReanchorCreditCardOutstanding(account: Account, extraction: GenericExtraction, occurredAt: Long) {
        if (account.type != AccountType.CREDIT_CARD) return
        val creditLimit = account.creditLimit ?: return
        val availableLimit = extraction.availableLimit.value ?: return
        val outstanding = outstandingFromAvailableLimit(creditLimit, availableLimit)
        reanchorAccount(accountDao, balanceAnchorDao, account, -outstanding, occurredAt)
    }

    private suspend fun markTerminal(
        sms: RawSms,
        institution: String,
        parseClass: ParseClass,
        status: ParseStatus,
        matchedRuleId: String? = null,
    ) {
        rawSmsDao.update(
            sms.copy(
                institution = institution,
                parseStatus = status,
                parseClass = parseClass.toEntityParseClass(),
                matchedRuleId = matchedRuleId ?: sms.matchedRuleId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}

private fun ParserTxnType.toParserDirectionOrNull(): ParserDirection? = when (this) {
    ParserTxnType.DEBIT -> ParserDirection.DEBIT
    ParserTxnType.CREDIT -> ParserDirection.CREDIT
    ParserTxnType.CARD_SPEND, ParserTxnType.CARD_PAYMENT, ParserTxnType.STATEMENT, ParserTxnType.TRANSFER -> null
}

private fun ParseClass.toEntityParseClass(): EntityParseClass = when (this) {
    ParseClass.TRANSACTION -> EntityParseClass.TRANSACTION
    ParseClass.OTP -> EntityParseClass.OTP
    ParseClass.DECLINED -> EntityParseClass.DECLINED
    ParseClass.SI_UPCOMING -> EntityParseClass.SI_UPCOMING
    ParseClass.SI_FAILED -> EntityParseClass.SI_FAILED
    ParseClass.AUTOPAY_SCHEDULED -> EntityParseClass.AUTOPAY_SCHEDULED
    ParseClass.COLLECT_REQUEST -> EntityParseClass.COLLECT_REQUEST
    ParseClass.PROMO -> EntityParseClass.PROMO
    ParseClass.UNKNOWN -> EntityParseClass.UNKNOWN
}
