package com.gameocr.app.translate

import com.gameocr.app.llm.LlamaMultiSequence
import kotlin.math.min

internal data class LocalLlmNativeBatchPlan<T>(
    val items: List<T>,
    val promptTokens: Int,
    val requiredKvTokens: Int,
    val nativeBatch: Boolean,
)

internal fun localLlmBatchResultUpdates(
    resultIndexes: List<Int>,
    translated: String?,
): List<BatchTranslationUpdate> = resultIndexes.map { index ->
    BatchTranslationUpdate(index = index, text = translated)
}

/**
 * Plans independent native sequences without exceeding the per-sequence context, unified KV,
 * JNI sequence capacity, or the maximum logical prompt batch accepted by llama_decode.
 */
internal object LocalLlmNativeBatchPolicy {
    const val CONTEXT_HEADROOM_TOKENS = 4
    val SUPPORTED_BATCH_SIZES = setOf(1, 2, 4, 8)

    fun selectedBatchSize(requested: Int, nativeSequenceCapacity: Int): Int {
        if (requested !in SUPPORTED_BATCH_SIZES) return 1
        return min(
            requested,
            nativeSequenceCapacity.coerceIn(1, LlamaMultiSequence.MAX_SEQUENCE_COUNT),
        )
    }

    fun <T> plan(
        items: List<T>,
        requestedBatchSize: Int,
        configuredContextTokens: Int,
        engineContextTokens: Int,
        systemPromptTokens: Int,
        nativePromptBatchTokens: Int,
        nativeSequenceCapacity: Int,
        maxNewTokensPerItem: Int,
        promptTokenCount: (T) -> Int,
    ): List<LocalLlmNativeBatchPlan<T>> {
        if (items.isEmpty()) return emptyList()

        val batchSize = selectedBatchSize(requestedBatchSize, nativeSequenceCapacity)
        val engineContext = engineContextTokens.coerceAtLeast(1)
        val perSequenceContext = configuredContextTokens
            .takeIf { it > 0 }
            ?.coerceAtMost(engineContext)
            ?: engineContext
        val systemTokens = systemPromptTokens.coerceAtLeast(0)
        val promptBatchTokens = nativePromptBatchTokens.coerceAtLeast(1)
        val predictTokens = maxNewTokensPerItem.coerceAtLeast(1)
        val tokenCounts = items.map(promptTokenCount)
        val result = mutableListOf<LocalLlmNativeBatchPlan<T>>()
        var cursor = 0

        while (cursor < items.size) {
            val start = cursor
            var acceptedPromptTokens = 0
            var acceptedKvTokens = systemTokens

            while (cursor < items.size && cursor - start < batchSize) {
                val promptTokens = tokenCounts[cursor]
                if (promptTokens <= 0) break

                val perSequenceRequired = systemTokens.toLong() + promptTokens + predictTokens +
                    CONTEXT_HEADROOM_TOKENS
                val candidatePromptTokens = acceptedPromptTokens.toLong() + promptTokens
                val candidateKvTokens = acceptedKvTokens.toLong() + promptTokens + predictTokens
                val fits = perSequenceRequired <= perSequenceContext &&
                    candidatePromptTokens <= promptBatchTokens &&
                    candidateKvTokens + CONTEXT_HEADROOM_TOKENS <= engineContext
                if (!fits) break

                acceptedPromptTokens = candidatePromptTokens.toInt()
                acceptedKvTokens = candidateKvTokens.toInt()
                cursor += 1
            }

            if (cursor == start) {
                val promptTokens = tokenCounts[cursor]
                result += LocalLlmNativeBatchPlan(
                    items = listOf(items[cursor]),
                    promptTokens = promptTokens,
                    requiredKvTokens = systemTokens + promptTokens.coerceAtLeast(0) + predictTokens,
                    nativeBatch = false,
                )
                cursor += 1
            } else {
                val acceptedItems = items.subList(start, cursor).toList()
                result += LocalLlmNativeBatchPlan(
                    items = acceptedItems,
                    promptTokens = acceptedPromptTokens,
                    requiredKvTokens = acceptedKvTokens,
                    nativeBatch = acceptedItems.size > 1,
                )
            }
        }
        return result
    }
}
