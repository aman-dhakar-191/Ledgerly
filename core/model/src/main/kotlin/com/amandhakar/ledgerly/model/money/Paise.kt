package com.amandhakar.ledgerly.model.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import kotlinx.serialization.Serializable

/**
 * Money, always as an integer count of paise (1 rupee = 100 paise).
 *
 * Never `Double`/`Float`/`BigDecimal` in storage — this is the only
 * representation of money allowed in the ledger. Convert to a display string
 * only at the UI edge via [format].
 */
@JvmInline
@Serializable
value class Paise(val value: Long) : Comparable<Paise> {

    operator fun plus(other: Paise): Paise = Paise(value + other.value)
    operator fun minus(other: Paise): Paise = Paise(value - other.value)
    operator fun unaryMinus(): Paise = Paise(-value)
    operator fun times(factor: Int): Paise = Paise(value * factor)
    operator fun times(factor: Long): Paise = Paise(value * factor)

    override fun compareTo(other: Paise): Int = value.compareTo(other.value)

    val isNegative: Boolean get() = value < 0
    val isZero: Boolean get() = value == 0L

    /** Formats as a display string, e.g. `Paise(123456).format() == "1,234.56"`. */
    fun format(currency: String = "INR"): String {
        val negative = value < 0
        val abs = kotlin.math.abs(value)
        val rupees = abs / 100
        val paise = abs % 100
        val grouped = groupIndianStyle(rupees)
        val symbol = currencySymbol(currency)
        val sign = if (negative) "-" else ""
        return "$sign$symbol$grouped.${paise.toString().padStart(2, '0')}"
    }

    companion object {
        val ZERO = Paise(0)

        private val AMOUNT_PATTERN = Regex(
            """(?i)^\s*(?:rs\.?|inr|₹)?\s*([\d,]+(?:\.\d{1,2})?)\s*(?:rs\.?|inr|₹)?\s*$"""
        )

        /**
         * Parses strings like `"1,234.56"`, `"Rs.500"`, `"INR 42"`, `"₹1,00,000.5"`.
         * Returns null for malformed input rather than throwing — callers route
         * unparseable amounts to review, they never crash the parser.
         */
        fun fromRupeeString(s: String): Paise? {
            val match = AMOUNT_PATTERN.matchEntire(s.trim()) ?: return null
            val numeric = match.groupValues[1].replace(",", "")
            val decimal = try {
                BigDecimal(numeric)
            } catch (e: NumberFormatException) {
                return null
            }
            val paise = decimal.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
            return Paise(paise)
        }

        private fun currencySymbol(currency: String): String = when (currency) {
            "INR" -> "₹"
            else -> try {
                Currency.getInstance(currency).symbol
            } catch (e: IllegalArgumentException) {
                "$currency "
            }
        }

        /** Indian digit grouping: last 3 digits, then groups of 2 (e.g. 1,00,000). */
        private fun groupIndianStyle(rupees: Long): String {
            val digits = rupees.toString()
            if (digits.length <= 3) return digits
            val last3 = digits.takeLast(3)
            val rest = digits.dropLast(3)
            val sb = StringBuilder()
            var i = rest.length
            while (i > 0) {
                val start = maxOf(0, i - 2)
                sb.insert(0, rest.substring(start, i))
                if (start > 0) sb.insert(0, ",")
                i = start
            }
            return "$sb,$last3"
        }
    }
}
