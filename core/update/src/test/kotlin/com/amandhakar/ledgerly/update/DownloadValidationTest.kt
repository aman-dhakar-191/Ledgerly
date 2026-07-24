package com.amandhakar.ledgerly.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DownloadValidationTest {

    @Test
    fun `matching content length and expected size is complete`() {
        assertThat(isCompleteDownload(actualBytes = 1000, expectedSizeBytes = 1000, contentLength = 1000)).isTrue()
    }

    @Test
    fun `truncated download fails even if the server never sent Content-Length`() {
        assertThat(isCompleteDownload(actualBytes = 500, expectedSizeBytes = 1000, contentLength = -1)).isFalse()
    }

    @Test
    fun `content length mismatch fails even if final size happens to match expected`() {
        assertThat(isCompleteDownload(actualBytes = 1000, expectedSizeBytes = 1000, contentLength = 999)).isFalse()
    }

    @Test
    fun `size matches Content-Length but not the release metadata fails`() {
        assertThat(isCompleteDownload(actualBytes = 1000, expectedSizeBytes = 2000, contentLength = 1000)).isFalse()
    }
}
