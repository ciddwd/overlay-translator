package com.gameocr.app.service

internal data class TranslationOutputDecision(
    val text: String,
    val failed: Boolean,
)

internal enum class TranslationRetryAction {
    ACCEPT,
    RETRY,
    FAIL,
}

internal object TranslationOutputPolicy {
    fun shouldRetryInCaller(
        settingEnabled: Boolean,
        translatorHandlesRetry: Boolean,
    ): Boolean = settingEnabled && !translatorHandlesRetry

    fun action(
        output: String?,
        retryEnabled: Boolean,
        attempt: Int,
    ): TranslationRetryAction = when {
        !output.isNullOrBlank() -> TranslationRetryAction.ACCEPT
        retryEnabled && attempt == 0 -> TranslationRetryAction.RETRY
        else -> TranslationRetryAction.FAIL
    }

    fun resolve(output: String?, failureText: String): TranslationOutputDecision =
        if (output.isNullOrBlank()) {
            TranslationOutputDecision(failureText, failed = true)
        } else {
            TranslationOutputDecision(output, failed = false)
        }
}
