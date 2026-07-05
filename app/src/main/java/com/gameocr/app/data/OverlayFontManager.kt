package com.gameocr.app.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class OverlayFontManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val fontDir: File
        get() = File(context.filesDir, OverlayFontPolicy.FONT_DIR_NAME)

    @Volatile private var cachedFileName: String? = null
    @Volatile private var cachedTypeface: Typeface? = null

    suspend fun importFont(uri: Uri): OverlayFontImportResult = withContext(Dispatchers.IO) {
        val displayName = OverlayFontPolicy.sanitizeDisplayName(queryDisplayName(uri) ?: uri.lastPathSegment)
        OverlayFontPolicy.validateCandidate(displayName, byteCount = 1L)?.let {
            return@withContext OverlayFontImportResult.Failure(it)
        }

        val dir = fontDir
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext OverlayFontImportResult.Failure(OverlayFontImportError.COPY_FAILED)
        }

        val tmp = File(dir, "import-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L

        val copyError = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        if (bytes > OverlayFontPolicy.MAX_FONT_BYTES) {
                            return@withContext failAndDelete(tmp, OverlayFontImportError.TOO_LARGE)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext failAndDelete(tmp, OverlayFontImportError.UNREADABLE)
        }.exceptionOrNull()

        if (copyError != null) {
            Timber.w(copyError, "Failed to import overlay font")
            return@withContext failAndDelete(tmp, OverlayFontImportError.UNREADABLE)
        }

        OverlayFontPolicy.validateCandidate(displayName, bytes)?.let {
            return@withContext failAndDelete(tmp, it)
        }

        if (!canLoadTypeface(tmp)) {
            return@withContext failAndDelete(tmp, OverlayFontImportError.INVALID_FONT)
        }

        val targetName = OverlayFontPolicy.storedFileNameForSha256(digest.digest().toHexString())
        val target = File(dir, targetName)
        val installed = runCatching {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }.isSuccess

        if (!installed) {
            return@withContext failAndDelete(tmp, OverlayFontImportError.COPY_FAILED)
        }

        settingsRepository.update {
            it.copy(
                overlayFontFileName = targetName,
                overlayFontDisplayName = displayName,
                overlayFonts = OverlayFontPolicy.upsertImportedFont(
                    it.overlayFonts,
                    targetName,
                    displayName
                )
            )
        }
        invalidateCache()

        OverlayFontImportResult.Success(
            fileName = targetName,
            displayName = displayName
        )
    }

    suspend fun resetFont() = withContext(Dispatchers.IO) {
        val current = settingsRepository.get()
        val retainedFonts = OverlayFontPolicy.upsertImportedFont(
            current.overlayFonts,
            current.overlayFontFileName,
            current.overlayFontDisplayName
        )
        settingsRepository.update {
            it.copy(
                overlayFontFileName = "",
                overlayFontDisplayName = "",
                overlayFonts = retainedFonts
            )
        }
        invalidateCache()
    }

    suspend fun selectFont(fileName: String, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = OverlayFontPolicy.normalizeStoredFileName(fileName) ?: return@withContext false
        val cleanDisplayName = OverlayFontPolicy.sanitizeDisplayName(displayName)
        val file = File(fontDir, normalized)
        if (!file.isFile || !canLoadTypeface(file)) {
            return@withContext false
        }
        settingsRepository.update {
            it.copy(
                overlayFontFileName = normalized,
                overlayFontDisplayName = cleanDisplayName,
                overlayFonts = OverlayFontPolicy.upsertImportedFont(
                    it.overlayFonts,
                    normalized,
                    cleanDisplayName
                )
            )
        }
        invalidateCache()
        true
    }

    suspend fun deleteFont(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = OverlayFontPolicy.normalizeStoredFileName(fileName) ?: return@withContext false
        val current = settingsRepository.get()
        val nextFonts = OverlayFontPolicy.removeImportedFont(current.overlayFonts, normalized)
        val wasSelected = current.overlayFontFileName == normalized
        val file = File(fontDir, normalized)
        val fileDeleted = !file.exists() || runCatching { file.delete() }
            .onFailure { Timber.w(it, "Failed to delete overlay font: %s", normalized) }
            .getOrDefault(false)
        if (!fileDeleted) return@withContext false

        settingsRepository.update {
            it.copy(
                overlayFontFileName = if (wasSelected) "" else it.overlayFontFileName,
                overlayFontDisplayName = if (wasSelected) "" else it.overlayFontDisplayName,
                overlayFonts = nextFonts
            )
        }
        invalidateCache()
        true
    }

    @Synchronized
    fun typefaceFor(settings: Settings): Typeface? = typefaceFor(settings.overlayFontFileName)

    @Synchronized
    fun typefaceFor(fileName: String): Typeface? {
        val normalized = OverlayFontPolicy.normalizeStoredFileName(fileName) ?: run {
            invalidateCache()
            return null
        }
        cachedTypeface?.let { cached ->
            if (cachedFileName == normalized) return cached
        }

        val file = File(fontDir, normalized)
        if (!file.isFile) {
            invalidateCache()
            return null
        }

        val typeface = runCatching { Typeface.Builder(file).build() }.getOrNull()
        if (typeface == null) {
            Timber.w("Overlay font file exists but cannot be loaded: %s", normalized)
            invalidateCache()
            return null
        }

        cachedFileName = normalized
        cachedTypeface = typeface
        return typeface
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                }
        }.getOrNull()
    }

    private fun canLoadTypeface(file: File): Boolean =
        runCatching { Typeface.Builder(file).build() != null }.getOrDefault(false)

    private fun failAndDelete(file: File, error: OverlayFontImportError): OverlayFontImportResult.Failure {
        runCatching { file.delete() }
        return OverlayFontImportResult.Failure(error)
    }

    private fun invalidateCache() {
        cachedFileName = null
        cachedTypeface = null
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { "%02x".format(it) }
}
