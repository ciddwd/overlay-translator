package com.gameocr.app.data

import kotlinx.serialization.Serializable

/** A provider selection, not a separate translation engine. */
@Serializable
enum class OpenAiProvider {
    CUSTOM,
    BILIBILI,
}

internal fun Settings.usesBilibiliTranslation(): Boolean =
    translatorEngine == TranslatorEngine.OPENAI && openAiProvider == OpenAiProvider.BILIBILI

/** Same model names at different providers must not reuse each other's cached translations. */
internal fun Settings.openAiCacheModelKey(): String =
    "${openAiProvider.name}|${baseUrl.trim().trimEnd('/')}|$model"

/** Resolve a request snapshot only; never persist these values over the custom OpenAI account. */
internal fun Settings.resolveOpenAiProvider(): Settings = if (usesBilibiliTranslation()) {
    copy(
        baseUrl = "https://index-translate.bilibili.com/v1/",
        apiKey = "index",
        model = "Index-Translate-35B-A3B",
    )
} else {
    this
}
