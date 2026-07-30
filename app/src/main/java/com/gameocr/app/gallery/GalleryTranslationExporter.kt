package com.gameocr.app.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import com.gameocr.app.data.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale

data class GalleryExportProgress(
    val completed: Int,
    val total: Int,
    val displayName: String,
)

data class GalleryExportResult(
    val total: Int,
    val exported: Int,
    val failed: Int,
)

@Singleton
class GalleryTranslationExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GalleryTranslationRepository,
    private val imageDecoder: GalleryImageDecoder,
    private val renderer: GalleryTranslatedImageRenderer,
    private val json: Json,
) {
    suspend fun exportTask(
        taskId: String,
        treeUri: Uri,
        onProgress: (GalleryExportProgress) -> Unit,
    ): GalleryExportResult {
        val task = requireNotNull(repository.getTask(taskId)) {
            "The gallery translation task no longer exists."
        }
        val settings = repository.settingsForTask(task)
        require(galleryExportRenderMode(settings) != GalleryExportRenderMode.UNSUPPORTED_FLOATING) {
            "Floating-window tasks do not support translated-image export."
        }
        val items = repository.getItems(taskId)
            .filter { it.status == GalleryItemStatus.SUCCEEDED }
        if (items.isEmpty()) return GalleryExportResult(total = 0, exported = 0, failed = 0)

        val parentDocumentUri = withContext(Dispatchers.IO) {
            require(DocumentsContract.isTreeUri(treeUri)) {
                "The selected destination is not a writable folder."
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                Timber.i(it, "Gallery export destination permission is session-only")
            }
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }

        var exported = 0
        var failed = 0
        items.forEachIndexed { index, item ->
            onProgress(
                GalleryExportProgress(
                    completed = index,
                    total = items.size,
                    displayName = item.displayName,
                )
            )
            try {
                withContext(Dispatchers.IO) {
                    exportItem(
                        item = item,
                        parentDocumentUri = parentDocumentUri,
                        settings = settings,
                    )
                }
                exported++
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failed++
                Timber.w(
                    error,
                    "Gallery translated image export failed task=%s item=%s",
                    taskId,
                    item.id,
                )
            }
            onProgress(
                GalleryExportProgress(
                    completed = index + 1,
                    total = items.size,
                    displayName = item.displayName,
                )
            )
        }
        return GalleryExportResult(
            total = items.size,
            exported = exported,
            failed = failed,
        )
    }

    private fun exportItem(
        item: GalleryTranslationItemEntity,
        parentDocumentUri: Uri,
        settings: Settings,
    ) {
        val segments = json.decodeFromString<List<GalleryTranslationSegment>>(item.segmentsJson)
            .filter { it.translatedText.isNotBlank() }
        require(segments.isNotEmpty()) { "No translated regions to export." }

        var decoded: GalleryDecodedImage? = null
        var rendered: Bitmap? = null
        var encodedFile: File? = null
        var outputUri: Uri? = null
        try {
            decoded = imageDecoder.decode(item)
            rendered = renderer.render(
                source = decoded.bitmap,
                processedWidth = item.processedWidth,
                processedHeight = item.processedHeight,
                segments = segments,
                settings = settings,
            )
            encodedFile = encodeTranslatedPng(rendered)
            outputUri = requireNotNull(
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocumentUri,
                    PNG_MIME_TYPE,
                    galleryTranslatedFileName(item.position, item.displayName),
                )
            ) {
                "The selected folder could not create an output image."
            }
            encodedFile.inputStream().use { input ->
                context.contentResolver.openOutputStream(outputUri, "w").use { output ->
                    requireNotNull(output) { "The output image could not be opened." }
                    input.copyTo(output)
                }
            }
        } catch (error: Throwable) {
            outputUri?.let { uri ->
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }
            }
            throw error
        } finally {
            encodedFile?.let { file ->
                if (file.exists() && !file.delete()) {
                    Timber.w("Gallery export temporary file could not be deleted: %s", file.name)
                }
            }
            rendered
                ?.takeIf { it !== decoded?.bitmap && !it.isRecycled }
                ?.recycle()
            decoded?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun encodeTranslatedPng(rendered: Bitmap): File {
        val outputFile = File.createTempFile(
            TEMP_FILE_PREFIX,
            ".png",
            context.cacheDir,
        )
        try {
            outputFile.outputStream().buffered().use { output ->
                check(rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "The translated image could not be encoded."
                }
            }
            val metadata = galleryExportMetadata()
            ExifInterface(outputFile).apply {
                setAttribute(ExifInterface.TAG_ARTIST, metadata.artist)
                setAttribute(ExifInterface.TAG_SOFTWARE, metadata.software)
                saveAttributes()
            }
            return outputFile
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private companion object {
        const val PNG_MIME_TYPE = "image/png"
        const val TEMP_FILE_PREFIX = "gallery_translated_"
    }
}

internal data class GalleryExportMetadata(
    val artist: String,
    val software: String,
)

internal fun galleryExportMetadata(): GalleryExportMetadata = GalleryExportMetadata(
    artist = GALLERY_EXPORT_CREATOR,
    software = GALLERY_EXPORT_CREATOR,
)

internal const val GALLERY_EXPORT_CREATOR = "屏译 · Screen Translator"

internal fun galleryCanExport(
    status: GalleryTaskStatus,
    successCount: Int,
): Boolean = status !in setOf(
    GalleryTaskStatus.QUEUED,
    GalleryTaskStatus.RUNNING,
    GalleryTaskStatus.WAITING_RETRY,
) && successCount > 0

internal fun galleryTranslatedFileName(
    position: Int,
    displayName: String,
): String {
    val baseName = displayName
        .substringBeforeLast('.', displayName)
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .trim(' ', '.')
        .take(80)
        .ifBlank { "image" }
    return String.format(
        Locale.ROOT,
        "%s_translated_%03d.png",
        baseName,
        position.coerceAtLeast(0) + 1,
    )
}
