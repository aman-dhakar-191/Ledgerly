package com.amandhakar.ledgerly.update

/**
 * tasks/update-system.md. [targetSchemaVersion] isn't in the task's original sketch — it's the
 * "[Implementation note]" for the schema-migration guard, read from an optional `schemaVersion:`
 * line in the release body next to `versionCode:`.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val sizeBytes: Long,
    val targetSchemaVersion: Int?,
)
