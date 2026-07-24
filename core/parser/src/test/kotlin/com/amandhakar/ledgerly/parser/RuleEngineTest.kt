package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RuleEngineTest {

    @Test
    fun `generated rule matches its own source message`() {
        val body = "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:987654321"
        val confirmed = mapOf(
            "amount" to body.indexOf("5000.00").let { it until it + "5000.00".length },
            "merchant" to body.indexOf("AMAN DHAKAR").let { it until it + "AMAN DHAKAR".length },
            "occurredAt" to body.indexOf("09-Jun-26").let { it until it + "09-Jun-26".length },
        )

        val rule = generateRule(body, confirmed)
        val outcome = matchWithTimeout(Regex(rule.pattern), body)

        assertThat(outcome).isInstanceOf(MatchOutcome.Matched::class.java)
        val fields = capturedFields(rule, (outcome as MatchOutcome.Matched).result)
        assertThat(fields["amount"]).isEqualTo("5000.00")
        assertThat(fields["merchant"]).isEqualTo("AMAN DHAKAR")
        assertThat(fields["occurredAt"]).isEqualTo("09-Jun-26")
    }

    @Test
    fun `a generated rule still matches a sibling message with a different amount, merchant, date and reference`() {
        val source = "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:987654321"
        val confirmed = mapOf(
            "amount" to source.indexOf("5000.00").let { it until it + "5000.00".length },
            "merchant" to source.indexOf("AMAN DHAKAR").let { it until it + "AMAN DHAKAR".length },
            "occurredAt" to source.indexOf("09-Jun-26").let { it until it + "09-Jun-26".length },
            "reference" to source.indexOf("987654321").let { it until it + "987654321".length },
        )
        val rule = generateRule(source, confirmed)

        val nextMessage = "ICICI Bank Acct XX924 debited for Rs 1,234.56 on 15-Jul-26; SWIGGY credited. UPI:111222333"
        val outcome = matchWithTimeout(Regex(rule.pattern), nextMessage)

        assertThat(outcome).isInstanceOf(MatchOutcome.Matched::class.java)
        val fields = capturedFields(rule, (outcome as MatchOutcome.Matched).result)
        assertThat(fields["amount"]).isEqualTo("1,234.56")
        assertThat(fields["merchant"]).isEqualTo("SWIGGY")
        assertThat(fields["occurredAt"]).isEqualTo("15-Jul-26")
        assertThat(fields["reference"]).isEqualTo("111222333")
    }

    @Test
    fun `an unconfirmed variable field is baked in literally and so does not generalise`() {
        // A realistic limitation, not a bug: any field the reviewer didn't confirm (here,
        // "reference") stays part of the surrounding literal text, so the rule only matches
        // messages that happen to repeat that exact substring.
        val source = "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:987654321"
        val confirmed = mapOf("amount" to source.indexOf("5000.00").let { it until it + "5000.00".length })
        val rule = generateRule(source, confirmed)

        val differentReference = "ICICI Bank Acct XX924 debited for Rs 1,234.56 on 09-Jun-26; AMAN DHAKAR credited. UPI:111222333"
        assertThat(matchWithTimeout(Regex(rule.pattern), differentReference)).isEqualTo(MatchOutcome.NoMatch)
    }

    @Test
    fun `a message from an unrelated format does not match`() {
        val source = "ICICI Bank Acct XX924 debited for Rs 5000.00 on 09-Jun-26; AMAN DHAKAR credited. UPI:987654321"
        val confirmed = mapOf("amount" to source.indexOf("5000.00").let { it until it + "5000.00".length })
        val rule = generateRule(source, confirmed)

        val unrelated = "Your Apay Wallet balance is debited for INR 140.00. Reference Number is 600789415458"
        assertThat(matchWithTimeout(Regex(rule.pattern), unrelated)).isEqualTo(MatchOutcome.NoMatch)
    }

    @Test
    fun `timeout rejection works`() {
        // Whether a *particular* regex backtracks catastrophically is a JVM implementation detail
        // — JDK 21 has closed off every classic "evil regex" example tried here (nested
        // quantifiers over a single character class no longer blow up, even at hundreds of
        // repetitions). So this tests the actual mechanism Task 1.7 asks for — a hard wall-clock
        // timeout around work that might not return in time — directly, with a task that's
        // deterministically slow regardless of what the JVM's regex engine does or doesn't
        // optimize away.
        val outcome = runWithTimeout(timeoutMillis = 50) {
            Thread.sleep(500)
            "should never get here"
        }

        assertThat(outcome).isEqualTo(TimedResult.TimedOut)
    }

    @Test
    fun `a task that finishes within the timeout completes normally`() {
        val outcome = runWithTimeout(timeoutMillis = 500) { 2 + 2 }
        assertThat(outcome).isEqualTo(TimedResult.Completed(4))
    }

    @Test
    fun `a well-behaved rule does not time out`() {
        val outcome = matchWithTimeout(Regex("hello (\\w+)"), "hello world", timeoutMillis = 100)
        assertThat(outcome).isInstanceOf(MatchOutcome.Matched::class.java)
    }
}
