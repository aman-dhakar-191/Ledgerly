package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.dao.AccountDao
import com.amandhakar.ledgerly.database.dao.RawSmsDao
import com.amandhakar.ledgerly.database.dao.TransactionDao
import com.amandhakar.ledgerly.database.dao.TransferDao
import com.amandhakar.ledgerly.database.entity.AccountType
import com.amandhakar.ledgerly.database.entity.DetectedBy
import com.amandhakar.ledgerly.database.entity.Direction
import com.amandhakar.ledgerly.database.entity.Transaction
import com.amandhakar.ledgerly.database.entity.Transfer
import com.amandhakar.ledgerly.database.entity.TransferKind
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.first

private val BILL_PAYMENT_NARRATION = Regex("InfoBIL\\*INFT\\*", RegexOption.IGNORE_CASE)

/**
 * Task 2.2/docs/corpus-findings.md §2a. The bank-side debit's `InfoBIL*INFT*` narration alone
 * covers any bill payment, card or otherwise - it's a same-amount, same-calendar-date credit
 * landing on a CREDIT_CARD account that actually confirms this specific debit as a card payment
 * (observed same-day gaps range from seconds to ~35 minutes, so match on date, not clock time).
 * Unlike [TransferLinker], this never asks the user: the two-sided pattern match on a
 * card-payment-shaped message pair *is* the confirmation, so every link here is [DetectedBy.AUTO].
 */
class CardPaymentMatcher @Inject constructor(
    private val transactionDao: TransactionDao,
    private val rawSmsDao: RawSmsDao,
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
) {
    /** Called right after a transaction is written, whichever side of the pair arrives first. */
    suspend fun tryMatch(transaction: Transaction) {
        if (transaction.transferId != null) return
        val counterpart = findCounterpart(transaction) ?: return
        val (debit, credit) = if (transaction.direction == Direction.DEBIT) {
            transaction to counterpart
        } else {
            counterpart to transaction
        }
        link(debit, credit)
    }

    private suspend fun findCounterpart(transaction: Transaction): Transaction? = when (transaction.direction) {
        Direction.DEBIT -> findCardCredit(transaction)
        Direction.CREDIT -> findBankDebit(transaction)
    }

    private suspend fun findCardCredit(debit: Transaction): Transaction? {
        if (!isBillPaymentDebit(debit)) return null
        val cardAccountIds = accountDao.observeActive().first()
            .filter { it.type == AccountType.CREDIT_CARD }
            .map { it.id }
        return cardAccountIds
            .flatMap { accountId -> transactionsOnDay(accountId, debit.occurredAt) }
            .filter { it.amount == debit.amount && it.direction == Direction.CREDIT && it.transferId == null }
            .minByOrNull { abs(it.occurredAt - debit.occurredAt) }
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this validation-heavy path
    private suspend fun findBankDebit(credit: Transaction): Transaction? {
        val creditAccount = accountDao.getById(credit.accountId) ?: return null
        if (creditAccount.type != AccountType.CREDIT_CARD) return null

        val otherAccountIds = accountDao.observeActive().first()
            .filterNot { it.id == credit.accountId }
            .map { it.id }
        return otherAccountIds
            .flatMap { accountId -> transactionsOnDay(accountId, credit.occurredAt) }
            .filter { it.amount == credit.amount && it.direction == Direction.DEBIT && it.transferId == null }
            .filter { isBillPaymentDebit(it) }
            .minByOrNull { abs(it.occurredAt - credit.occurredAt) }
    }

    private suspend fun transactionsOnDay(accountId: String, epochMillis: Long): List<Transaction> =
        transactionDao.observeByAccountAndDateRange(accountId, dayStart(epochMillis), dayEnd(epochMillis)).first()

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this validation-heavy path
    private suspend fun isBillPaymentDebit(debit: Transaction): Boolean {
        val rawSmsId = debit.rawSmsId ?: return false
        val body = rawSmsDao.getById(rawSmsId)?.body ?: return false
        return BILL_PAYMENT_NARRATION.containsMatchIn(body)
    }

    private suspend fun link(debit: Transaction, credit: Transaction) {
        val now = System.currentTimeMillis()
        val transfer = Transfer(
            id = UUID.randomUUID().toString(),
            fromTxnId = debit.id,
            toTxnId = credit.id,
            kind = TransferKind.CARD_PAYMENT,
            detectedBy = DetectedBy.AUTO,
            confidence = 1f,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        transferDao.insert(transfer)
        transactionDao.update(debit.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
        transactionDao.update(credit.copy(transferId = transfer.id, isInternal = true, updatedAt = now))
    }

    private fun dayStart(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun dayEnd(epochMillis: Long): Long = dayStart(epochMillis) + DAY_MILLIS - 1
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000
