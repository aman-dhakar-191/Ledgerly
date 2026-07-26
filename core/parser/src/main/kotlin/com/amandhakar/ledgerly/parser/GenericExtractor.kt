package com.amandhakar.ledgerly.parser

import com.amandhakar.ledgerly.model.money.Paise
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tier 2 of the parser ladder (docs/parser.md): a generic, sender-agnostic extractor. Only ever
 * runs for a sender with no active [ParserRule] yet, and its output is always a suggestion — never
 * a direct ledger write.
 */
object GenericExtractor {

    private val CURRENCY_AMOUNT = Regex(
        """(?i)(rs\.?|inr|usd|₹)\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})(\s*/-)?"""
    )
    private val BARE_VERB_AMOUNT = Regex(
        """(?i)(?:debited|credited)\s+(?:by|for|with)?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})"""
    )
    private val LIMIT_GUARD = Regex("(?i)limit")
    /**
     * Known trailing disclaimer/customer-service footers - ICICI's "To dispute call ... or SMS
     * BLOCK ### to ##########" and axio's "To report misuse call ##########" - never a merchant,
     * but the "\bto\s+" merchant anchor below would otherwise capture one whole when the preceding
     * sentence ends with no space before it.
     *
     * ICICI's UPI-debit format actually reads "Call ##### for dispute. SMS BLOCK ### to
     * ##########" - reversed word order from "to dispute call" above, and with its own separate
     * "SMS BLOCK ... to <phone>" clause - so both the reversed phrasing and "SMS BLOCK" itself need
     * their own entries, or the "\bto\s+" anchor greedily captures the trailing phone number as the
     * merchant on every single one of these (a very high-volume real-world format).
     */
    private val DISCLAIMER_FOOTERS = listOf(
        Regex("(?i)to dispute call\\b.*"),
        Regex("(?i)to report misuse call\\b.*"),
        Regex("(?i)call\\s+\\d+\\s+for\\s+dispute\\b.*"),
        Regex("(?i)\\bsms\\s+block\\b.*"),
    )
    private val BALANCE_LABEL = Regex(
        """(?i)(avl\s*bal|avb\s*bal|avbl\s*bal|available\s*balance|updated\s*balance\s*is|""" +
            """updated\s*balance\s*:|\bbal\b)\.?\s*[:.]?\s*(?:rs\.?|inr|₹)?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})"""
    )
    /** Never a balance (docs/corpus-findings.md §8's CARD_SPEND_LIMIT) - a card's available limit. */
    private val AVAILABLE_LIMIT_LABEL = Regex(
        """(?i)avl\s*limit\.?\s*[:.]?\s*(?:rs\.?|inr|₹)?\s*(\d[\d,]*(?:\.\d{1,2})?|\.\d{1,2})"""
    )

    // SBI's "has a debit/credit by <method> of Rs X" (NACH, transfer, Cheque) uses the noun form,
    // not the verbs below - "debit"/"credit" alone, never "debited"/"credited".
    private val DEBIT_VERBS = Regex("(?i)\\b(debited|withdrawn|spent|paid|sent)\\b|has a debit by\\b")
    private val CREDIT_VERBS = Regex("(?i)\\b(credited|received|deposited|refund)\\b|has a credit by\\b")
    /**
     * docs/corpus-findings.md §10's wallet payment format ("Payment of Rs X using Apay Balance
     * successful ...") carries none of [DEBIT_VERBS]'s words - only checked when neither list
     * matches, so a "successful" appearing alongside a real credit/refund verb never overrides it.
     */
    private val PAYMENT_SUCCESSFUL = Regex("(?i)payment of.*?\\bsuccessful\\b")
    /**
     * docs/corpus-findings.md §10's axio BNPL spend formats ("Thank you for availing Pay Later
     * credit of Rs{amt}" / "Thanks for availing Rs{amt} Pay Later credit") - despite the word
     * "credit", this is money the user just spent (a draw against their Pay Later line), a DEBIT,
     * and carries none of [DEBIT_VERBS]'s words either.
     */
    private val BNPL_SPEND = Regex("(?i)\\bavailing\\b.*\\bpay later\\b")
    /**
     * A recurring Standing Instruction confirmation ("We have successfully processed payment of
     * INR X to Merchant Y, as per Standing Instruction...") is always the cardholder's own spend
     * (a DEBIT), but carries none of [DEBIT_VERBS]'s words - "processed" isn't one of them, and
     * [PAYMENT_SUCCESSFUL] requires "successful" after "payment of", not "successfully" before it.
     * Also [ParseClass]'s own `TRANSACTION_OVERRIDE` keys on this exact phrase.
     */
    private val SI_PAYMENT_PROCESSED = Regex("(?i)successfully processed(?:\\s+the)?\\s+payment\\s+of")
    /**
     * SBI's debit-card POS confirmation ("transaction number X for Rs.Y by SBI Debit Card XNNNN
     * done at Z on DATE...") never uses "debited"/"spent" either - "done at" alone would be too
     * broad to trust as a signal on its own, so this requires "debit card" earlier in the body too.
     */
    private val CARD_POS_DONE_AT = Regex("(?i)debit card\\b.*\\bdone at\\b")

    private val ACCOUNT_ANCHOR = Regex(
        """(?i)(?:a/c|acct|acc|account|card)s?\.?\s*(?:no\.?\s*)?([Xx*0-9]{3,})"""
    )
    private val ACCOUNT_ENDING_WITH = Regex("""(?i)ending with\s+(\d{3,})""")
    private val TRAILING_DIGITS = Regex("""\d+$""")

    private val MERCHANT_ANCHORS = listOf(
        /**
         * docs/corpus-findings.md's ACCT_DEBIT_VIN (`VIN*{merchant}`) and CARD_SPEND_LIMIT's card
         * network merchant descriptor (`MSW*TRAVEL RETA`, `ANTHROPIC* CLAU`, `VSI*MICROSOFT`) - a
         * documented format ("Yes" for carrying a merchant) that nonetheless extracted nothing:
         * every other anchor's character class omits `*`, so none of them can even reach past it.
         * Placed first since a literal `*` essentially never appears in any other format's merchant
         * text, so this can never wrongly pre-empt a more specific anchor below.
         *
         * Excludes ICICI's own `InfoBIL*INFT*` (a bill-payment reference code, not a merchant -
         * ACCT_DEBIT_BILL carries no `{merchant}` per docs) and `NFS*` (ATM withdrawal boilerplate,
         * `ACCT_DEBIT_ATM` likewise carries none). Both are `CODE*CODE*...` shaped - two `*`s, not
         * one - so the `(?<!\*)` lookbehind stops this from also matching the *second* segment
         * ("INFT*FGR6") as if it were its own independent merchant-code match; `NFS*CASH WDL*` still
         * has a *second* trailing `*` right before the terminating period, and unlike every other
         * anchor's char class, this one deliberately excludes `.`/`,` - a real network merchant name
         * never contains either, and including them would let the lazy capture jump straight over
         * that second `*` and the period right after it, swallowing the rest of the message.
         */
        Regex("""(?i)(?<!\*)\b(?!InfoBIL\*)(?!NFS\*)([A-Za-z0-9]+\*\s?[A-Za-z0-9 &'_]+?)(?=\.|;|$)"""),
        Regex("""(?i)for UPI-[^-\s]+-([A-Za-z0-9 .,&'_]+?)(?=\.|;|$)"""),
        /**
         * Task 2.8/docs/corpus-findings.md §6's REFUND format ("AMAZON refund of Rs 367.09
         * credited to ICICI Bank Credit Card XX6001...") - must come before the generic `\bto\s+`
         * anchor below, which would otherwise capture "ICICI Bank Credit Card XX6001" (from
         * "credited to ICICI...") instead of the actual merchant leading the message.
         */
        Regex("""(?i)\b([A-Za-z0-9 .,&'_]+?)\s+refund\s+of\s+(?:rs\.?|inr|₹)"""),
        Regex("""(?i)\bmerchant\s+([A-Za-z0-9 .,&'_]+?)(?=,|\.|;|\s+as\s|\s+on\s|$)"""),
        // Standing Instruction confirmation's other phrasing, without the "Merchant" keyword:
        // "...processed the payment of INR 299.00 for Amazon, as per the Standing Instruction..."
        Regex("""(?i)\bfor\s+([A-Za-z0-9 .,&'_]+?),\s+as\s+per\s+the\s+standing\s+instruction"""),
        // SBI NEFT-credit's payer name ("...UTR 38113634161DC by CONSCENDO TECHNOLOGI, INFO: Salary
        // Oct 24-SBI") and its UPI-credit's payer name ("...transfer from TARSON TOKBI Ref No
        // 101947667588 -SBI") both need their own anchors: the generic "\bby\s+"/"\bfrom\s+" anchors
        // below can't reach them because SBI's messages end in a bare "-SBI" suffix with no
        // preceding period/comma, so the generic anchors' lazy capture (which is allowed to consume
        // "." and "," itself) can never find a satisfiable stopping point and the whole match fails.
        Regex("""(?i)\bby\s+([A-Za-z0-9 .,&'_]+?)(?=,\s*info\b)"""),
        Regex("""(?i)\btransfer from\s+([A-Za-z0-9 .,&'_]+?)\s+ref\s*no\b"""),
        Regex("""(?i)\btrf to\s+([A-Za-z0-9 .,&'_]+?)(?=\s+refno|\.|;|$)"""),
        Regex("""(?i)\bon\s+\d{1,2}[-/]\w+[-/]\d{2,4}\s+on\s+([A-Za-z0-9 .,&'_]+?)(?=\.|;|$)"""),
        Regex("""(?i)\btowards\s+([A-Za-z0-9 .,&'_]+?)(?=\s+on\s|,|\.|;|$)"""),
        Regex("""(?i)\bat\s+([A-Za-z0-9 .,&'_]+?)(?=\s+on\s|\.|;|$)"""),
        Regex("""(?i)\bto\s+([A-Za-z0-9 .,&'_]+?)(?=\s+on\s|\.|;|$)"""),
        Regex("""(?i)\bfrom\s+([A-Za-z0-9 .,&'_]+?)(?=\.|;|,|$)"""),
        Regex("""(?i)([A-Za-z][A-Za-z .]+?)\s+credited\b"""),
    )

    // SBI's "Ref No 101947667588" has a space between "Ref" and "No" - "\bref(?:no)?\b" alone only
    // ever matches the bare word "Ref" there (the "no" branch requires zero-width adjacency), so
    // the capture group grabs the literal word "No" instead of the actual reference number after it.
    private val REFERENCE_ANCHOR = Regex(
        """(?i)(?:upi[-:]|imps ref no|mandate id|txn\s*id|\brrn\b|\butr\b|reference number is|""" +
            """\bref(?:\s*no)?\b)\s*[:.]?\s*([A-Za-z0-9]+)"""
    )

    private val DATE_PATTERN = Regex("""(\d{1,2})[-/]?([A-Za-z]{3}|\d{1,2})[-/]?(\d{2}|\d{4})""")
    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    fun extract(body: String, receivedAt: Long): GenericExtraction {
        val balance = extractBalance(body)
        val amount = extractAmount(body, excluding = balance?.span)
        val availableLimit = extractAvailableLimit(body)
        return GenericExtraction(
            amount = amount?.let { ExtractedField(it.paise, 1f, it.span) } ?: ExtractedField.empty(),
            currency = amount?.let { ExtractedField(it.currency, 1f, it.span) } ?: ExtractedField.empty(),
            direction = extractDirection(body),
            accountLast4 = extractAccountLast4(body),
            balanceAfter = balance?.let { ExtractedField(it.paise, 1f, it.span) } ?: ExtractedField.empty(),
            merchant = extractMerchant(body),
            occurredAt = extractOccurredAt(body, receivedAt),
            reference = extractReference(body),
            availableLimit = availableLimit?.let { ExtractedField(it.paise, 1f, it.span) } ?: ExtractedField.empty(),
        )
    }

    private data class AmountMatch(val paise: Long, val currency: String, val span: IntRange)

    private fun toAmountMatch(text: String, currencyToken: String?, range: IntRange): AmountMatch? {
        val paise = Paise.fromRupeeString(text)?.value ?: return null
        val currency = when (currencyToken?.lowercase()) {
            "usd" -> "USD"
            else -> "INR"
        }
        return AmountMatch(paise, currency, range)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun extractBalance(body: String): AmountMatch? {
        val match = BALANCE_LABEL.find(body) ?: return null
        val numberGroup = match.groups[2] ?: return null
        return toAmountMatch(numberGroup.value, currencyToken = null, range = numberGroup.range)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun extractAmount(body: String, excluding: IntRange?): AmountMatch? {
        val candidates = CURRENCY_AMOUNT.findAll(body)
            .filterNot { isLimitClause(body, it) }
            .filterNot { excluding != null && it.range.first in excluding }
            .mapNotNull { m ->
                val numberGroup = m.groups[2] ?: return@mapNotNull null
                val text = (m.groups[1]?.value.orEmpty()) + numberGroup.value + (m.groups[3]?.value.orEmpty())
                // The span must be just the digits, not the whole match (including the "Rs."
                // prefix) - Task 1.7's generateRule only generalises a span into a numeric capture
                // group when its text is digits-only; a span that drags in the currency prefix
                // silently falls back to an escaped literal, baking one specific amount into the
                // rule forever instead of a pattern that matches any amount.
                toAmountMatch(text, m.groups[1]?.value, numberGroup.range)
            }
            .toList()
        if (candidates.isNotEmpty()) return candidates.first()

        val bareMatch = BARE_VERB_AMOUNT.find(body) ?: return null
        val numberGroup = bareMatch.groups[1] ?: return null
        return toAmountMatch(numberGroup.value, currencyToken = null, range = numberGroup.range)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun extractAvailableLimit(body: String): AmountMatch? {
        val match = AVAILABLE_LIMIT_LABEL.find(body) ?: return null
        val numberGroup = match.groups[1] ?: return null
        return toAmountMatch(numberGroup.value, currencyToken = null, range = numberGroup.range)
    }

    private fun isLimitClause(body: String, match: MatchResult): Boolean {
        val windowStart = maxOf(0, match.range.first - 15)
        return LIMIT_GUARD.containsMatchIn(body.substring(windowStart, match.range.first))
    }

    private fun extractDirection(body: String): ExtractedField<Direction> {
        val debit = DEBIT_VERBS.find(body)
        val credit = CREDIT_VERBS.find(body)
        val winner = listOfNotNull(
            debit?.let { it to Direction.DEBIT },
            credit?.let { it to Direction.CREDIT },
        ).minByOrNull { it.first.range.first }
            ?: PAYMENT_SUCCESSFUL.find(body)?.let { it to Direction.DEBIT }
            ?: BNPL_SPEND.find(body)?.let { it to Direction.DEBIT }
            ?: SI_PAYMENT_PROCESSED.find(body)?.let { it to Direction.DEBIT }
            ?: CARD_POS_DONE_AT.find(body)?.let { it to Direction.DEBIT }
            ?: return ExtractedField.empty()
        return ExtractedField(winner.second, 1f, winner.first.range)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun extractAccountLast4(body: String): ExtractedField<String> {
        val match = ACCOUNT_ANCHOR.find(body) ?: ACCOUNT_ENDING_WITH.find(body)
            ?: return ExtractedField.empty()
        val token = match.groups[1] ?: return ExtractedField.empty()
        val digits = TRAILING_DIGITS.find(token.value)?.value ?: return ExtractedField.empty()
        return ExtractedField(digits.takeLast(4), 1f, token.range)
    }

    private fun extractMerchant(body: String): ExtractedField<String> {
        val footerStart = DISCLAIMER_FOOTERS.mapNotNull { it.find(body)?.range?.first }.minOrNull()
        val found = MERCHANT_ANCHORS.firstNotNullOfOrNull { anchor -> matchMerchant(body, anchor, footerStart) }
        return found ?: ExtractedField.empty()
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun matchMerchant(body: String, anchor: Regex, footerStart: Int?): ExtractedField<String>? {
        val group = anchor.find(body)?.groups?.get(1) ?: return null
        if (footerStart != null && group.range.first >= footerStart) return null
        val cleaned = group.value.trim()
        // A reference number, phone number or UPI ID can land in a merchant anchor's capture group
        // (e.g. the "\bto\s+" anchor on a phone number in an unrecognised disclaimer footer) - a
        // real merchant name always has at least one letter, so this is a cheap, general backstop
        // independent of any one footer pattern being complete.
        if (cleaned.none { it.isLetter() }) return null
        return cleaned.takeIf { it.isNotEmpty() }?.let { ExtractedField(it, 0.6f, group.range) }
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun extractReference(body: String): ExtractedField<String> {
        val match = REFERENCE_ANCHOR.find(body) ?: return ExtractedField.empty()
        val group = match.groups[1] ?: return ExtractedField.empty()
        return ExtractedField(group.value, 1f, group.range)
    }

    private fun extractOccurredAt(body: String, receivedAt: Long): ExtractedField<Long> {
        for (match in DATE_PATTERN.findAll(body)) {
            val epochMillis = parseDate(match) ?: continue
            return ExtractedField(epochMillis, 1f, match.range)
        }
        return ExtractedField(receivedAt, 0.5f, null)
    }

    @Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
    private fun parseDate(match: MatchResult): Long? {
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val monthToken = match.groupValues[2]
        val month = monthToken.toIntOrNull() ?: (MONTHS.indexOf(monthToken.lowercase().take(3)) + 1)
        val yearToken = match.groupValues[3]
        val year = if (yearToken.length == 2) 2000 + yearToken.toInt() else yearToken.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching {
            LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
