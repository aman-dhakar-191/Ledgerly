package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PayeeNameTest {

    @Test
    fun `name variants from the corpus all normalize to the same value`() {
        val expected = "AMAN DHAKAR"
        assertThat(normalizePayeeName("AMAN DHAKAR")).isEqualTo(expected)
        assertThat(normalizePayeeName("Aman Dhakar")).isEqualTo(expected)
        assertThat(normalizePayeeName("Aman  Dhakar")).isEqualTo(expected)
    }

    @Test
    fun `a resembling surname does not normalize to the same value`() {
        assertThat(normalizePayeeName("KIRAN DHAKER")).isNotEqualTo(normalizePayeeName("AMAN DHAKAR"))
        assertThat(normalizePayeeName("RAHUL DHAKAR")).isNotEqualTo(normalizePayeeName("AMAN DHAKAR"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertThat(normalizePayeeName("  Aman Dhakar  ")).isEqualTo("AMAN DHAKAR")
    }

    @Test
    fun `internal whitespace runs collapse to a single space`() {
        assertThat(normalizePayeeName("Kiran   Dhaker")).isEqualTo("KIRAN DHAKER")
    }
}
