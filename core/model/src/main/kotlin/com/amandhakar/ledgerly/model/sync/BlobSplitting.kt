package com.amandhakar.ledgerly.model.sync

/** docs/crypto.md: "If a blob approaches 900 KiB, split to txn-2026-07-a, txn-2026-07-b." */
const val MAX_BLOB_SIZE_BYTES = 900 * 1024

/**
 * Serializes [entities] under [blobId], splitting into `-a`, `-b`, ... suffixed blobs (each
 * re-serialized independently) if the whole set would exceed [maxBytes]. Returns a single
 * `{blobId: bytes}` entry when no split is needed.
 */
suspend fun BlobSerializer.serializeWithSplit(
    blobId: String,
    entities: List<Any>,
    maxBytes: Int = MAX_BLOB_SIZE_BYTES,
): Map<String, ByteArray> {
    val whole = serialize(blobId, entities)
    if (whole.size <= maxBytes || entities.size <= 1) {
        return mapOf(blobId to whole)
    }

    val mid = entities.size / 2
    val chunks = mutableListOf<List<Any>>()
    splitUntilFits(entities.subList(0, mid), maxBytes, chunks)
    splitUntilFits(entities.subList(mid, entities.size), maxBytes, chunks)

    return chunks.mapIndexed { index, chunk -> "$blobId-${suffix(index)}" to serialize(blobId, chunk) }.toMap()
}

private suspend fun BlobSerializer.splitUntilFits(entities: List<Any>, maxBytes: Int, out: MutableList<List<Any>>) {
    if (entities.size <= 1 || serialize("size-check", entities).size <= maxBytes) {
        out += entities
        return
    }
    val mid = entities.size / 2
    splitUntilFits(entities.subList(0, mid), maxBytes, out)
    splitUntilFits(entities.subList(mid, entities.size), maxBytes, out)
}

private fun suffix(index: Int): String = ('a' + index).toString()
