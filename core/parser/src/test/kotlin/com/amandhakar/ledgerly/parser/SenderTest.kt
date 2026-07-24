package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SenderTest {

    @ParameterizedTest
    @CsvSource(
        "AD-ICICIT-S, ICICIT",
        "JX-ICICIT-S, ICICIT",
        "VM-ICICIT-S, ICICIT",
        "JX-ICICIT, ICICIT",
        "ICICIT, ICICIT",
        "VA-EPFOHO-G, EPFOHO",
        "BZ-EPFOHO, EPFOHO",
        "AD-EPFOHO-S, EPFOHO",
    )
    fun `sender IDs collapse to their institution`(raw: String, expected: String) {
        assertThat(normalizeSender(raw)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "9876543210, true",
        "+919876543210, true",
        "919876543210, true",
        "AD-ICICIT-S, false",
        "ICICIT, false",
        "56070, false",
        "VM-HDFCBK, false",
    )
    fun `only a bare digit string is a personal number`(sender: String, expected: Boolean) {
        assertThat(isPersonalNumber(sender)).isEqualTo(expected)
    }
}
