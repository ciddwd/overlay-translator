package com.gameocr.app.llm

/** One normalized initialization contract shared by the holder, estimate and native boundary. */
object LocalModelInitializationPolicy {
    fun nativeEnvironment(config: LocalModelConfig): Map<String, String> {
        val value = config.normalized()
        return linkedMapOf(
            "GAMEOCR_CONTEXT_TOKENS" to value.contextTokens.toString(),
            "GAMEOCR_BATCH_TOKENS" to value.batchTokens.toString(),
            "GAMEOCR_MICRO_BATCH_TOKENS" to value.microBatchTokens.toString(),
            "GAMEOCR_USE_MMAP" to if (value.useMmap) "1" else "0",
            "GAMEOCR_USE_MLOCK" to if (value.useMlock) "1" else "0",
            "GAMEOCR_CACHE_TYPE_K" to value.cacheTypeK,
            "GAMEOCR_CACHE_TYPE_V" to value.cacheTypeV,
            "GAMEOCR_FLASH_ATTENTION" to value.flashAttention,
            "GAMEOCR_GPU_LAYERS" to value.gpuLayers.toString(),
        )
    }
}
