package com.gameocr.app.glossary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal const val GLOSSARY_IMPORT_MAX_ENTRIES: Int = 5_000
internal const val GLOSSARY_IMPORT_MAX_FILE_BYTES: Int = 2 * 1024 * 1024
internal const val GLOSSARY_IMPORT_MAX_SOURCE_LENGTH: Int = 200
internal const val GLOSSARY_IMPORT_MAX_TARGET_LENGTH: Int = 500

internal data class GlossaryImportEntry(
    val index: Int,
    val source: String,
    val target: String,
)

internal data class GlossaryImportDocument(
    val entries: List<GlossaryImportEntry>,
)

internal class GlossaryImportFormatException(message: String) : IllegalArgumentException(message)

internal object GlossaryImportJson {
    private const val FORMAT = "overlay-translator.glossary"
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(content: String): GlossaryImportDocument {
        val root = runCatching {
            json.parseToJsonElement(content.removePrefix("\uFEFF"))
        }.getOrElse { error ->
            throw GlossaryImportFormatException(error.message ?: "Invalid JSON.")
        }
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> parseWrappedRows(root)
            else -> throw GlossaryImportFormatException("Glossary JSON must be an object or array.")
        }
        if (rows.size > GLOSSARY_IMPORT_MAX_ENTRIES) {
            throw GlossaryImportFormatException(
                "Glossary JSON contains more than $GLOSSARY_IMPORT_MAX_ENTRIES entries."
            )
        }
        return GlossaryImportDocument(
            entries = rows.mapIndexed { zeroBasedIndex, element ->
                parseEntry(zeroBasedIndex + 1, element)
            },
        )
    }

    private fun parseWrappedRows(root: JsonObject): JsonArray {
        val format = root.stringValue("format")
        if (format != FORMAT) {
            throw GlossaryImportFormatException("Unsupported glossary format.")
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        if (version != VERSION) {
            throw GlossaryImportFormatException("Unsupported glossary version.")
        }
        return root["terms"] as? JsonArray
            ?: throw GlossaryImportFormatException("Glossary terms must be an array.")
    }

    private fun parseEntry(index: Int, element: JsonElement): GlossaryImportEntry {
        val row = element as? JsonObject
            ?: return GlossaryImportEntry(index = index, source = "", target = "")
        return GlossaryImportEntry(
            index = index,
            source = row.stringValue("source") ?: row.stringValue("原文").orEmpty(),
            target = row.stringValue("target") ?: row.stringValue("译文").orEmpty(),
        )
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.takeIf(JsonPrimitive::isString)?.content
    }
}

enum class GlossaryImportConflictPolicy {
    SKIP,
    OVERWRITE,
}

internal data class GlossaryImportOptions(
    val scopePackage: String,
    val appLabel: String,
    val sourceLang: String,
    val targetLang: String,
    val category: GlossaryTermCategory,
    val caseSensitive: Boolean,
    val enabled: Boolean,
    val conflictPolicy: GlossaryImportConflictPolicy,
)

internal enum class GlossaryImportRowStatus {
    READY,
    WILL_OVERWRITE,
    WILL_SKIP,
    DUPLICATE,
    FILE_CONFLICT,
    DESELECTED,
    EMPTY_SOURCE,
    EMPTY_TARGET,
    SOURCE_TOO_LONG,
    TARGET_TOO_LONG,
}

internal data class GlossaryImportPreviewRow(
    val entry: GlossaryImportEntry,
    val status: GlossaryImportRowStatus,
    val selected: Boolean,
    val selectable: Boolean,
    val existingTarget: String? = null,
) {
    val willImport: Boolean
        get() = status == GlossaryImportRowStatus.READY ||
            status == GlossaryImportRowStatus.WILL_OVERWRITE
}

internal data class GlossaryImportPreview(
    val rows: List<GlossaryImportPreviewRow>,
) {
    val importableCount: Int = rows.count(GlossaryImportPreviewRow::willImport)
    val overwriteCount: Int = rows.count { it.status == GlossaryImportRowStatus.WILL_OVERWRITE }
    val skipCount: Int = rows.count {
        it.status == GlossaryImportRowStatus.WILL_SKIP ||
            it.status == GlossaryImportRowStatus.DUPLICATE ||
            it.status == GlossaryImportRowStatus.DESELECTED
    }
    val invalidCount: Int = rows.count {
        it.status == GlossaryImportRowStatus.EMPTY_SOURCE ||
            it.status == GlossaryImportRowStatus.EMPTY_TARGET ||
            it.status == GlossaryImportRowStatus.SOURCE_TOO_LONG ||
            it.status == GlossaryImportRowStatus.TARGET_TOO_LONG
    }
    val hasFileConflicts: Boolean = rows.any { it.status == GlossaryImportRowStatus.FILE_CONFLICT }
    val canImport: Boolean = importableCount > 0 && !hasFileConflicts
}

internal object GlossaryImportPreviewPolicy {
    fun defaultSelection(document: GlossaryImportDocument): Set<Int> = document.entries
        .asSequence()
        .filter { baseValidationStatus(it) == null }
        .map(GlossaryImportEntry::index)
        .toSet()

    fun preview(
        document: GlossaryImportDocument,
        existingTerms: List<GlossaryTermEntity>,
        options: GlossaryImportOptions,
        selectedIndices: Set<Int>,
    ): GlossaryImportPreview {
        val selectedValidEntries = document.entries.filter { entry ->
            entry.index in selectedIndices && baseValidationStatus(entry) == null
        }
        val groups = selectedValidEntries.groupBy { entry ->
            normalizeGlossaryTerm(entry.source, options.caseSensitive)
        }
        val existingBySource = existingTerms.asSequence()
            .filter { term ->
                term.scopePackage == options.scopePackage &&
                    term.sourceLang.equals(options.sourceLang, ignoreCase = true) &&
                    term.targetLang.equals(options.targetLang, ignoreCase = true) &&
                    term.caseSensitive == options.caseSensitive
            }
            .associateBy { term ->
                normalizeGlossaryTerm(term.sourceTerm, options.caseSensitive)
            }
        val stablePreviewPriorities = stablePreviewPriorities(
            document = document,
            existingBySource = existingBySource,
            options = options,
        )

        val rows = document.entries.map { entry ->
                val invalid = baseValidationStatus(entry)
                if (invalid != null) {
                    return@map GlossaryImportPreviewRow(
                        entry = entry,
                        status = invalid,
                        selected = false,
                        selectable = false,
                    )
                }
                if (entry.index !in selectedIndices) {
                    return@map GlossaryImportPreviewRow(
                        entry = entry,
                        status = GlossaryImportRowStatus.DESELECTED,
                        selected = false,
                        selectable = true,
                    )
                }

                val key = normalizeGlossaryTerm(entry.source, options.caseSensitive)
                val selectedGroup = groups.getValue(key)
                val distinctTargets = selectedGroup.map { it.target.trim() }.distinct()
                if (distinctTargets.size > 1) {
                    return@map GlossaryImportPreviewRow(
                        entry = entry,
                        status = GlossaryImportRowStatus.FILE_CONFLICT,
                        selected = true,
                        selectable = true,
                    )
                }
                if (selectedGroup.first().index != entry.index) {
                    return@map GlossaryImportPreviewRow(
                        entry = entry,
                        status = GlossaryImportRowStatus.DUPLICATE,
                        selected = true,
                        selectable = true,
                    )
                }

                val existing = existingBySource[key]
                val status = when {
                    existing == null -> GlossaryImportRowStatus.READY
                    options.conflictPolicy == GlossaryImportConflictPolicy.OVERWRITE ->
                        GlossaryImportRowStatus.WILL_OVERWRITE
                    else -> GlossaryImportRowStatus.WILL_SKIP
                }
                GlossaryImportPreviewRow(
                    entry = entry,
                    status = status,
                    selected = true,
                    selectable = true,
                    existingTarget = existing?.targetTerm,
                )
            }
        return GlossaryImportPreview(
            rows = rows.sortedWith(
                compareBy<GlossaryImportPreviewRow> {
                    stablePreviewPriorities.getValue(it.entry.index)
                }
                    .thenBy { it.entry.index }
            ),
        )
    }

    fun buildTerms(
        preview: GlossaryImportPreview,
        options: GlossaryImportOptions,
    ): List<GlossaryTermEntity> = preview.rows
        .asSequence()
        .filter(GlossaryImportPreviewRow::willImport)
        .map { row ->
            GlossaryTermEntity(
                scopePackage = options.scopePackage,
                appLabel = options.appLabel,
                sourceLang = options.sourceLang,
                targetLang = options.targetLang,
                sourceTerm = row.entry.source.trim(),
                targetTerm = row.entry.target.trim(),
                category = options.category,
                caseSensitive = options.caseSensitive,
                enabled = options.enabled,
            )
        }
        .toList()

    private fun baseValidationStatus(entry: GlossaryImportEntry): GlossaryImportRowStatus? = when {
        entry.source.isBlank() -> GlossaryImportRowStatus.EMPTY_SOURCE
        entry.target.isBlank() -> GlossaryImportRowStatus.EMPTY_TARGET
        entry.source.trim().length > GLOSSARY_IMPORT_MAX_SOURCE_LENGTH ->
            GlossaryImportRowStatus.SOURCE_TOO_LONG
        entry.target.trim().length > GLOSSARY_IMPORT_MAX_TARGET_LENGTH ->
            GlossaryImportRowStatus.TARGET_TOO_LONG
        else -> null
    }

    /**
     * Keeps the preview order stable while the user selects or deselects rows. The initial
     * review priority is derived from the file, database and import options, never from the
     * transient checkbox state.
     */
    private fun stablePreviewPriorities(
        document: GlossaryImportDocument,
        existingBySource: Map<String, GlossaryTermEntity>,
        options: GlossaryImportOptions,
    ): Map<Int, Int> {
        val validGroups = document.entries
            .filter { baseValidationStatus(it) == null }
            .groupBy { normalizeGlossaryTerm(it.source, options.caseSensitive) }

        return document.entries.associate { entry ->
            val validationStatus = baseValidationStatus(entry)
            val status = if (validationStatus != null) {
                validationStatus
            } else {
                val key = normalizeGlossaryTerm(entry.source, options.caseSensitive)
                val group = validGroups.getValue(key)
                when {
                    group.map { it.target.trim() }.distinct().size > 1 ->
                        GlossaryImportRowStatus.FILE_CONFLICT
                    group.first().index != entry.index -> GlossaryImportRowStatus.DUPLICATE
                    existingBySource[key] != null &&
                        options.conflictPolicy == GlossaryImportConflictPolicy.OVERWRITE ->
                        GlossaryImportRowStatus.WILL_OVERWRITE
                    existingBySource[key] != null -> GlossaryImportRowStatus.WILL_SKIP
                    else -> GlossaryImportRowStatus.READY
                }
            }
            entry.index to previewPriority(status)
        }
    }

    private fun previewPriority(status: GlossaryImportRowStatus): Int = when (status) {
        GlossaryImportRowStatus.FILE_CONFLICT -> 0
        GlossaryImportRowStatus.EMPTY_SOURCE,
        GlossaryImportRowStatus.EMPTY_TARGET,
        GlossaryImportRowStatus.SOURCE_TOO_LONG,
        GlossaryImportRowStatus.TARGET_TOO_LONG -> 1
        GlossaryImportRowStatus.WILL_OVERWRITE,
        GlossaryImportRowStatus.WILL_SKIP,
        GlossaryImportRowStatus.DUPLICATE -> 2
        GlossaryImportRowStatus.READY -> 3
        GlossaryImportRowStatus.DESELECTED -> 4
    }
}

data class GlossaryImportCommitResult(
    val inserted: Int,
    val overwritten: Int,
    val skipped: Int,
) {
    val changed: Int get() = inserted + overwritten
}
