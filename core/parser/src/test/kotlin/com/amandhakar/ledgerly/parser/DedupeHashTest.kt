package com.amandhakar.ledgerly.parser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DedupeHashTest {

    @Test
    fun `identical sender, timestamp and body produce the same hash`() {
        val a = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "some message body")
        val b = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "some message body")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `a different body changes the hash`() {
        val a = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "body one")
        val b = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "body two")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `a different sender changes the hash`() {
        val a = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "same body")
        val b = computeDedupeHash("JX-ICICIT-S", 1_700_000_000_000L, "same body")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `a different timestamp changes the hash`() {
        val a = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "same body")
        val b = computeDedupeHash("AD-ICICIT-S", 1_700_000_001_000L, "same body")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `hash is a 64-character lowercase hex string`() {
        val hash = computeDedupeHash("AD-ICICIT-S", 1_700_000_000_000L, "body")
        assertThat(hash).matches("[0-9a-f]{64}")
    }
}
