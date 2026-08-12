package com.gameocr.app.llm

/**
 * Read-only tokenizer/context metrics exported by the local ai-chat JNI binding.
 *
 * Callers must invoke these methods inside [LlamaEngineHolder.withEngineSession], because they
 * reuse the binding's process-global model, context and chat-template state.
 */
internal object LlamaPromptMetrics {
    external fun countTextTokens(text: String): Int

    external fun countUserPromptTokens(prompt: String): Int

    /** Counts one shared prompt prefix once and every sequence-private suffix separately. */
    external fun effectiveUserPromptBatchTokens(prompts: Array<String>): Int

    external fun contextSizeTokens(): Int

    external fun batchSizeTokens(): Int

    external fun sequenceCapacity(): Int

    external fun systemPromptTokens(): Int
}
