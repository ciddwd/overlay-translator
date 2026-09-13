package com.gameocr.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent-prompt multi-sequence generation backed by one batched llama_decode loop. */
internal object LlamaMultiSequence {
    const val MAX_SEQUENCE_COUNT = 8
    const val TOKEN_LIMIT_SENTINEL = "<|gameocr_token_limit|>"
    const val LINE_LIMIT_SENTINEL = "<|gameocr_line_limit|>"

    data class Metrics(
        val initMs: Double,
        val prefillMs: Double,
        val decodeMs: Double,
        val totalMs: Double,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    data class Result(
        val outputs: List<String>,
        val metrics: Metrics?,
    )

    suspend fun generate(prompts: List<String>, predictLength: Int): List<String>? {
        return generate(
            prompts = prompts,
            predictLengths = List(prompts.size) { predictLength },
        )
    }

    suspend fun generate(
        prompts: List<String>,
        predictLengths: List<Int>,
        maxOutputLines: List<Int> = List(prompts.size) { 0 },
        markLimitAsInvalid: Boolean = false,
    ): List<String>? = generateWithMetrics(
        prompts = prompts,
        predictLengths = predictLengths,
        maxOutputLines = maxOutputLines,
        markLimitAsInvalid = markLimitAsInvalid,
    )?.outputs

    suspend fun generateWithMetrics(
        prompts: List<String>,
        predictLengths: List<Int>,
        maxOutputLines: List<Int> = List(prompts.size) { 0 },
        markLimitAsInvalid: Boolean = false,
    ): Result? {
        if (prompts.size !in 1..MAX_SEQUENCE_COUNT || prompts.any(String::isEmpty)) return null
        if (predictLengths.size != prompts.size || predictLengths.any { it < 1 }) return null
        if (maxOutputLines.size != prompts.size || maxOutputLines.any { it < 0 }) return null
        val nativeResult = withContext(Dispatchers.IO) {
            val outputs = generateNative(
                prompts = prompts.toTypedArray(),
                predictLengths = predictLengths.toIntArray(),
                maxOutputLines = maxOutputLines.toIntArray(),
                markLimitAsInvalid = markLimitAsInvalid,
            )
            outputs to if (outputs != null) lastMetricsNative() else null
        }
        val outputs = nativeResult.first ?: return null
        val outputList = outputs.toList().takeIf { it.size == prompts.size } ?: return null
        return Result(
            outputs = outputList,
            metrics = parseNativeMetrics(nativeResult.second),
        )
    }

    internal fun parseNativeMetrics(values: DoubleArray?): Metrics? {
        if (values == null || values.size != METRICS_FIELD_COUNT || values.any { !it.isFinite() || it < 0.0 }) {
            return null
        }
        val inputTokens = values[4].toInt()
        val outputTokens = values[5].toInt()
        if (inputTokens.toDouble() != values[4] || outputTokens.toDouble() != values[5]) return null
        return Metrics(
            initMs = values[0],
            prefillMs = values[1],
            decodeMs = values[2],
            totalMs = values[3],
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    private external fun generateNative(
        prompts: Array<String>,
        predictLengths: IntArray,
        maxOutputLines: IntArray,
        markLimitAsInvalid: Boolean,
    ): Array<String>?

    private external fun lastMetricsNative(): DoubleArray?

    private const val METRICS_FIELD_COUNT = 6
}
