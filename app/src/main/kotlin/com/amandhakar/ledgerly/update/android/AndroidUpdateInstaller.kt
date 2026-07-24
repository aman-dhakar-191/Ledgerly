package com.amandhakar.ledgerly.update.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.amandhakar.ledgerly.update.UpdateInfo
import com.amandhakar.ledgerly.update.UpdateInstaller
import com.amandhakar.ledgerly.update.isCompleteDownload
import com.amandhakar.ledgerly.update.verifyMatchingSignatures
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * tasks/update-system.md. Only the parts that genuinely need Android APIs live here — the actual
 * decisions ([isCompleteDownload], [verifyMatchingSignatures]) are pure functions in
 * `:core:update`, so they're covered by real local tests rather than only device testing.
 */
class AndroidUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateInstaller {

    override suspend fun download(info: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "update.apk")
        runCatching {
            val connection = URL(info.downloadUrl).openConnection() as HttpURLConnection
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("update download failed: HTTP ${connection.responseCode}")
                }
                val contentLength = connection.contentLengthLong
                connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                check(isCompleteDownload(target.length(), info.sizeBytes, contentLength)) {
                    "incomplete or mismatched download (expected ${info.sizeBytes} bytes)"
                }
                target
            } finally {
                connection.disconnect()
            }
        }.onFailure { target.delete() }
    }

    override fun verifySignature(apk: File): Boolean {
        val packageManager = context.packageManager
        val remote = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        val local = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
        return verifyMatchingSignatures(remote, local)
    }

    override fun requestInstall(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
