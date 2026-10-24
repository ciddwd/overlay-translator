package com.gameocr.app.llm

import com.gameocr.app.util.CpuThreadPolicy

internal data class LocalLlmThreadConfig(
    val generationThreads: Int,
    val promptThreads: Int,
)

/**
 * Separates llama.cpp token-generation threads (TG / n_threads) from prompt and batch
 * threads (PP / n_threads_batch). Automatic settings use the existing CPU policy; per-model
 * overrides can use up to the actual core count (64 at most), never nonexistent cores.
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

    fun configured(availableProcessors: Int, requestedGenerationThreads: Int,
        overrides: LocalModelConfig?): LocalLlmThreadConfig {
        val automatic = select(availableProcessors, requestedGenerationThreads)
        val value = overrides?.normalized() ?: return automatic
        val cores = availableProcessors.coerceIn(1, 64)
        return LocalLlmThreadConfig(
            value.generationThreads?.coerceIn(1, cores) ?: automatic.generationThreads,
            value.promptThreads?.coerceIn(1, cores) ?: automatic.promptThreads,
        )
    }
}
