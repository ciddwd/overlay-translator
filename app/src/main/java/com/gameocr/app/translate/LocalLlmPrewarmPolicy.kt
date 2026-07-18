package com.gameocr.app.translate

internal enum class LocalLlmPrewarmDecision {
    PREWARM,
    SKIP_NON_LOCAL_OR_FALLBACK,
    SKIP_MODEL_NOT_INSTALLED,
}

internal object LocalLlmPrewarmPolicy {
    fun decide(
        routedToLocalModel: Boolean,
        modelInstalled: Boolean,
    ): LocalLlmPrewarmDecision = when {
        !routedToLocalModel -> LocalLlmPrewarmDecision.SKIP_NON_LOCAL_OR_FALLBACK
        !modelInstalled -> LocalLlmPrewarmDecision.SKIP_MODEL_NOT_INSTALLED
        else -> LocalLlmPrewarmDecision.PREWARM
    }
}

internal data class LocalLlmPrewarmResult(
    val decision: LocalLlmPrewarmDecision,
    val modelKind: String? = null,
)
