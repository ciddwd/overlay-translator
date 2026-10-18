package com.gameocr.app.llm

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelMemoryPolicyTest {
    @Test
    fun metadataProtocol_retainsLayerArraysAndHandlesMissingMalformedValues() {
        val fields = arrayOf("qwen3", "40960", "1024", "2", "8,16", "2,4", "128,64", "128,64",
            "151936", "0", "", "", "", "")
        val metadata = requireNotNull(LocalModelMemoryMetadata.fromNative(fields))
        assertEquals(listOf(8, 16), metadata.headCount)
        assertEquals(listOf(2, 4), metadata.headCountKv)
        assertEquals(listOf(128, 64), metadata.keyLength)
        assertEquals(0, metadata.slidingWindow)
        assertNull(metadata.ssmStateSize)
        assertEquals(metadata, Json.decodeFromString<LocalModelMemoryMetadata>(Json.encodeToString(metadata)))
        listOf("", "2,bad", "-1", "999999999999999999999").forEach { raw ->
            val invalid = fields.copyOf().also { it[5] = raw }
            assertEquals(emptyList<Int>(), LocalModelMemoryMetadata.fromNative(invalid)?.headCountKv)
        }
        assertNull(LocalModelMemoryMetadata.fromNative(fields.dropLast(1).toTypedArray()))
        assertNull(LocalModelMemoryMetadata.fromNative(fields.copyOf().also { it[0] = "" }))
    }

    @Test
    fun conventionalGqa_estimatesActualSlotsAndQuantizedBlockSizesNotNineSequences() {
        data class Case(val config: LocalModelConfig, val kv: Long)
        listOf(
            Case(LocalModelConfig(), 48L * MIB),
            Case(LocalModelConfig(cacheTypeK = "q8_0", cacheTypeV = "q8_0"), 51L * MIB / 2),
            Case(LocalModelConfig(cacheTypeK = "q4_0", cacheTypeV = "q4_0"), 27L * MIB / 2),
            Case(LocalModelConfig(cacheTypeK = "q4_0", cacheTypeV = "q8_0", flashAttention = "off"), 123L * MIB / 4),
            Case(LocalModelConfig(contextTokens = 513), 18L * MIB),
            Case(LocalModelConfig(contextTokens = 4096), 96L * MIB),
        ).forEach { case ->
            val estimate = LocalModelMemoryPolicy.estimate(entry(), case.config)
            assertEquals(case.kv, estimate.kvBytes)
            assertEquals(WEIGHTS, estimate.weightsBytes)
            assertEquals(estimate.weightsBytes + estimate.kvBytes + estimate.computeBytes, estimate.totalBytes)
            assertTrue(estimate.metadataBased)
            assertTrue(estimate.approximate)
            assertEquals(LocalModelMemoryEstimateSource.METADATA, estimate.source)
            assertTrue(estimate.conservativeTotalBytes >= estimate.totalBytes)
        }
    }

    @Test
    fun perLayerDimensions_andTransposedValueCacheUseMaximumWidth() {
        val metadata = metadata().copy(blockCount = 2, headCount = listOf(8, 16), headCountKv = listOf(2, 4),
            keyLength = listOf(128, 64), valueLength = listOf(64, 128))
        data class Case(val flash: String, val expectedKv: Long)
        listOf(Case("on", 4718592), Case("off", 6291456)).forEach { case ->
            val estimate = LocalModelMemoryPolicy.estimate(entry(metadata), LocalModelConfig(flashAttention = case.flash))
            assertEquals(case.expectedKv, estimate.kvBytes)
            assertTrue(estimate.metadataBased)
        }
    }

    @Test
    fun invalidUnknownHybridOrMissingMetadata_usesExplicitConservativeFallback() {
        data class Case(val metadata: LocalModelMemoryMetadata?, val source: LocalModelMemoryEstimateSource)
        listOf(
            Case(null, LocalModelMemoryEstimateSource.MISSING_METADATA),
            Case(metadata().copy(architecture = "future-model"), LocalModelMemoryEstimateSource.UNSUPPORTED_ARCHITECTURE),
            Case(metadata().copy(architecture = "qwen35"), LocalModelMemoryEstimateSource.UNSUPPORTED_ARCHITECTURE),
            Case(metadata().copy(ssmStateSize = 128), LocalModelMemoryEstimateSource.UNSUPPORTED_ARCHITECTURE),
            Case(metadata().copy(fullAttentionInterval = 4), LocalModelMemoryEstimateSource.UNSUPPORTED_ARCHITECTURE),
            Case(metadata().copy(blockCount = 0), LocalModelMemoryEstimateSource.INVALID_METADATA),
            Case(metadata().copy(headCountKv = emptyList()), LocalModelMemoryEstimateSource.INVALID_METADATA),
            Case(metadata().copy(headCountKv = listOf(3)), LocalModelMemoryEstimateSource.INVALID_METADATA),
            Case(metadata().copy(keyLength = listOf(128, 128)), LocalModelMemoryEstimateSource.INVALID_METADATA),
            Case(metadata().copy(embeddingLength = 1001), LocalModelMemoryEstimateSource.INVALID_METADATA),
            Case(metadata().copy(vocabSize = null), LocalModelMemoryEstimateSource.INVALID_METADATA),
        ).forEach { case ->
            val estimate = LocalModelMemoryPolicy.estimate(entry(case.metadata))
            assertFalse(estimate.metadataBased)
            assertTrue(estimate.approximate)
            assertEquals(case.source, estimate.source)
            assertTrue(estimate.kvBytes >= 512 * MIB)
            assertEquals(estimate.totalBytes, estimate.conservativeTotalBytes)
        }
        assertEquals(Long.MAX_VALUE, LocalModelMemoryPolicy.estimate(entry().copy(sizeBytes = Long.MAX_VALUE)).totalBytes)
        assertEquals(0L, LocalModelMemoryPolicy.estimate(entry(null).copy(sizeBytes = -1)).weightsBytes)
    }

    @Test
    fun computeWorkspaceBudget_respondsToMicroBatchVocabAndAttentionSetting() {
        val original = LocalModelMemoryPolicy.estimate(entry())
        assertTrue(LocalModelMemoryPolicy.estimate(entry(), LocalModelConfig(microBatchTokens = 64)).computeBytes < original.computeBytes)
        assertTrue(LocalModelMemoryPolicy.estimate(entry(metadata().copy(vocabSize = 200000))).computeBytes > original.computeBytes)
        assertTrue(LocalModelMemoryPolicy.estimate(entry(), LocalModelConfig(flashAttention = "on")).computeBytes < original.computeBytes)
    }

    @Test
    fun conservativeBudget_includesF16AndFlashOffWorkspaceAfterNativeFallback() {
        data class Case(val metadata: LocalModelMemoryMetadata, val config: LocalModelConfig)
        listOf(
            Case(metadata(), LocalModelConfig(flashAttention = "on")),
            Case(metadata(), LocalModelConfig(flashAttention = "on", cacheTypeK = "q4_0", cacheTypeV = "q8_0")),
            Case(metadata().copy(blockCount = 2, valueLength = listOf(64, 256)),
                LocalModelConfig(flashAttention = "on", cacheTypeK = "q8_0", cacheTypeV = "q4_0")),
        ).forEach { case ->
            val requested = LocalModelMemoryPolicy.estimate(entry(case.metadata), case.config)
            val cpuFallback = LocalModelMemoryPolicy.estimate(entry(case.metadata),
                case.config.copy(flashAttention = "off", cacheTypeK = "f16", cacheTypeV = "f16", gpuLayers = 0))
            assertTrue(requested.conservativeTotalBytes >= cpuFallback.totalBytes)
            assertTrue(requested.conservativeTotalBytes >= requested.totalBytes)
        }
    }

    private fun metadata() = LocalModelMemoryMetadata(
        architecture = "qwen3", contextLength = 40960, embeddingLength = 1024, blockCount = 24,
        headCount = listOf(8), headCountKv = listOf(2), vocabSize = 151936,
    )

    private fun entry(metadata: LocalModelMemoryMetadata? = metadata()) = LocalModelEntry(
        id = "model", name = "model", kind = null, file = null, sizeBytes = WEIGHTS,
        architecture = metadata?.architecture.orEmpty(), quantization = "Q4_K_M", chatTemplate = "template",
        config = LocalModelConfig(), memoryMetadata = metadata,
    )

    companion object {
        private const val MIB = 1024L * 1024L
        private const val WEIGHTS = 512 * MIB
    }
}
