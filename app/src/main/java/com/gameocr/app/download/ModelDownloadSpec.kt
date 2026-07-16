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

data class ModelDownloadTerminalRecord(
    val specs: List<ModelDownloadSpec>,
    val succeeded: Boolean,
    val status: String,
    val error: String,
    val file: String,
    val downloaded: Long,
    val total: Long,
    val finishedAt: Long,
    val ownerPresetId: String?,
)

internal fun splitModelDownloadRequests(
    specs: List<ModelDownloadSpec>,
): List<List<ModelDownloadSpec>> =
    specs.distinct().map(::listOf)

internal fun latestUnresolvedModelDownloadFailure(
    records: List<ModelDownloadTerminalRecord>,
    activeRequestKeys: Set<String> = emptySet(),
): ModelDownloadTerminalRecord? {
    val latestSuccessAt = records.asSequence()
        .filter { it.succeeded }
        .flatMap { record -> record.specs.asSequence().map { spec -> spec to record.finishedAt } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, finishedTimes) -> finishedTimes.maxOrNull() ?: Long.MIN_VALUE }
    val activeSpecs = activeRequestKeys.asSequence()
        .mapNotNull { requestKey ->
            requestKey
                .takeIf { ',' !in it }
                ?.let(ModelDownloadSpec::decode)
        }
        .toSet()

    return records.asSequence()
        .filterNot { it.succeeded }
        .mapNotNull { failure ->
            val unresolvedSpecs = failure.specs.distinct().filter { spec ->
                spec !in activeSpecs &&
                    (latestSuccessAt[spec] ?: Long.MIN_VALUE) <= failure.finishedAt
            }
            failure.copy(specs = unresolvedSpecs).takeIf { unresolvedSpecs.isNotEmpty() }
        }
        .maxByOrNull { it.finishedAt }
}

object ModelDownloadWorkPolicy {
    const val WORK_TAG = "model-download"
    const val MAX_ATTEMPTS = 3
    private const val OWNER_TAG_PREFIX = "$WORK_TAG-owner:"

    fun uniqueWorkName(specs: List<ModelDownloadSpec>): String =
        "$WORK_TAG:${specs.joinToString(",") { it.encode() }}"

    fun requestKey(specs: List<ModelDownloadSpec>): String =
        specs.joinToString(",") { it.encode() }

    fun ownerTag(presetId: String): String = "$OWNER_TAG_PREFIX$presetId"

    fun ownerPresetId(tags: Set<String>): String? = tags.asSequence()
        .filter { it.startsWith(OWNER_TAG_PREFIX) }
        .map { it.removePrefix(OWNER_TAG_PREFIX) }
        .firstOrNull { it.isNotBlank() }

    /** WorkManager's runAttemptCount starts at zero. */
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS - 1
}
