package com.gameocr.app.gallery

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where the images of a new gallery task came from. Stored on the task as [GalleryTranslationTaskEntity.sourceKind]. */
@Serializable
enum class GalleryImportKind { PICKED, ALBUM, FOLDER }

/** What to do when an album or folder holds more than [GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK] images. */
@Serializable
enum class GallerySplitMode { SPLIT, FIRST_ONLY }

@Serializable
data class GalleryImportEntry(
    val uri: String,
    val name: String = "",
    val dateMs: Long = 0L,
)

/**
 * The selection handed from the home screen, task list or album picker to the create screen.
 * [entries] carries metadata for albums and folders in the source provider's order;
 * picked and shared images keep their order and leave it empty.
 */
@Serializable
data class GalleryImportSource(
    val kind: GalleryImportKind = GalleryImportKind.PICKED,
    val name: String = "",
    val entries: List<GalleryImportEntry> = emptyList(),
    val splitMode: GallerySplitMode = GallerySplitMode.SPLIT,
)


internal object GalleryImportPolicy {
    const val MAX_TITLE_LENGTH = 60
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(source: GalleryImportSource): String = json.encodeToString(source)

    fun decode(raw: String?): GalleryImportSource =
        raw?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString<GalleryImportSource>(it) }.getOrNull() }
            ?: GalleryImportSource()

    /**
     * Splits an ordered selection into task-sized parts. Picked images are already capped at
     * the task limit; albums and folders either split into parts of 100 or keep the first 100.
     */
    fun parts(uris: List<String>, kind: GalleryImportKind, splitMode: GallerySplitMode): List<List<String>> {
        val distinct = uris.map(String::trim).filter(String::isNotEmpty).distinct()
        if (distinct.isEmpty()) return emptyList()
        val limit = GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK
        return when {
            kind == GalleryImportKind.PICKED || distinct.size <= limit -> listOf(distinct.take(limit))
            splitMode == GallerySplitMode.SPLIT -> distinct.chunked(limit)
            else -> listOf(distinct.take(limit))
        }
    }

    /** Collapses whitespace and caps the length. Empty means "show the creation date". */
    fun normalizeTitle(input: String): String =
        input.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE_LENGTH).trim()

    fun defaultTitle(source: GalleryImportSource): String = when (source.kind) {
        GalleryImportKind.PICKED -> ""
        GalleryImportKind.ALBUM, GalleryImportKind.FOLDER -> normalizeTitle(source.name)
    }

    /** One title per part: "Name" alone, or "Name (1/2)", "Name (2/2)". Blank stays blank. */
    fun partTitles(baseTitle: String, partCount: Int): List<String> {
        val base = normalizeTitle(baseTitle)
        if (partCount <= 1) return listOf(base)
        if (base.isEmpty()) return List(partCount) { "" }
        return List(partCount) { index ->
            val suffix = " (${index + 1}/$partCount)"
            base.take(MAX_TITLE_LENGTH - suffix.length).trimEnd() + suffix
        }
    }
}

/** The task list and results show the title, falling back to the creation date the list showed before titles existed. */
internal fun galleryTaskDisplayTitle(title: String, createdAtText: String): String =
    title.ifBlank { createdAtText }
