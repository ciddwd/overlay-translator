package com.gameocr.app.download

import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.llm.LlmModelKind

enum class ModelDownloadType {
    LLM,
    PADDLE,
    MANGA_OCR,
    ORIENTATION,
}

data class ModelDownloadSpec(
    val type: ModelDownloadType,
    val variant: String = "",
) {
    fun encode(): String = "${type.name}|$variant"

    companion object {
        fun llm(kind: LlmModelKind) = ModelDownloadSpec(ModelDownloadType.LLM, kind.name)
        fun paddle(version: PaddleModelVersion) = ModelDownloadSpec(ModelDownloadType.PADDLE, version.name)
        fun mangaOcr() = ModelDownloadSpec(ModelDownloadType.MANGA_OCR)
        fun orientation() = ModelDownloadSpec(ModelDownloadType.ORIENTATION)

        fun decode(value: String): ModelDownloadSpec? {
            val parts = value.split('|', limit = 2)
            val type = parts.firstOrNull()?.let { name ->
                ModelDownloadType.entries.firstOrNull { it.name == name }
            } ?: return null
            val variant = parts.getOrElse(1) { "" }
            if ((type == ModelDownloadType.LLM || type == ModelDownloadType.PADDLE) && variant.isBlank()) {
                return null
            }
            return ModelDownloadSpec(type, variant)
        }

        fun decodeAll(values: Array<out String>): List<ModelDownloadSpec>? =
            values.map { decode(it) ?: return null }.takeIf { it.isNotEmpty() }
    }
}

object ModelDownloadWorkPolicy {
    const val WORK_TAG = "model-download"
    const val MAX_ATTEMPTS = 3
    private const val OWNER_TAG_PREFIX = "$WORK_TAG-owner:"

    fun uniqueWorkName(specs: List<ModelDownloadSpec>): String =
        "$WORK_TAG:${specs.joinToString(",") { it.encode() }}"

    fun ownerTag(presetId: String): String = "$OWNER_TAG_PREFIX$presetId"

    fun ownerPresetId(tags: Set<String>): String? = tags.asSequence()
        .filter { it.startsWith(OWNER_TAG_PREFIX) }
        .map { it.removePrefix(OWNER_TAG_PREFIX) }
        .firstOrNull { it.isNotBlank() }

    /** WorkManager's runAttemptCount starts at zero. */
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS - 1
}
