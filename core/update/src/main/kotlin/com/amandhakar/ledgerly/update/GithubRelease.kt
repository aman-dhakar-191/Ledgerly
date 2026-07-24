package com.amandhakar.ledgerly.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shape of `GET /repos/{owner}/{repo}/releases/latest` (docs/ci.md), fields we actually use. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long,
)
