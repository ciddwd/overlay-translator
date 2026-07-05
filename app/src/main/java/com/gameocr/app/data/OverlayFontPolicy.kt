package com.gameocr.app.data

import java.util.Locale

object OverlayFontPolicy {
    const val MAX_FONT_BYTES: Long = 50L * 1024L * 1024L
    const val FONT_DIR_NAME: String = "overlay_fonts"
    val OPEN_DOCUMENT_MIME_TYPES: Array<String> = arrayOf(
        "font/ttf",
        "application/x-font-ttf",
        "application/octet-stream"
    )

    private val storedFileNameRegex = Regex("[a-f0-9]{64}\\.ttf")

    fun sanitizeDisplayName(rawName: String?): String {
        val leaf = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()

        return leaf
            .filter { it >= ' ' && it != '/' && it != '\\' }
            .take(120)
            .ifBlank { "font.ttf" }
    }

    fun validateCandidate(displayName: String?, byteCount: Long): OverlayFontImportError? {
        val cleanName = sanitizeDisplayName(displayName)
        if (!cleanName.lowercase(Locale.US).endsWith(".ttf")) {
            return OverlayFontImportError.UNSUPPORTED_EXTENSION
        }
        if (byteCount <= 0L) {
            return OverlayFontImportError.EMPTY_FILE
        }
        if (byteCount > MAX_FONT_BYTES) {
            return OverlayFontImportError.TOO_LARGE
        }
        return null
    }

    fun storedFileNameForSha256(sha256Hex: String): String {
        val normalized = sha256Hex.lowercase(Locale.US)
        require(normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "sha256Hex must be 64 lowercase hex characters"
        }
        return "$normalized.ttf"
    }

    fun normalizeStoredFileName(fileName: String): String? {
        val clean = sanitizeDisplayName(fileName).lowercase(Locale.US)
        return clean.takeIf { storedFileNameRegex.matches(it) }
    }

    fun normalizeImportedFonts(fonts: List<OverlayFontEntry>): List<OverlayFontEntry> {
        val seen = linkedSetOf<String>()
        return fonts.mapNotNull { font ->
            val fileName = normalizeStoredFileName(font.fileName) ?: return@mapNotNull null
            if (!seen.add(fileName)) return@mapNotNull null
            OverlayFontEntry(
                fileName = fileName,
                displayName = sanitizeDisplayName(font.displayName)
            )
        }
    }

    fun upsertImportedFont(
        fonts: List<OverlayFontEntry>,
        fileName: String,
        displayName: String
    ): List<OverlayFontEntry> {
        val normalizedFileName = normalizeStoredFileName(fileName) ?: return normalizeImportedFonts(fonts)
        val entry = OverlayFontEntry(
            fileName = normalizedFileName,
            displayName = sanitizeDisplayName(displayName)
        )
        val normalizedFonts = normalizeImportedFonts(fonts)
        var replaced = false
        val updated = normalizedFonts.map { font ->
            if (font.fileName == normalizedFileName) {
                replaced = true
                entry
            } else {
                font
            }
        }
        return if (replaced) updated else updated + entry
    }

    fun removeImportedFont(
        fonts: List<OverlayFontEntry>,
        fileName: String
    ): List<OverlayFontEntry> {
        val normalizedFileName = normalizeStoredFileName(fileName) ?: return normalizeImportedFonts(fonts)
        return normalizeImportedFonts(fonts).filterNot { it.fileName == normalizedFileName }
    }
}

enum class OverlayFontImportError {
    UNSUPPORTED_EXTENSION,
    EMPTY_FILE,
    TOO_LARGE,
    UNREADABLE,
    INVALID_FONT,
    COPY_FAILED
}

sealed class OverlayFontImportResult {
    data class Success(
        val fileName: String,
        val displayName: String
    ) : OverlayFontImportResult()

    data class Failure(
        val error: OverlayFontImportError
    ) : OverlayFontImportResult()
}
