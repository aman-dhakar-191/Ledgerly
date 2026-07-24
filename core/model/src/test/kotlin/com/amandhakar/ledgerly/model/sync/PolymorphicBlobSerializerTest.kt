package com.amandhakar.ledgerly.model.sync

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@Serializable
data class TestTransaction(val id: String, val amount: Long, val note: String? = null)

private val testModule = SerializersModule {
    polymorphic(Any::class) {
        subclass(TestTransaction::class)
    }
}

class PolymorphicBlobSerializerTest {

    private val serializer = PolymorphicBlobSerializer(testModule)

    @ParameterizedTest
    @ValueSource(ints = [1, 100, 10_000])
    fun `round trips N entities exactly`(count: Int) = runBlocking {
        val entities = (1..count).map { TestTransaction(id = "txn-$it", amount = it.toLong() * 100) }

        val bytes = serializer.serialize("txn-2026-07", entities)
        val decoded = serializer.deserialize("txn-2026-07", bytes)

        assertThat(decoded).isEqualTo(entities)
    }

    @Test
    fun `unknown fields in a newer payload don't crash deserialization`() = runBlocking {
        val original = listOf(TestTransaction("t1", 100, null))
        val bytes = serializer.serialize("blob", original)
        val json = gunzip(bytes)

        // Simulate a blob written by a newer app version carrying a field this version
        // doesn't know about yet.
        val mutatedJson = json.removeSuffix("]").removeSuffix("}") + ",\"futureField\":\"unexpected\"}]"
        val mutatedBytes = gzip(mutatedJson)

        val decoded = serializer.deserialize("blob", mutatedBytes)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `serializeWithSplit does not split a small payload`() = runBlocking {
        val entities = (1..5).map { TestTransaction("txn-$it", it.toLong()) }
        val result = serializer.serializeWithSplit("txn-2026-07", entities)

        assertThat(result.keys).containsExactly("txn-2026-07")
    }

    @Test
    fun `serializeWithSplit splits into -a -b blobs once the payload exceeds the size limit`() = runBlocking {
        val random = Random(seed = 42)
        // Pseudo-random (not gzip-friendly) text so the compressed size actually scales with input.
        val entities = (1..8_000).map {
            TestTransaction(id = "txn-$it", amount = it.toLong(), note = randomString(random, 300))
        }

        val result = serializer.serializeWithSplit("txn-2026-07", entities, maxBytes = MAX_BLOB_SIZE_BYTES)

        assertThat(result.size).isAtLeast(2)
        assertThat(result.keys).containsAtLeast("txn-2026-07-a", "txn-2026-07-b")
        result.values.forEach { chunkBytes -> assertThat(chunkBytes.size).isAtMost(MAX_BLOB_SIZE_BYTES) }

        val reassembled = result.entries.sortedBy { it.key }.flatMap { (_, chunkBytes) ->
            serializer.deserialize("txn-2026-07", chunkBytes)
        }
        assertThat(reassembled).isEqualTo(entities)
    }

    private fun randomString(random: Random, length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun gzip(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
}
