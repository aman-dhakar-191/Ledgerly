package com.amandhakar.ledgerly.model.sync

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * A generic [BlobSerializer] over any set of concrete `@Serializable` classes registered in
 * [module] as polymorphic subtypes of [Any]. `docs/crypto.md`'s literal interface takes
 * `List<@Serializable Any>` — `@Serializable` isn't a type-use annotation, so that can't compile
 * as written; a caller-supplied [SerializersModule] is the real mechanism for "any serializable
 * class" without this module needing to know about every concrete entity type (which would
 * require depending downward from :core:model onto :core:database).
 *
 * `ignoreUnknownKeys` satisfies "unknown fields in older blobs don't crash deserialization."
 */
class PolymorphicBlobSerializer(module: SerializersModule) : BlobSerializer {

    private val json = Json {
        serializersModule = module
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(PolymorphicSerializer(Any::class))

    override suspend fun serialize(blobId: String, entities: List<Any>): ByteArray {
        val jsonText = json.encodeToString(listSerializer, entities)
        return gzip(jsonText.toByteArray(Charsets.UTF_8))
    }

    override suspend fun deserialize(blobId: String, bytes: ByteArray): List<Any> {
        val jsonText = gunzip(bytes).toString(Charsets.UTF_8)
        return json.decodeFromString(listSerializer, jsonText)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream()).use { it.readBytes() }
}
