package com.amandhakar.ledgerly.model.money

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class PaiseTest {

    @ParameterizedTest
    @CsvSource(
        "'1,234.56', 123456",
        "'Rs.500', 50000",
        "'Rs. 500', 50000",
        "'INR 42', 4200",
        "'₹1,00,000.5', 10000050",
        "'100', 10000",
        "'0.99', 99",
        "'  250.00  ', 25000",
        "'500 Rs.', 50000",
        "'1234.5', 123450",
    )
    fun `parses valid rupee strings`(input: String, expectedPaise: Long) {
        assertThat(Paise.fromRupeeString(input)).isEqualTo(Paise(expectedPaise))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "abc",
            "Rs.",
            "12.345",
            "--100",
            "1234.",
            "Rs 100 extra text",
        ]
    )
    fun `rejects malformed input by returning null`(input: String) {
        assertThat(Paise.fromRupeeString(input)).isNull()
    }

    @Test
    fun `arithmetic operators`() {
        val a = Paise(10_000)
        val b = Paise(2_500)
        assertThat(a + b).isEqualTo(Paise(12_500))
        assertThat(a - b).isEqualTo(Paise(7_500))
        assertThat(-a).isEqualTo(Paise(-10_000))
        assertThat(a * 3).isEqualTo(Paise(30_000))
    }

    @Test
    fun `comparison and sign helpers`() {
        assertThat(Paise(100) > Paise(50)).isTrue()
        assertThat(Paise(-1).isNegative).isTrue()
        assertThat(Paise(0).isZero).isTrue()
        assertThat(Paise(1).isZero).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "123456, '₹1,234.56'",
        "50000, '₹500.00'",
        "99, '₹0.99'",
        "-123456, '-₹1,234.56'",
        "10000050, '₹1,00,000.50'",
        "0, '₹0.00'",
    )
    fun `formats for display with Indian grouping`(paise: Long, expected: String) {
        assertThat(Paise(paise).format()).isEqualTo(expected)
    }

    @Test
    fun `round trip through fromRupeeString and format preserves the amount`() {
        val parsed = Paise.fromRupeeString("1,23,456.78")!!
        assertThat(parsed.value).isEqualTo(12345678L)
        assertThat(parsed.format()).isEqualTo("₹1,23,456.78")
    }
}
