package com.gameocr.app.service

internal data class TranslationOutputDecision(
    val text: String,
    val failed: Boolean,
)

internal enum class EmptyTranslationAction {
    ACCEPT,
    RETRY,
    FAIL,
}

internal object TranslationOutputPolicy {
    fun action(
        output: String?,
        retryEnabled: Boolean,
        attempt: Int,
    ): EmptyTranslationAction = when {
        !output.isNullOrBlank() -> EmptyTranslationAction.ACCEPT
        retryEnabled && attempt == 0 -> EmptyTranslationAction.RETRY
        else -> EmptyTranslationAction.FAIL
    }

    fun resolve(output: String?, failureText: String): TranslationOutputDecision =
        if (output.isNullOrBlank()) {
            TranslationOutputDecision(failureText, failed = true)
        } else {
            TranslationOutputDecision(output, failed = false)
        }
}
