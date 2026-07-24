package com.amandhakar.ledgerly.parser

/**
 * One field pulled from an SMS body. Confidence is per field, not per message — high-confidence
 * fields pre-fill silently in the review inbox, low-confidence ones are highlighted (Task 1.6).
 * [span] is the field's character range in the source body; rule generation (Task 1.7) needs it
 * to turn a user correction into a capture group.
 */
data class ExtractedField<T>(
    val value: T?,
    val confidence: Float,
    val span: IntRange?,
) {
    companion object {
        fun <T> empty(): ExtractedField<T> = ExtractedField(null, 0f, null)
    }
}
