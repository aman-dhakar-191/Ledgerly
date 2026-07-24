package com.amandhakar.ledgerly.parser

private val LEADING_ROUTE_CODE = Regex("^[A-Z]{2}-")
private val TRAILING_TRAFFIC_CLASS = Regex("-[A-Z]$")
private val PERSONAL_NUMBER = Regex("^\\+?\\d{7,15}$")

/**
 * Sender IDs identify the telecom route, not the institution — `AD-ICICIT-S`, `JX-ICICIT-S` and
 * plain `ICICIT` all carry identical ICICI formats (docs/corpus-findings.md §1). Rules and trust
 * key on the resulting institution; `RawSms.sender` keeps the raw value for provenance.
 */
fun normalizeSender(raw: String): String =
    raw.replace(LEADING_ROUTE_CODE, "").replace(TRAILING_TRAFFIC_CLASS, "")

/**
 * Every DLT-registered institutional sender ID is alphanumeric (docs/corpus-findings.md §1's
 * `AD-ICICIT-S` family); a bare digit string is a personal phone number, not an institution, and
 * must never reach the sender-trust classification gate (docs/parser.md).
 */
fun isPersonalNumber(sender: String): Boolean = PERSONAL_NUMBER.matches(sender.trim())
