package com.amandhakar.ledgerly.parser

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** docs/parser.md's rule validation, step 1: a candidate that can't finish fast is unsafe. */
sealed interface MatchOutcome {
    data class Matched(val result: MatchResult) : MatchOutcome
    data object NoMatch : MatchOutcome
    data object TimedOut : MatchOutcome
}

/** [runWithTimeout]'s result — distinct from [MatchOutcome] since [block] isn't always a regex match. */
sealed interface TimedResult<out T> {
    data class Completed<T>(val value: T) : TimedResult<T>
    data object TimedOut : TimedResult<Nothing>
}

private const val DEFAULT_TIMEOUT_MILLIS = 100L

// A blocked call isn't cancellable mid-execution in general (least of all a regex match, which
// can't be interrupted mid-backtrack), so a timeout here can only stop *waiting*, not the runaway
// work itself — the daemon thread keeps running until it finishes on its own. That's fine for what
// this is used for: rejecting an unsafe candidate rule before it's ever activated, not bounding
// work already committed to at runtime. Threads are cheap and daemon, so a handful of abandoned
// ones from rejected candidates cost nothing that matters.
private val TIMEOUT_EXECUTOR = Executors.newCachedThreadPool { runnable ->
    Thread(runnable).apply { isDaemon = true }
}

/**
 * Runs [block] on a background thread, waiting up to [timeoutMillis]. Kept generic and separate
 * from [matchWithTimeout] so the timeout mechanism itself has a test that doesn't depend on a
 * particular regex actually backtracking catastrophically — whether one does is a JVM
 * implementation detail, and modern JDKs have closed off most of the classic "evil regex" examples
 * (nested quantifiers over a single character class no longer blow up on JDK 21, for instance).
 */
fun <T> runWithTimeout(timeoutMillis: Long, block: () -> T): TimedResult<T> {
    val future = TIMEOUT_EXECUTOR.submit(Callable(block))
    return try {
        TimedResult.Completed(future.get(timeoutMillis, TimeUnit.MILLISECONDS))
    } catch (
        @Suppress("SwallowedException") // the timeout itself IS the result being reported
        e: TimeoutException,
    ) {
        future.cancel(true)
        TimedResult.TimedOut
    }
}

/**
 * Matches [pattern] against the *entire* [body] (a rule anchors on the whole message, not a
 * substring) within [timeoutMillis] — Task 1.7's "100ms timeout per match; reject on timeout
 * (catastrophic backtracking)".
 */
fun matchWithTimeout(pattern: Regex, body: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): MatchOutcome =
    when (val outcome = runWithTimeout(timeoutMillis) { pattern.matchEntire(body) }) {
        is TimedResult.Completed -> outcome.value?.let { MatchOutcome.Matched(it) } ?: MatchOutcome.NoMatch
        is TimedResult.TimedOut -> MatchOutcome.TimedOut
    }

/** Field name -> captured text, using a [GeneratedRule.fieldMap] against an already-successful match. */
fun capturedFields(rule: GeneratedRule, match: MatchResult): Map<String, String> =
    rule.fieldMap.mapNotNull { (fieldName, groupIndex) ->
        match.groups[groupIndex]?.value?.let { fieldName to it }
    }.toMap()
