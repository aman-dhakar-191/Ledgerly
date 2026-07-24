package com.amandhakar.ledgerly.ledger

import com.amandhakar.ledgerly.database.entity.GoldenTest

/**
 * Task 1.14: "Export/import as JSON so the corpus survives a reinstall." Every field here is a
 * flat string/number/null, so a small hand-rolled parser is simpler than pulling in a JSON library
 * for this alone (same call [DataExportFormat.kt] makes for Task 1.17's export).
 */
fun buildGoldenTestsJson(goldenTests: List<GoldenTest>): String =
    goldenTests.joinToString(",", prefix = "[", postfix = "]") { goldenTestJson(it) }

private fun goldenTestJson(test: GoldenTest) =
    """{"id":${jsonString(test.id)},"rawBody":${jsonString(test.rawBody)},""" +
        """"expectedJson":${jsonString(test.expectedJson)},"ruleId":${jsonNullableString(test.ruleId)},""" +
        """"createdAt":${test.createdAt},"updatedAt":${test.updatedAt}}"""

private fun jsonString(value: String): String {
    val escaped = buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
    return "\"$escaped\""
}

private fun jsonNullableString(value: String?) = value?.let { jsonString(it) } ?: "null"

/** One imported row; [createdAt]/[updatedAt] carry over as-is, [ruleId] is dropped on import — the
 * rule it referred to lived in the old install and can't be assumed to exist in this one. */
data class ImportedGoldenTest(
    val id: String,
    val rawBody: String,
    val expectedJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class GoldenTestJsonParseException(message: String) : Exception(message)

@Suppress("CyclomaticComplexMethod") // one branch per JSON token type; a hand-rolled parser is inherently this shape
fun parseGoldenTestsJson(json: String): List<ImportedGoldenTest> {
    val parser = MiniJsonParser(json)
    val results = mutableListOf<ImportedGoldenTest>()
    parser.expect('[')
    parser.skipWhitespace()
    if (parser.peek() == ']') {
        parser.expect(']')
        return results
    }
    while (true) {
        results += parseGoldenTestObject(parser)
        parser.skipWhitespace()
        when (parser.peek()) {
            ',' -> { parser.expect(','); parser.skipWhitespace() }
            ']' -> { parser.expect(']'); return results }
            else -> throw GoldenTestJsonParseException("Expected ',' or ']' at ${parser.position}")
        }
    }
}

@Suppress("ThrowsCount") // one throw per required field, so a missing one names itself in the error
private fun parseGoldenTestObject(parser: MiniJsonParser): ImportedGoldenTest {
    val fields = mutableMapOf<String, Any?>()
    parser.expect('{')
    parser.skipWhitespace()
    while (parser.peek() != '}') {
        parser.skipWhitespace()
        val key = parser.parseString()
        parser.skipWhitespace()
        parser.expect(':')
        parser.skipWhitespace()
        fields[key] = parser.parseValue()
        parser.skipWhitespace()
        if (parser.peek() == ',') {
            parser.expect(',')
            parser.skipWhitespace()
        }
    }
    parser.expect('}')
    return ImportedGoldenTest(
        id = fields["id"] as? String ?: throw GoldenTestJsonParseException("Missing 'id'"),
        rawBody = fields["rawBody"] as? String ?: throw GoldenTestJsonParseException("Missing 'rawBody'"),
        expectedJson = fields["expectedJson"] as? String ?: throw GoldenTestJsonParseException("Missing 'expectedJson'"),
        createdAt = (fields["createdAt"] as? Long) ?: throw GoldenTestJsonParseException("Missing 'createdAt'"),
        updatedAt = (fields["updatedAt"] as? Long) ?: throw GoldenTestJsonParseException("Missing 'updatedAt'"),
    )
}

private class MiniJsonParser(private val source: String) {
    var position = 0
        private set

    fun peek(): Char {
        skipWhitespace()
        if (position >= source.length) throw GoldenTestJsonParseException("Unexpected end of input")
        return source[position]
    }

    fun expect(c: Char) {
        skipWhitespace()
        if (position >= source.length || source[position] != c) {
            throw GoldenTestJsonParseException("Expected '$c' at $position")
        }
        position++
    }

    fun skipWhitespace() {
        while (position < source.length && source[position].isWhitespace()) position++
    }

    fun parseValue(): Any? {
        skipWhitespace()
        return when {
            position >= source.length -> throw GoldenTestJsonParseException("Unexpected end of input")
            source[position] == '"' -> parseString()
            source.startsWith("null", position) -> { position += 4; null }
            source.startsWith("true", position) -> { position += 4; true }
            source.startsWith("false", position) -> { position += 5; false }
            else -> parseNumber()
        }
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount") // one branch per JSON escape sequence; inherently this shape
    fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            if (position >= source.length) throw GoldenTestJsonParseException("Unterminated string")
            when (val c = source[position]) {
                '"' -> { position++; return result.toString() }
                '\\' -> {
                    position++
                    if (position >= source.length) throw GoldenTestJsonParseException("Unterminated escape")
                    when (val escaped = source[position]) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        '/' -> result.append('/')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = source.substring(position + 1, position + 5)
                            result.append(hex.toInt(16).toChar())
                            position += 4
                        }
                        else -> throw GoldenTestJsonParseException("Unknown escape '\\$escaped'")
                    }
                    position++
                }
                else -> { result.append(c); position++ }
            }
        }
    }

    private fun parseNumber(): Long {
        val start = position
        if (position < source.length && source[position] == '-') position++
        while (position < source.length && (source[position].isDigit())) position++
        return source.substring(start, position).toLongOrNull()
            ?: throw GoldenTestJsonParseException("Invalid number at $start")
    }
}
