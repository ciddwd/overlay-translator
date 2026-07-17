package com.gameocr.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent-prompt multi-sequence generation backed by one batched llama_decode loop. */
internal object LlamaMultiSequence {
    const val MAX_SEQUENCE_COUNT = 8

    suspend fun generate(prompts: List<String>, predictLength: Int): List<String>? {
        if (prompts.size !in 2..MAX_SEQUENCE_COUNT || prompts.any(String::isEmpty)) return null
        val outputs = withContext(Dispatchers.IO) {
            generateNative(prompts.toTypedArray(), predictLength.coerceAtLeast(1))
        } ?: return null
        return outputs.toList().takeIf { it.size == prompts.size }
    }

    private external fun generateNative(prompts: Array<String>, predictLength: Int): Array<String>?
}
