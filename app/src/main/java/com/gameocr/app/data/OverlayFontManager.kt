package com.gameocr.app.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

internal data class StagedOverlayFont(
    val entry: OverlayFontEntry,
    val file: File,
)

internal data class OverlayFontCommit(
    val entry: OverlayFontEntry,
    val target: File,
    val backup: File?,
    val modified: Boolean,
)

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

    internal fun transferFileFor(fileName: String): File? {
        val normalized = OverlayFontPolicy.normalizeStoredFileName(fileName) ?: return null
        return File(fontDir, normalized).takeIf { it.isFile }
    }

    internal fun existingFontEntries(fonts: List<OverlayFontEntry>): List<OverlayFontEntry> =
        OverlayFontPolicy.normalizeImportedFonts(fonts).filter { entry ->
            val file = File(fontDir, entry.fileName)
            file.isFile && canLoadTypeface(file)
        }

    internal fun installTransferredFont(
        font: SettingsBundleFont,
        input: InputStream,
    ): OverlayFontEntry {
        val stagingDir = File(context.cacheDir, "settings-font-${System.nanoTime()}")
        require(stagingDir.mkdirs()) { "Could not create the font staging directory." }
        return try {
            val staged = stageTransferredFont(font, input, stagingDir)
            val commit = commitTransferredFont(staged)
            finishTransferredFont(commit)
            commit.entry
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    internal fun stageTransferredFont(
        font: SettingsBundleFont,
        input: InputStream,
        stagingDir: File,
    ): StagedOverlayFont {
        val normalizedFileName = requireNotNull(
            OverlayFontPolicy.normalizeStoredFileName(font.fileName),
        ) { "Transferred font has an invalid file name." }
        val displayName = OverlayFontPolicy.sanitizeDisplayName(font.displayName)
        require(font.byteCount in 1..OverlayFontPolicy.MAX_FONT_BYTES) {
            "Transferred font has an invalid size."
        }
        require(stagingDir.isDirectory || stagingDir.mkdirs()) {
            "Could not create the font staging directory."
        }
        val tmp = File(stagingDir, "font-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        try {
            tmp.outputStream().buffered().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    byteCount += count
                    require(byteCount <= OverlayFontPolicy.MAX_FONT_BYTES) {
                        "Transferred font is too large."
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            require(byteCount == font.byteCount) { "Transferred font size does not match its manifest." }
            require(OverlayFontPolicy.storedFileNameForSha256(digest.digest().toHexString()) == normalizedFileName) {
                "Transferred font checksum does not match its file name."
            }
            require(canLoadTypeface(tmp)) { "Transferred font cannot be loaded." }
            return StagedOverlayFont(
                entry = OverlayFontEntry(normalizedFileName, displayName),
                file = tmp,
            )
        } catch (error: Throwable) {
            runCatching { tmp.delete() }
            throw error
        }
    }

    internal fun commitTransferredFont(staged: StagedOverlayFont): OverlayFontCommit {
        val dir = fontDir
        require(dir.exists() || dir.mkdirs()) { "Could not create the font directory." }
        val target = File(dir, staged.entry.fileName)
        if (target.isFile && canLoadTypeface(target)) {
            return OverlayFontCommit(staged.entry, target, backup = null, modified = false)
        }

        val backup = target.takeIf(File::exists)?.let { existing ->
            File(staged.file.parentFile, "backup-${staged.entry.fileName}").also { backupFile ->
                existing.copyTo(backupFile, overwrite = true)
            }
        }
        return try {
            staged.file.copyTo(target, overwrite = true)
            require(canLoadTypeface(target)) { "Committed font cannot be loaded." }
            invalidateCache()
            OverlayFontCommit(staged.entry, target, backup, modified = true)
        } catch (error: Throwable) {
            if (backup?.isFile == true) {
                backup.copyTo(target, overwrite = true)
            } else {
                runCatching { target.delete() }
            }
            invalidateCache()
            throw error
        }
    }

    internal fun rollbackTransferredFont(commit: OverlayFontCommit) {
        if (!commit.modified) return
        if (commit.backup?.isFile == true) {
            commit.backup.copyTo(commit.target, overwrite = true)
        } else {
            require(!commit.target.exists() || commit.target.delete()) {
                "Could not roll back imported font ${commit.entry.displayName}."
            }
        }
        invalidateCache()
    }

    internal fun finishTransferredFont(commit: OverlayFontCommit) {
        runCatching { commit.backup?.delete() }
        invalidateCache()
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
