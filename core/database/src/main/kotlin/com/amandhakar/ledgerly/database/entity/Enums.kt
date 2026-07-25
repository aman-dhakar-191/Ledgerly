package com.amandhakar.ledgerly.database.entity

enum class ParseStatus { UNPROCESSED, PARSED, REVIEW, IGNORED, FAILED }

/**
 * Mirrors [com.amandhakar.ledgerly.parser.ParseClass] — duplicated rather than depending on
 * `:core:parser` from `:core:database`, same as [Direction] below; the pipeline in `:app` converts
 * between the two at the seam (docs/schema.md's `raw_sms.parse_class`).
 */
enum class ParseClass {
    TRANSACTION,
    OTP,
    DECLINED,
    SI_UPCOMING,
    SI_FAILED,
    AUTOPAY_SCHEDULED,
    COLLECT_REQUEST,
    PROMO,
    UNKNOWN,
}

enum class SenderType { BANK, CARD, OTP, PROMO, SPAM, NOT_FINANCIAL, UNKNOWN }

enum class ParserTxnType { DEBIT, CREDIT, CARD_SPEND, CARD_PAYMENT, STATEMENT, TRANSFER }

enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, CASH, WALLET, LOAN }

enum class Direction { DEBIT, CREDIT }

enum class TransactionSource { SMS_RULE, SMS_GENERIC, MANUAL, IMPORT }

enum class TransactionStatus { CONFIRMED, PENDING_REVIEW, REJECTED }

enum class AuditReason { USER_EDIT, RULE_BACKFILL, RECONCILE_FIX }

/** docs/schema.md: opening balances and later drift corrections are the same operation. */
enum class BalanceAnchorSource { OPENING, USER_CORRECTION, SMS_DERIVED }
