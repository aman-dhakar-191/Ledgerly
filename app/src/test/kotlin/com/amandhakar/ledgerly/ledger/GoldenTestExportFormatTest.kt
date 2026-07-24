package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.entity.GoldenTest
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Task 1.14: pure JSON building/parsing, no Android/Robolectric needed to exercise it. */
class GoldenTestExportFormatTest {

    @Test
    fun `round trips a golden test through export and import`() {
        val test = GoldenTest(
            id = "gt-1",
            rawBody = "ICICI Bank Acc XXXX debited Rs. 500.00 on 09-Jun-26",
            expectedJson = """{"amount":50000,"direction":"DEBIT","merchant":null,"occurredAt":123,"balanceAfter":null}""",
            ruleId = "rule-1",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            deletedAt = null,
        )

        val json = buildGoldenTestsJson(listOf(test))
        val imported = parseGoldenTestsJson(json)

        assertThat(imported).hasSize(1)
        assertThat(imported.single().id).isEqualTo(test.id)
        assertThat(imported.single().rawBody).isEqualTo(test.rawBody)
        assertThat(imported.single().expectedJson).isEqualTo(test.expectedJson)
        assertThat(imported.single().createdAt).isEqualTo(test.createdAt)
        assertThat(imported.single().updatedAt).isEqualTo(test.updatedAt)
    }

    @Test
    fun `round trips a null ruleId and special characters in the body`() {
        val test = GoldenTest(
            id = "gt-2",
            rawBody = "Line one\nLine \"two\" with a \\backslash\\ and a tab\t.",
            expectedJson = """{"amount":100,"direction":"CREDIT","merchant":"A & B","occurredAt":1,"balanceAfter":1}""",
            ruleId = null,
            createdAt = 1L,
            updatedAt = 2L,
            deletedAt = null,
        )

        val json = buildGoldenTestsJson(listOf(test))
        val imported = parseGoldenTestsJson(json)

        assertThat(imported.single().rawBody).isEqualTo(test.rawBody)
        assertThat(imported.single().expectedJson).isEqualTo(test.expectedJson)
    }

    @Test
    fun `parses an empty export`() {
        assertThat(parseGoldenTestsJson(buildGoldenTestsJson(emptyList()))).isEmpty()
    }

    @Test
    fun `rejects malformed json`() {
        assertThrows(GoldenTestJsonParseException::class.java) { parseGoldenTestsJson("not json") }
    }
}
