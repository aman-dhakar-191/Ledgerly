package com.amandhakar.ledgerly.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val VERSION_CODE_LINE = Regex("(?im)^versionCode:\\s*(\\d+)\\s*$")
private val SCHEMA_VERSION_LINE = Regex("(?im)^schemaVersion:\\s*(\\d+)\\s*$")
private val TAG_NAME = Regex("^v(.+)-build\\d+$")

/**
 * Turns a `GET .../releases/latest` response body into an [UpdateInfo], or `null` if there's
 * nothing to install — docs/ci.md's rules: drafts and prereleases are ignored, `versionCode` is
 * compared as an integer (never the version-name string) and must be present and well-formed,
 * and the asset must be the `-release.apk`, never a debug build.
 */
@Suppress("ReturnCount") // guard-clause style is clearer than nesting for this parser
fun parseLatestRelease(responseJson: String, currentVersionCode: Int): UpdateInfo? {
    val release = runCatching { json.decodeFromString<GithubRelease>(responseJson) }
        .getOrElse { if (it is SerializationException) return null else throw it }
    if (release.draft || release.prerelease) return null

    val versionCode = VERSION_CODE_LINE.find(release.body.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        ?: return null
    if (versionCode <= currentVersionCode) return null

    val asset = release.assets.firstOrNull { it.name.endsWith("-release.apk") } ?: return null
    val schemaVersion = SCHEMA_VERSION_LINE.find(release.body.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    return UpdateInfo(
        versionCode = versionCode,
        versionName = TAG_NAME.find(release.tagName)?.groupValues?.get(1) ?: release.tagName,
        downloadUrl = asset.browserDownloadUrl,
        releaseNotes = release.body,
        sizeBytes = asset.size,
        targetSchemaVersion = schemaVersion,
    )
}
