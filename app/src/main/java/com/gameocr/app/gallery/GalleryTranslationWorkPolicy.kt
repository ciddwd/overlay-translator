package com.gameocr.app.gallery

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.TranslatorEngine
import java.io.IOException

internal object GalleryTranslationWorkPolicy {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val MAX_IMAGES_PER_TASK = 100
    const val MAX_RETRY_ATTEMPTS = 3
    const val FOREGROUND_IMAGE_THRESHOLD = 10
    const val WORK_TAG = "gallery_translation"

    fun uniqueWorkName(taskId: String): String = "gallery_translation_$taskId"

    fun normalizeSelection(uriStrings: List<String>): List<String> =
        uriStrings.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_IMAGES_PER_TASK)
            .toList()

    fun mergeSelection(current: List<String>, additions: List<String>): List<String> =
        normalizeSelection(current + additions)

    fun removeSelection(current: List<String>, uriString: String): List<String> {
        val target = uriString.trim()
        return normalizeSelection(current).filterNot { it == target }
    }

    fun sharedImageSelection(
        action: String?,
        mimeType: String?,
        singleUri: String?,
        multipleUris: List<String>,
    ): List<String> {
        if (mimeType?.startsWith("image/", ignoreCase = true) != true) return emptyList()
        val candidates = when (action) {
            ACTION_SEND -> listOfNotNull(singleUri)
            ACTION_SEND_MULTIPLE -> multipleUris
            else -> emptyList()
        }
        return normalizeSelection(candidates)
    }

    fun requiresNetwork(
        ocrEngine: OcrEngineKind,
        translatorEngine: TranslatorEngine,
    ): Boolean = ocrEngine in networkOcrEngines || translatorEngine in networkTranslators

    fun shouldUseForeground(
        imageCount: Int,
        translatorEngine: TranslatorEngine,
    ): Boolean =
        imageCount >= FOREGROUND_IMAGE_THRESHOLD ||
            translatorEngine == TranslatorEngine.LOCAL_SAKURA ||
            translatorEngine == TranslatorEngine.LOCAL_HY_MT2

    fun terminalStatus(
        progress: GalleryTaskProgress,
        canceled: Boolean,
    ): GalleryTaskStatus = when {
        canceled -> GalleryTaskStatus.CANCELED
        progress.total <= 0 -> GalleryTaskStatus.FAILED
        progress.completed < progress.total -> GalleryTaskStatus.RUNNING
        progress.succeeded == progress.total -> GalleryTaskStatus.SUCCEEDED
        progress.failed == progress.total -> GalleryTaskStatus.FAILED
        else -> GalleryTaskStatus.PARTIAL
    }

    fun shouldRetry(error: Throwable, attemptCount: Int): Boolean {
        if (attemptCount >= MAX_RETRY_ATTEMPTS) return false
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        if (causes.any { it is IOException }) return true
        val message = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return retryableMarkers.any(message::contains)
    }

    fun shouldRetryFailedTranslation(enabled: Boolean, attemptCount: Int): Boolean =
        enabled && attemptCount == FIRST_RETRY_ATTEMPT

    private val networkOcrEngines = setOf(
        OcrEngineKind.UMI_OCR,
        OcrEngineKind.LUNA_OCR,
        OcrEngineKind.BAIDU,
        OcrEngineKind.TENCENT,
        OcrEngineKind.YOUDAO,
        OcrEngineKind.PADDLE_AI_STUDIO,
    )

    private val networkTranslators = setOf(
        TranslatorEngine.OPENAI,
        TranslatorEngine.ANTHROPIC,
        TranslatorEngine.DEEPL,
        TranslatorEngine.YOUDAO_PICTRANS,
        TranslatorEngine.GOOGLE,
        TranslatorEngine.VOLC,
        TranslatorEngine.BAIDU_FANYI,
        TranslatorEngine.TENCENT,
    )

    private val retryableMarkers = listOf(
        "timeout",
        "timed out",
        "http 408",
        "http 425",
        "http 429",
        "http 500",
        "http 502",
        "http 503",
        "http 504",
        "too many requests",
        "temporarily unavailable",
        "connection reset",
        "connection refused",
    )

    private const val FIRST_RETRY_ATTEMPT = 1
}
