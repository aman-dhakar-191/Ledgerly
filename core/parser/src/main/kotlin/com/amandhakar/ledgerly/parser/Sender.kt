package com.amandhakar.ledgerly.parser

private val LEADING_ROUTE_CODE = Regex("^[A-Z]{2}-")
private val TRAILING_TRAFFIC_CLASS = Regex("-[A-Z]$")

/**
 * Sender IDs identify the telecom route, not the institution — `AD-ICICIT-S`, `JX-ICICIT-S` and
 * plain `ICICIT` all carry identical ICICI formats (docs/corpus-findings.md §1). Rules and trust
 * key on the resulting institution; `RawSms.sender` keeps the raw value for provenance.
 */
fun normalizeSender(raw: String): String =
    raw.replace(LEADING_ROUTE_CODE, "").replace(TRAILING_TRAFFIC_CLASS, "")
