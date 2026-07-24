package com.amandhakar.ledgerly.update.android

import com.amandhakar.ledgerly.BuildConfig
import com.amandhakar.ledgerly.update.UpdateChecker
import com.amandhakar.ledgerly.update.UpdateInfo
import com.amandhakar.ledgerly.update.parseLatestRelease
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * docs/ci.md: unauthenticated, public repo, well under the 60 req/hour rate limit for a daily
 * check. A network failure throws rather than returning null, so [UpdateCheckWorker] can tell
 * "no update" apart from "couldn't check" and retry the latter.
 */
class GithubUpdateChecker @Inject constructor() : UpdateChecker {

    override suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = fetch(RELEASES_URL)
        parseLatestRelease(body, BuildConfig.VERSION_CODE)
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub releases request failed: HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/aman-dhakar-191/Ledgerly/releases/latest"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
