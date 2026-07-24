package com.amandhakar.ledgerly.update

/**
 * A partial or truncated download must never reach the installer (tasks/update-system.md).
 * [contentLength] is what the server reported for this response (`-1` if it didn't say);
 * [expectedSizeBytes] is [UpdateInfo.sizeBytes] from the release asset metadata itself, checked
 * independently since a server could report a `Content-Length` for the wrong body.
 */
fun isCompleteDownload(actualBytes: Long, expectedSizeBytes: Long, contentLength: Long): Boolean {
    if (contentLength >= 0 && actualBytes != contentLength) return false
    return actualBytes == expectedSizeBytes
}
