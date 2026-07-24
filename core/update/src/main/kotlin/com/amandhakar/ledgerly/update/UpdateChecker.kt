package com.amandhakar.ledgerly.update

/** `null` means up to date. A failed check (network error) is an exception, not a null. */
interface UpdateChecker {
    suspend fun checkForUpdate(): UpdateInfo?
}
