package com.gameocr.app.llm

/** One normalized initialization contract shared by the holder, estimate and native boundary. */
object LocalModelInitializationPolicy {
    fun nativeEnvironment(config: LocalModelConfig): Map<String, String> {
        val value = config.normalized()
        return linkedMapOf(
            "GAMEOCR_CONTEXT_TOKENS" to value.contextTokens.toString(),
            "GAMEOCR_BATCH_TOKENS" to value.batchTokens.toString(),
            "GAMEOCR_MICRO_BATCH_TOKENS" to value.microBatchTokens.toString(),
            "GAMEOCR_LOAD_MODE" to value.loadMode,
            "GAMEOCR_CACHE_TYPE_K" to value.cacheTypeK,
            "GAMEOCR_CACHE_TYPE_V" to value.cacheTypeV,
            "GAMEOCR_FLASH_ATTENTION" to value.flashAttention,
            "GAMEOCR_GPU_LAYERS" to value.gpuLayers.toString(),
        )
    }
}

/** llama.cpp `llama_load_mode` names, exactly as `llama_load_mode_from_str` accepts them. */
object LocalModelLoadMode {
    /** Memory-maps, unless a selected GPU backend cannot map files; then reads the whole file. */
    const val AUTO = "auto"
    const val MMAP = "mmap"
    const val MMAP_MLOCK = "mmap+mlock"
    const val MLOCK = "mlock"
    const val NONE = "none"
    /** Unbuffered reads; llama.cpp falls back to buffered reads where O_DIRECT is refused. */
    const val DIRECT_IO = "dio"

    val ALL = listOf(AUTO, MMAP, MMAP_MLOCK, MLOCK, NONE, DIRECT_IO)

    /** The former default (mapping on, lock off) becomes [AUTO], which also maps on CPU. */
    fun fromLegacy(useMmap: Boolean?, useMlock: Boolean?): String {
        val mmap = useMmap ?: true
        val mlock = useMlock ?: false
        return when {
            mmap && mlock -> MMAP_MLOCK
            mmap -> AUTO
            mlock -> MLOCK
            else -> NONE
        }
    }
}
