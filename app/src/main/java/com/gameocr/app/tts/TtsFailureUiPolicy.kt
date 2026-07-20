package com.gameocr.app.tts

import android.content.Context
import com.gameocr.app.R

private const val MAX_TTS_FAILURE_DETAIL_LENGTH = 160

internal sealed interface TtsFailureUiReason {
    data class LanguageUnavailable(val languageTag: String) : TtsFailureUiReason
    data class Generic(val detail: String) : TtsFailureUiReason
}

internal fun ttsFailureUiReason(error: Throwable): TtsFailureUiReason {
    val causes = generateSequence(error) { current -> current.cause }
    causes.filterIsInstance<SystemTtsLanguageUnavailableException>()
        .firstOrNull()
        ?.let { return TtsFailureUiReason.LanguageUnavailable(it.languageTag) }

    val rawDetail = causes
        .mapNotNull { cause -> cause.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: error.javaClass.simpleName.takeIf(String::isNotBlank)
        ?: "Unknown error"
    val normalized = rawDetail.replace(Regex("\\s+"), " ").trim()
    val detail = if (normalized.length <= MAX_TTS_FAILURE_DETAIL_LENGTH) {
        normalized
    } else {
        normalized.take(MAX_TTS_FAILURE_DETAIL_LENGTH - 1) + "…"
    }
    return TtsFailureUiReason.Generic(detail)
}

internal fun Context.ttsFailureMessage(error: Throwable): String =
    when (val reason = ttsFailureUiReason(error)) {
        is TtsFailureUiReason.LanguageUnavailable -> getString(
            R.string.toast_tts_language_unavailable_format,
            reason.languageTag,
        )
        is TtsFailureUiReason.Generic -> getString(
            R.string.toast_tts_failed_format,
            reason.detail,
        )
    }
