package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.BalanceAnchorDao
import com.amandhakar.ledgerly.database.dao.CardStatementDao
import com.amandhakar.ledgerly.database.dao.ParserRuleDao
import com.amandhakar.ledgerly.database.dao.PayeeAllowlistDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.dao.TransferDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.CardStatement
import com.amandhakar.ledgerly.database.entity.DetectedBy
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.ParserRule
import com.amandhakar.ledgerly.database.entity.ParserTxnType
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.database.entity.Transfer
import com.amandhakar.ledgerly.database.entity.TransferKind
import com.amandhakar.ledgerly.model.money.Paise
import com.amandhakar.ledgerly.parser.GeneratedRule
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.MatchOutcome
import com.amandhakar.ledgerly.parser.ParseClass
import com.amandhakar.ledgerly.parser.ReconciliationResult
import com.amandhakar.ledgerly.parser.StatementAmounts
import com.amandhakar.ledgerly.parser.StatementExtractor
import com.amandhakar.ledgerly.parser.TxnClass
import com.amandhakar.ledgerly.parser.capturedFields
import com.amandhakar.ledgerly.parser.classify
import com.amandhakar.ledgerly.parser.classifyTransaction
import com.amandhakar.ledgerly.parser.extractNewCreditLimit
import com.amandhakar.ledgerly.parser.isBnplSettlementMerchant
import com.amandhakar.ledgerly.parser.isPersonalNumber
import com.amandhakar.ledgerly.parser.isWalletFundingMerchant
import com.amandhakar.ledgerly.parser.matchWithTimeout
import com.amandhakar.ledgerly.parser.normalizeSender
import com.amandhakar.ledgerly.parser.outstandingFromAvailableLimit
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
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
    private val cardStatementDao: CardStatementDao,
    private val transferDao: TransferDao,
    private val refundMatcher: RefundMatcher,
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

        // Task 2.4/2.6: a statement or a BNPL credit-limit-change notice is never a transaction, but
        // both are still real financial data from a trusted sender - they go through the same
        // trust/ledger-start gates as TRANSACTION, just diverge below them instead of Tier 1/2.
        if (parseClass != ParseClass.TRANSACTION && parseClass != ParseClass.STATEMENT && parseClass != ParseClass.CREDIT_LIMIT_CHANGE) {
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

        if (parseClass == ParseClass.STATEMENT) {
            processStatement(sms, institution, sender)
            return
        }

        if (parseClass == ParseClass.CREDIT_LIMIT_CHANGE) {
            processCreditLimitChange(sms, institution, sender)
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
        if (transaction != null) {
            cardPaymentMatcher.tryMatch(transaction)
            maybeLinkBnplSettlement(transaction)
            maybeLinkAtmWithdrawal(transaction, sms)
            refundMatcher.tryMatch(transaction, sms)
        }
        maybeReanchorCreditCardOutstanding(account, extraction, extraction.occurredAt.value ?: sms.receivedAt)
        markTerminal(sms, institution, parseClass, ParseStatus.REVIEW)
    }

    /**
     * Task 2.4/docs/corpus-findings.md §6: a statement is never a transaction - it only sets the
     * card's due amounts and date, and runs the reconciliation check against transactions since
     * the last statement.
     */
    private suspend fun processStatement(sms: RawSms, institution: String, sender: SenderRegistry) {
        val amounts = StatementExtractor.extractAmounts(sms.body)
        val extraction = amounts?.let { GenericExtractor.extract(sms.body, sms.receivedAt) }
        val dueDate = extraction?.let { StatementExtractor.extractDueDate(sms.body, it.occurredAt.value, sms.receivedAt) }
        val account = extraction?.let { resolveAccount(sender, it) }

        if (amounts == null || dueDate == null || account == null) {
            markTerminal(sms, institution, ParseClass.STATEMENT, ParseStatus.REVIEW)
            return
        }

        val statement = writeCardStatement(sms, account, amounts, dueDate)
        if (statement != null) reconcileStatementOutstanding(sms, account, statement)
        markTerminal(sms, institution, ParseClass.STATEMENT, ParseStatus.PARSED)
    }

    private suspend fun writeCardStatement(sms: RawSms, account: Account, amounts: StatementAmounts, dueDate: Long): CardStatement? {
        if (cardStatementDao.getByRawSmsId(sms.id) != null) return null
        val now = System.currentTimeMillis()
        val statement = CardStatement(
            id = UUID.randomUUID().toString(),
            accountId = account.id,
            totalDue = amounts.totalDue,
            minimumDue = amounts.minimumDue,
            dueDate = dueDate,
            statementDate = sms.receivedAt,
            rawSmsId = sms.id,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        cardStatementDao.insert(statement)
        return statement
    }

    /**
     * tasks/phase-2.md's reconciliation check: compare `sum(card transactions since last
     * statement)` against this statement's total - a mismatch means a missed message, interest, or
     * fees, so it creates a flagged [TransactionSource.ADJUSTMENT] entry rather than silently
     * accepting either number. Nothing to compare against for a card's first-ever statement.
     */
    private suspend fun reconcileStatementOutstanding(sms: RawSms, account: Account, statement: CardStatement) {
        val previous = cardStatementDao.getLatestBefore(account.id, statement.statementDate) ?: return
        val signedSum = transactionDao.getSignedSumSinceAnchor(account.id, previous.statementDate, statement.statementDate)
        // signedSum is a change in balance (DEBIT subtracts); outstanding moves the opposite way.
        val expected = previous.totalDue - signedSum
        val diff = statement.totalDue - expected
        if (diff == 0L) return

        val now = System.currentTimeMillis()
        transactionDao.insert(
            Transaction(
                id = UUID.randomUUID().toString(),
                accountId = account.id,
                amount = abs(diff),
                direction = if (diff > 0) EntityDirection.DEBIT else EntityDirection.CREDIT,
                occurredAt = statement.statementDate,
                merchantRaw = "Statement reconciliation adjustment",
                balanceAfter = null,
                rawSmsId = sms.id,
                source = TransactionSource.ADJUSTMENT,
                status = TransactionStatus.PENDING_REVIEW,
                transferId = null,
                isInternal = false,
                notes = "Expected outstanding $expected from transactions since the last statement; this one states ${statement.totalDue}.",
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }

    /**
     * Task 2.6/docs/corpus-findings.md §10: axio's BNPL_LIMIT_CHANGE carries no transaction, only a
     * new `Account.credit_limit` for its BNPL account - resolved the same way as any other
     * axio message, purely via [SenderRegistry.accountId] (axio never carries a last4).
     */
    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    private suspend fun processCreditLimitChange(sms: RawSms, institution: String, sender: SenderRegistry) {
        val newLimit = extractNewCreditLimit(sms.body)
        val account = sender.accountId?.let { accountDao.getById(it) }
        if (newLimit == null || account == null) {
            markTerminal(sms, institution, ParseClass.CREDIT_LIMIT_CHANGE, ParseStatus.REVIEW)
            return
        }
        accountDao.update(account.copy(creditLimit = newLimit, updatedAt = System.currentTimeMillis()))
        markTerminal(sms, institution, ParseClass.CREDIT_LIMIT_CHANGE, ParseStatus.PARSED)
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
        maybeLinkBnplSettlement(transaction)
        maybeLinkAtmWithdrawal(transaction, sms)
        refundMatcher.tryMatch(transaction, sms)
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

    /**
     * Task 2.5/docs/corpus-findings.md §10: a bank debit funding an Amazon Pay wallet top-up is
     * visible only from the bank side - a one-sided transfer, same treatment as an already-confirmed
     * [com.amandhakar.ledgerly.database.entity.PayeeAllowlist] payee, just hardcoded rather than
     * user-confirmed since the merchant string is a specific, known brand.
     *
     * Task 2.8: a refund/reversal credit is marked internal unconditionally, whether or not
     * [RefundMatcher] later finds a spend to link it to - "never as income" (tasks/phase-2.md) does
     * not depend on a match existing.
     */
    private suspend fun isInternalTransfer(merchant: String?, body: String, direction: EntityDirection): Boolean =
        isWalletFundingMerchant(merchant) || isAllowlistedPayee(payeeAllowlistDao, merchant) ||
            (direction == EntityDirection.CREDIT && classifyTransaction(body, ParserDirection.CREDIT) == TxnClass.REVERSAL)

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
            isInternal = isInternalTransfer(extraction.merchant.value, sms.body, direction),
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
        val isInternal = isInternalTransfer(merchant, sms.body, direction)
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

    /**
     * Task 2.6/docs/corpus-findings.md §10: axio settles its bill via a direct bank debit to
     * `CAPITALFLOAT` with no confirmation SMS of its own from axio - unlike [CardPaymentMatcher],
     * which pairs two messages, this is a one-sided [Transfer] (docs/schema.md's nullable
     * `to_txn_id`) from the moment the bank-side debit is written.
     */
    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    private suspend fun maybeLinkBnplSettlement(transaction: Transaction) {
        if (transaction.transferId != null) return
        if (transaction.direction != EntityDirection.DEBIT) return
        if (!isBnplSettlementMerchant(transaction.merchantRaw)) return

        val now = System.currentTimeMillis()
        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            fromTxnId = transaction.id,
            toTxnId = null,
            kind = TransferKind.CARD_PAYMENT,
            detectedBy = DetectedBy.AUTO,
            confidence = 1f,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transferDao.insert(transfer)
        transactionDao.update(transaction.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
    }

    /**
     * Task 2.7/docs/corpus-findings.md §6: an ATM withdrawal moves money from the bank into cash,
     * it does not spend it - the bank-side debit links to a same-amount credit on the single
     * system CASH account (`ensureCashAccount`, "Cash - unallocated") via a two-sided [Transfer],
     * the same shape as [CardPaymentMatcher]'s card-payment pairing, just synthesized from one
     * message instead of matched from two, since no second SMS confirms the cash side.
     */
    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    private suspend fun maybeLinkAtmWithdrawal(transaction: Transaction, sms: RawSms) {
        if (transaction.transferId != null) return
        if (transaction.direction != EntityDirection.DEBIT) return
        if (classifyTransaction(sms.body, ParserDirection.DEBIT) != TxnClass.ATM_WITHDRAWAL) return
        val bankAccount = accountDao.getById(transaction.accountId) ?: return

        val now = System.currentTimeMillis()
        val cashAccount = ensureCashAccount(accountDao, bankAccount.currency, now)
        val cashTransaction = Transaction(
            id = UUID.randomUUID().toString(),
            accountId = cashAccount.id,
            amount = transaction.amount,
            direction = EntityDirection.CREDIT,
            occurredAt = transaction.occurredAt,
            merchantRaw = null,
            balanceAfter = null,
            rawSmsId = transaction.rawSmsId,
            source = transaction.source,
            status = transaction.status,
            transferId = null,
            isInternal = true,
            notes = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transactionDao.insert(cashTransaction)

        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            fromTxnId = transaction.id,
            toTxnId = cashTransaction.id,
            kind = TransferKind.ATM_WITHDRAWAL,
            detectedBy = DetectedBy.AUTO,
            confidence = 1f,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transferDao.insert(transfer)
        transactionDao.update(transaction.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
        transactionDao.update(cashTransaction.copy(transferId = transfer.id, updatedAt = now))

        creditCashAccount(accountDao, balanceAnchorDao, cashAccount, transaction.amount, transaction.occurredAt)
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
    ParseClass.STATEMENT -> EntityParseClass.STATEMENT
    ParseClass.CREDIT_LIMIT_CHANGE -> EntityParseClass.CREDIT_LIMIT_CHANGE
    ParseClass.SI_UPCOMING -> EntityParseClass.SI_UPCOMING
    ParseClass.SI_FAILED -> EntityParseClass.SI_FAILED
    ParseClass.AUTOPAY_SCHEDULED -> EntityParseClass.AUTOPAY_SCHEDULED
    ParseClass.COLLECT_REQUEST -> EntityParseClass.COLLECT_REQUEST
    ParseClass.PROMO -> EntityParseClass.PROMO
    ParseClass.UNKNOWN -> EntityParseClass.UNKNOWN
}
