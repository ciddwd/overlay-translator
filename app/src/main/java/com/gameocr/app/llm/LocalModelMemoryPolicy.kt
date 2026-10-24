package com.gameocr.app.llm

import kotlinx.serialization.Serializable

/** Header-only GGUF information; empty/unknown values are not invented from a model's name. */
@Serializable
data class LocalModelMemoryMetadata(
    val architecture: String = "",
    val contextLength: Int? = null,
    val embeddingLength: Int? = null,
    val blockCount: Int? = null,
    val headCount: List<Int> = emptyList(),
    val headCountKv: List<Int> = emptyList(),
    val keyLength: List<Int> = emptyList(),
    val valueLength: List<Int> = emptyList(),
    val vocabSize: Int? = null,
    val slidingWindow: Int? = null,
    val ssmStateSize: Int? = null,
    val ssmInnerSize: Int? = null,
    val ssmConvKernel: Int? = null,
    val fullAttentionInterval: Int? = null,
) {
    companion object {
        /** Fixed protocol shared with inspectMemoryFileNative; numeric arrays preserve layer detail. */
        fun fromNative(values: Array<String>): LocalModelMemoryMetadata? {
            if (values.size != 14 || values[0].isBlank()) return null
            fun scalar(index: Int): Int? = values[index].trim().toIntOrNull()?.takeIf { it >= 0 }
            fun vector(index: Int): List<Int> {
                if (values[index].isBlank()) return emptyList()
                val result = values[index].split(',').map { it.trim().toIntOrNull() }
                return if (result.all { it != null && it >= 0 }) result.filterNotNull() else emptyList()
            }
            return LocalModelMemoryMetadata(
                architecture = values[0], contextLength = scalar(1), embeddingLength = scalar(2),
                blockCount = scalar(3), headCount = vector(4), headCountKv = vector(5),
                keyLength = vector(6), valueLength = vector(7), vocabSize = scalar(8),
                slidingWindow = scalar(9), ssmStateSize = scalar(10), ssmInnerSize = scalar(11),
                ssmConvKernel = scalar(12), fullAttentionInterval = scalar(13),
            )
        }
    }
}

enum class LocalModelMemoryEstimateSource { METADATA, MISSING_METADATA, INVALID_METADATA, UNSUPPORTED_ARCHITECTURE }

data class LocalModelMemoryEstimate(
    val weightsBytes: Long,
    val kvBytes: Long,
    val computeBytes: Long,
    val totalBytes: Long,
    val metadataBased: Boolean,
    val source: LocalModelMemoryEstimateSource,
    /** CPU/backend support can force quantized KV back to F16; this budget includes that case. */
    val conservativeTotalBytes: Long,
    val approximate: Boolean = true,
)

/**
 * Advisory budget, not resident-memory measurement or a promise that a model will load.
 * KV layout follows pinned llama.cpp llama-hparams.cpp/llama-kv-cache.cpp; ggml-common.h defines
 * 32-value Q8_0/Q4_0 blocks as 34/18 bytes. Compute buffers are graph/backend dependent, so even
 * complete conventional-transformer metadata only produces an approximate total.
 */
object LocalModelMemoryPolicy {
    private const val MIB = 1024L * 1024L
    private val conventionalArchitectures = setOf(
        "llama", "qwen", "qwen2", "qwen2moe", "qwen3", "qwen3moe", "mistral",
        "gemma", "gemma2", "gemma3", "phi2", "phi3", "stablelm", "internlm2", "baichuan", "starcoder2",
    )

    fun estimate(entry: LocalModelEntry, config: LocalModelConfig = entry.config): LocalModelMemoryEstimate {
        val value = config.normalized()
        val weights = entry.sizeBytes.coerceAtLeast(0)
        val metadata = entry.memoryMetadata
            ?: return fallback(weights, value, LocalModelMemoryEstimateSource.MISSING_METADATA)
        val architecture = metadata.architecture.trim().lowercase()
        if (architecture !in conventionalArchitectures ||
            (metadata.ssmStateSize ?: 0) > 0 || (metadata.ssmInnerSize ?: 0) > 0 ||
            (metadata.ssmConvKernel ?: 0) > 0 || (metadata.fullAttentionInterval ?: 0) > 0
        ) return fallback(weights, value, LocalModelMemoryEstimateSource.UNSUPPORTED_ARCHITECTURE)
        return try {
            fromMetadata(weights, value, metadata)
                ?: fallback(weights, value, LocalModelMemoryEstimateSource.INVALID_METADATA)
        } catch (_: ArithmeticException) {
            fallback(weights, value, LocalModelMemoryEstimateSource.INVALID_METADATA)
        }
    }

    private fun fromMetadata(
        weights: Long, config: LocalModelConfig, metadata: LocalModelMemoryMetadata,
    ): LocalModelMemoryEstimate? {
        val layers = metadata.blockCount?.takeIf { it in 1..512 } ?: return null
        val embedding = metadata.embeddingLength?.takeIf { it in 1..262144 } ?: return null
        val vocab = metadata.vocabSize?.takeIf { it in 1..2000000 } ?: return null
        if ((metadata.contextLength ?: 0) <= 0) return null
        fun List<Int>.perLayer(): List<Int>? = when (size) {
            1 -> List(layers) { first() }
            layers -> this
            else -> null
        }
        val heads = metadata.headCount.perLayer()?.takeIf { it.all { n -> n in 1..4096 } } ?: return null
        val kvHeads = metadata.headCountKv.perLayer()?.takeIf { it.all { n -> n in 1..4096 } } ?: return null
        if (kvHeads.indices.any { kvHeads[it] > heads[it] || heads[it] % kvHeads[it] != 0 }) return null
        fun dimensions(raw: List<Int>): List<Int>? = if (raw.isNotEmpty()) {
            raw.perLayer()?.takeIf { it.all { n -> n in 1..262144 } }
        } else {
            if (heads.any { embedding % it != 0 }) null else heads.map { embedding / it }
        }
        val keyDimensions = dimensions(metadata.keyLength) ?: return null
        val valueDimensions = dimensions(metadata.valueLength) ?: return null
        val keyWidths = keyDimensions.indices.map { multiply(keyDimensions[it].toLong(), kvHeads[it].toLong()) }
        val valueWidths = valueDimensions.indices.map { multiply(valueDimensions[it].toLong(), kvHeads[it].toLong()) }
        // kv_unified=true means these are total context slots, not context multiplied by n_seq_max.
        val context = roundUp(config.contextTokens.toLong(), 256)
        val maxValueWidth = valueWidths.max()
        val kv = keyWidths.indices.sumOf { index ->
            val widthV = if (config.flashAttention == "off") maxValueWidth else valueWidths[index]
            multiply(context, add(rowBytes(keyWidths[index], config.cacheTypeK), rowBytes(widthV, config.cacheTypeV)))
        }
        // Without flash attention llama.cpp's transposed V allocation uses the maximum layer width.
        val fallbackKv = keyWidths.sumOf { multiply(context, multiply(add(it, maxValueWidth), 2)) }
        // Conservative graph-workspace margin: activations, vocabulary logits and (when applicable)
        // attention scores. Graph reuse, output selection and backend scheduling change actual usage.
        val micro = config.microBatchTokens.toLong()
        val activations = multiply(micro, add(multiply(embedding.toLong(), 48), multiply(vocab.toLong(), 4)))
        val attentionWithoutFlash = multiply(multiply(multiply(context, micro), heads.max().toLong()), 4)
        val attention = if (config.flashAttention == "on") 0L else attentionWithoutFlash
        val compute = add(64 * MIB, add(activations, attention))
        val fallbackCompute = add(64 * MIB, add(activations, attentionWithoutFlash))
        val total = add(weights, add(kv, compute))
        return LocalModelMemoryEstimate(
            weightsBytes = weights, kvBytes = kv, computeBytes = compute, totalBytes = total,
            metadataBased = true, source = LocalModelMemoryEstimateSource.METADATA,
            conservativeTotalBytes = add(weights, add(maxOf(kv, fallbackKv), maxOf(compute, fallbackCompute))),
        )
    }

    private fun fallback(
        weights: Long, config: LocalModelConfig, source: LocalModelMemoryEstimateSource,
    ): LocalModelMemoryEstimate {
        // Unknown/recurrent layouts have no trustworthy conventional KV formula. Reserve a broad
        // context/weight-scaled margin rather than falsely showing a small, metadata-based budget.
        val kv = maxOf(512 * MIB, weights / 2, config.contextTokens * 256L * 1024L)
        val compute = maxOf(256 * MIB, config.microBatchTokens * MIB)
        val total = saturatingAdd(weights, saturatingAdd(kv, compute))
        return LocalModelMemoryEstimate(weights, kv, compute, total, false, source, total)
    }

    private fun rowBytes(values: Long, type: String): Long = when (type) {
        "q8_0" -> multiply(roundUp(values, 32) / 32, 34)
        "q4_0" -> multiply(roundUp(values, 32) / 32, 18)
        else -> multiply(values, 2)
    }

    private fun roundUp(value: Long, multiple: Long): Long = multiply((value + multiple - 1) / multiple, multiple)
    private fun multiply(a: Long, b: Long): Long = Math.multiplyExact(a, b)
    private fun add(a: Long, b: Long): Long = Math.addExact(a, b)
    private fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
