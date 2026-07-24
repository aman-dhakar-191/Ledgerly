package com.amandhakar.ledgerly.ui.unlock

import com.nulabinc.zxcvbn.Zxcvbn

/** tasks/phase-0.md Task 0.8: zxcvbn strength meter, minimum score 3 (of 0-4) to proceed. */
object PassphraseStrength {
    const val MIN_SCORE = 3
    private val zxcvbn = Zxcvbn()

    fun score(passphrase: String): Int = if (passphrase.isEmpty()) 0 else zxcvbn.measure(passphrase).score

    fun isStrongEnough(passphrase: String): Boolean = score(passphrase) >= MIN_SCORE
}
