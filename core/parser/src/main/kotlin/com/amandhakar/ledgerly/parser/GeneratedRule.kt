package com.amandhakar.ledgerly.parser

/**
 * A learned, sender-agnostic-*within-one-institution* rule (docs/parser.md's "Rule generation"
 * section): [pattern] is a regex over the raw SMS body; [fieldMap] says which capture group (1
 * onward) holds which field. Persisting this alongside `institution`/`priority`/etc. is
 * `ParserRule` in `:core:database` — this is just the two fields that are actually derived from
 * text, kept here so the derivation is covered by real tests.
 */
data class GeneratedRule(val pattern: String, val fieldMap: Map<String, Int>)

private val DIGITS_ONLY = Regex("^[\\d,]+\\.?\\d*$")
private const val NUMERIC_GENERALIZATION = """[\d,]+\.?\d*"""
private const val DATE_GENERALIZATION = """\d{1,2}[-/]?\w{1,4}[-/]?\d{2,4}"""

/**
 * Turns a user-corrected extraction into a rule: escape everything except the confirmed field
 * spans literally, replace each span with a capture group generalised by its own shape, per
 * docs/parser.md's "Rule generation" section — a digit run generalises to a numeric pattern, an
 * [occurredAt] span to a loose date pattern, and `merchant` to a lazy wildcard bounded by its
 * literal neighbours. Anything else (reference numbers, mostly) is kept as its own escaped literal
 * text rather than generalised at all, since the doc doesn't specify a shape for it and literal is
 * the over-specific-not-over-general default: one extra review if it varies next time beats a
 * pattern loose enough to corrupt the ledger.
 *
 * [confirmedFields] spans must not overlap and must all lie within [body]'s bounds — the caller
 * (the review inbox, Task 1.13) is the one turning UI selections into these, so it already knows
 * this from the [GenericExtraction] spans it started from.
 */
fun generateRule(body: String, confirmedFields: Map<String, IntRange>): GeneratedRule {
    val orderedFields = confirmedFields.entries.sortedBy { it.value.first }
    val pattern = StringBuilder()
    val fieldMap = mutableMapOf<String, Int>()
    var cursor = 0

    orderedFields.forEachIndexed { index, (fieldName, span) ->
        pattern.append(Regex.escape(body.substring(cursor, span.first)))
        val fieldText = body.substring(span.first, span.last + 1)
        pattern.append('(').append(generalizedPattern(fieldName, fieldText)).append(')')
        fieldMap[fieldName] = index + 1
        cursor = span.last + 1
    }
    pattern.append(Regex.escape(body.substring(cursor)))

    return GeneratedRule(pattern.toString(), fieldMap)
}

private fun generalizedPattern(fieldName: String, fieldText: String): String = when {
    DIGITS_ONLY.matches(fieldText) -> NUMERIC_GENERALIZATION
    fieldName == "occurredAt" -> DATE_GENERALIZATION
    fieldName == "merchant" -> """.+?"""
    else -> Regex.escape(fieldText)
}
