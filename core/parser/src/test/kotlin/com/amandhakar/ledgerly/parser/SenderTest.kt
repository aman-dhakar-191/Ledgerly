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
}
