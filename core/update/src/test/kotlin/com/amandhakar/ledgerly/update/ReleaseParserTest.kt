package com.amandhakar.ledgerly.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReleaseParserTest {

    private fun release(
        tagName: String = "v0.2.0-build15",
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String? = "versionCode: 15\n\nAuto-generated release notes.",
        assets: String = """
            [{"name":"Ledgerly-v0.2.0-build15-release.apk",
              "browser_download_url":"https://example.com/a.apk","size":1000}]
        """,
    ): String = """
        {
          "tag_name": "$tagName",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": ${if (body == null) "null" else "\"${body.replace("\n", "\\n")}\""},
          "assets": $assets
        }
    """.trimIndent()

    @Test
    fun `higher remote versionCode is an update`() {
        val info = parseLatestRelease(release(), currentVersionCode = 10)
        assertThat(info).isNotNull()
        assertThat(info!!.versionCode).isEqualTo(15)
        assertThat(info.versionName).isEqualTo("0.2.0")
        assertThat(info.downloadUrl).isEqualTo("https://example.com/a.apk")
        assertThat(info.sizeBytes).isEqualTo(1000L)
    }

    @Test
    fun `equal versionCode is up to date`() {
        assertThat(parseLatestRelease(release(), currentVersionCode = 15)).isNull()
    }

    @Test
    fun `lower remote versionCode is up to date`() {
        assertThat(parseLatestRelease(release(), currentVersionCode = 20)).isNull()
    }

    @Test
    fun `absent versionCode returns null rather than guessing`() {
        val json = release(body = "Just some release notes, no version line.")
        assertThat(parseLatestRelease(json, currentVersionCode = 1)).isNull()
    }

    @Test
    fun `malformed versionCode returns null`() {
        val json = release(body = "versionCode: not-a-number")
        assertThat(parseLatestRelease(json, currentVersionCode = 1)).isNull()
    }

    @Test
    fun `null body returns null`() {
        val json = release(body = null)
        assertThat(parseLatestRelease(json, currentVersionCode = 1)).isNull()
    }

    @Test
    fun `draft releases are ignored`() {
        assertThat(parseLatestRelease(release(draft = true), currentVersionCode = 1)).isNull()
    }

    @Test
    fun `prerelease releases are ignored`() {
        assertThat(parseLatestRelease(release(prerelease = true), currentVersionCode = 1)).isNull()
    }

    @Test
    fun `a debug-named asset is never returned as the update`() {
        val json = release(
            assets = """[{"name":"Ledgerly-v0.2.0-build15-debug.apk","browser_download_url":"https://x/d.apk","size":1000}]""",
        )
        assertThat(parseLatestRelease(json, currentVersionCode = 1)).isNull()
    }

    @Test
    fun `release-named asset is chosen even when a debug asset is also present`() {
        val json = release(
            assets = """[
                {"name":"Ledgerly-v0.2.0-build15-debug.apk","browser_download_url":"https://x/d.apk","size":999},
                {"name":"Ledgerly-v0.2.0-build15-release.apk","browser_download_url":"https://x/r.apk","size":1000}
            ]""",
        )
        val info = parseLatestRelease(json, currentVersionCode = 1)
        assertThat(info?.downloadUrl).isEqualTo("https://x/r.apk")
    }

    @Test
    fun `schemaVersion line is parsed when present`() {
        val json = release(body = "versionCode: 15\nschemaVersion: 3\n")
        val info = parseLatestRelease(json, currentVersionCode = 1)
        assertThat(info?.targetSchemaVersion).isEqualTo(3)
    }

    @Test
    fun `schemaVersion is null when absent`() {
        val info = parseLatestRelease(release(), currentVersionCode = 1)
        assertThat(info?.targetSchemaVersion).isNull()
    }

    @Test
    fun `malformed JSON returns null rather than throwing`() {
        assertThat(parseLatestRelease("not json at all", currentVersionCode = 1)).isNull()
    }
}
