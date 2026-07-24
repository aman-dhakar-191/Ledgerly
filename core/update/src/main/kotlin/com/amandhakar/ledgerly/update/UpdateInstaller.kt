package com.amandhakar.ledgerly.update

import java.io.File

/** tasks/update-system.md. */
interface UpdateInstaller {
    suspend fun download(info: UpdateInfo): Result<File>
    fun verifySignature(apk: File): Boolean
    fun requestInstall(apk: File)
}
