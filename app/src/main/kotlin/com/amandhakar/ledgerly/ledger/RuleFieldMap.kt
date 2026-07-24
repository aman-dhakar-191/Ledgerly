package com.amandhakar.ledgerly.ledger

/**
 * `ParserRule.fieldMap`'s JSON codec: a flat `{"fieldName":groupIndex,...}` object. Hand-rolled
 * rather than pulling `kotlinx.serialization` into `:app` for what's always a handful of simple
 * string->int pairs with plain identifier keys (see [DataExportFormat.kt] for the same call on the
 * export side).
 */
private val FIELD_MAP_ENTRY = Regex(""""(\w+)":(\d+)""")

fun encodeFieldMap(fieldMap: Map<String, Int>): String =
    fieldMap.entries.joinToString(prefix = "{", postfix = "}") { (name, group) -> "\"$name\":$group" }

fun decodeFieldMap(json: String): Map<String, Int> =
    FIELD_MAP_ENTRY.findAll(json).associate { it.groupValues[1] to it.groupValues[2].toInt() }
