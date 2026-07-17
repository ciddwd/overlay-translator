package com.gameocr.app.llm

import com.gameocr.app.util.CpuThreadPolicy

internal data class LocalLlmThreadConfig(
    val generationThreads: Int,
    val promptThreads: Int,
)

/**
 * Separates llama.cpp token-generation threads (TG / n_threads) from prompt and batch
 * threads (PP / n_threads_batch). The requested TG is a build-time A/B input; both values
 * are capped by the device CPU policy so low-core devices cannot be over-subscribed.
 */
internal object LocalLlmThreadPolicy {
    const val GENERATION_THREADS_ENV = "GAMEOCR_GENERATION_THREADS"
    const val PROMPT_THREADS_ENV = "GAMEOCR_PROMPT_THREADS"

    fun select(
        availableProcessors: Int,
        requestedGenerationThreads: Int,
    ): LocalLlmThreadConfig {
        val promptThreads = CpuThreadPolicy.select(availableProcessors)
        return LocalLlmThreadConfig(
            generationThreads = requestedGenerationThreads.coerceIn(1, promptThreads),
            promptThreads = promptThreads,
        )
    }

    fun nativeEnvironment(config: LocalLlmThreadConfig): Map<String, String> = linkedMapOf(
        GENERATION_THREADS_ENV to config.generationThreads.toString(),
        PROMPT_THREADS_ENV to config.promptThreads.toString(),
    )
}
