package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.SenderRegistryDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.entity.Account
import com.amandhakar.ledgerly.database.entity.ParseStatus
import com.amandhakar.ledgerly.database.entity.RawSms
import com.amandhakar.ledgerly.database.entity.SenderRegistry
import com.amandhakar.ledgerly.database.entity.SenderType
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.TransactionSource
import com.amandhakar.ledgerly.database.entity.TransactionStatus
import com.amandhakar.ledgerly.parser.GenericExtraction
import com.amandhakar.ledgerly.parser.GenericExtractor
import com.amandhakar.ledgerly.parser.ParseClass
import com.amandhakar.ledgerly.parser.classify
import com.amandhakar.ledgerly.parser.normalizeSender
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import com.amandhakar.ledgerly.database.entity.Direction as EntityDirection
import com.amandhakar.ledgerly.database.entity.ParseClass as EntityParseClass

/**
 * docs/parser.md's Flow diagram, wired to real data. Tier 1 (learned [com.amandhakar.ledgerly.database.entity.ParserRule]
 * matching) isn't wired yet — a fresh install has no rules, so every message that reaches here
 * today goes through Tier 2 (the generic extractor) and lands in the review inbox (Task 1.13) for
 * the user to confirm, which is what generates the first rule (Task 1.7's `generateRule`).
 *
 * `is_internal` (Task 1.12) is never set here: the allowlist is only ever populated by explicit
 * user confirmation from the review inbox, never inferred at ingestion.
 */
class SmsParsingPipeline @Inject constructor(
    private val rawSmsDao: RawSmsDao,
    private val senderRegistryDao: SenderRegistryDao,
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val ledgerSettingsStore: LedgerSettingsStore,
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

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this pipeline
    private suspend fun processOne(sms: RawSms) {
        val institution = normalizeSender(sms.sender)
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

        val extraction = GenericExtractor.extract(sms.body, sms.receivedAt)
        val amount = extraction.amount.value
        val direction = extraction.direction.value
        val account = if (amount != null && direction != null) resolveAccount(sender, extraction) else null

        if (amount == null || direction == null || account == null) {
            // Not enough to suggest a transaction at all — the review inbox offers manual entry.
            markTerminal(sms, institution, parseClass, ParseStatus.REVIEW)
            return
        }

        writeTransaction(sms, account, extraction, amount, direction.toEntityDirection())
        markTerminal(sms, institution, parseClass, ParseStatus.REVIEW)
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

    private suspend fun writeTransaction(
        sms: RawSms,
        account: Account,
        extraction: GenericExtraction,
        amount: Long,
        direction: EntityDirection,
    ) {
        if (transactionDao.getByRawSmsId(sms.id) != null) return
        val now = System.currentTimeMillis()
        transactionDao.insert(
            Transaction(
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
                isInternal = false,
                notes = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            ),
        )
    }

    private suspend fun markTerminal(sms: RawSms, institution: String, parseClass: ParseClass, status: ParseStatus) {
        rawSmsDao.update(
            sms.copy(
                institution = institution,
                parseStatus = status,
                parseClass = parseClass.toEntityParseClass(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
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
