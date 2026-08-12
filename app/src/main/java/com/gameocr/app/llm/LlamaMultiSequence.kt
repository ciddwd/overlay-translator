package com.gameocr.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent-prompt multi-sequence generation backed by one batched llama_decode loop. */
internal object LlamaMultiSequence {
    const val MAX_SEQUENCE_COUNT = 8
    const val TOKEN_LIMIT_SENTINEL = "<|gameocr_token_limit|>"
    const val LINE_LIMIT_SENTINEL = "<|gameocr_line_limit|>"

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
    ): List<String>? {
        if (prompts.size !in 1..MAX_SEQUENCE_COUNT || prompts.any(String::isEmpty)) return null
        if (predictLengths.size != prompts.size || predictLengths.any { it < 1 }) return null
        if (maxOutputLines.size != prompts.size || maxOutputLines.any { it < 0 }) return null
        val outputs = withContext(Dispatchers.IO) {
            generateNative(
                prompts = prompts.toTypedArray(),
                predictLengths = predictLengths.toIntArray(),
                maxOutputLines = maxOutputLines.toIntArray(),
                markLimitAsInvalid = markLimitAsInvalid,
            )
        } ?: return null
        return outputs.toList().takeIf { it.size == prompts.size }
    }

    private external fun generateNative(
        prompts: Array<String>,
        predictLengths: IntArray,
        maxOutputLines: IntArray,
        markLimitAsInvalid: Boolean,
    ): Array<String>?
}
