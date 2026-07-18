package com.gameocr.app.llm

/**
 * Read-only tokenizer/context metrics exported by the local ai-chat JNI binding.
 *
 * Callers must ensure the model is loaded and hold [LlamaEngineHolder.inferenceMutex], because
 * these methods reuse the binding's process-global model and context.
 */
internal object LlamaPromptMetrics {
    external fun countTextTokens(text: String): Int

    external fun countUserPromptTokens(prompt: String): Int

    external fun contextSizeTokens(): Int

    external fun batchSizeTokens(): Int

    external fun sequenceCapacity(): Int

    external fun systemPromptTokens(): Int
}
