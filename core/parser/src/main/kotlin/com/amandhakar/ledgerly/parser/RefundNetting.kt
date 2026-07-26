package com.amandhakar.ledgerly.parser

/**
 * Task 2.8/tasks/phase-2.md: "a refund nets against the original spend... reduce that spend's
 * effective amount." A refund is never larger than what it refunds in a legitimate match (the
 * matcher itself only links a spend whose amount covers the refund), but this stays defensive
 * against a partial-refund chain that outgrew the spend for any other reason - never negative.
 */
fun effectiveAmount(originalAmount: Long, refundedAmount: Long): Long =
    (originalAmount - refundedAmount).coerceAtLeast(0L)
