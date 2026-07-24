package com.amandhakar.ledgerly.parser

import java.security.MessageDigest

/**
 * Task 1.1: computed before every `RawSms` insert so the unique index on `dedupe_hash` makes
 * re-ingest safe — the same SMS arriving twice (a re-delivered broadcast, the periodic archive
 * scan re-seeing a message the live receiver already caught) inserts once.
 */
fun computeDedupeHash(sender: String, receivedAt: Long, body: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$sender|$receivedAt|$body".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
