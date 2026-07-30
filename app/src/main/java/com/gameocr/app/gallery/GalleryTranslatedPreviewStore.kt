package com.gameocr.app.gallery

import android.content.Context
import android.graphics.Bitmap
import com.gameocr.app.data.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber

@Singleton
class GalleryTranslatedPreviewStore @Inject constructor(
    @ApplicationContext context: Context,
    private val imageDecoder: GalleryImageDecoder,
    private val renderer: GalleryTranslatedImageRenderer,
    private val json: Json,
) {
    private val root = File(context.cacheDir, CACHE_ROOT)
    private val generationMutex = Mutex()

    suspend fun storeTranslatedPreview(
        item: GalleryTranslationItemEntity,
        source: Bitmap,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
        settings: Settings,
    ): Boolean = safely(item, "store") {
        if (galleryExportRenderMode(settings) == GalleryExportRenderMode.UNSUPPORTED_FLOATING) {
            return@safely false
        }
        withContext(Dispatchers.IO) {
            generationMutex.withLock {
                writePreview(
                    item = item,
                    source = source,
                    processedWidth = processedWidth,
                    processedHeight = processedHeight,
                    segments = segments,
                    settings = settings,
                )
            }
        }
        true
    } ?: false

    suspend fun loadTranslatedThumbnail(
        item: GalleryTranslationItemEntity,
        settings: Settings,
    ): Bitmap? = loadTranslated(item, settings, thumbnail = true)

    suspend fun loadTranslatedPreview(
        item: GalleryTranslationItemEntity,
        settings: Settings,
    ): Bitmap? = loadTranslated(item, settings, thumbnail = false)

    fun deleteTask(taskId: String) {
        File(root, taskId).deleteRecursively()
    }

    private suspend fun loadTranslated(
        item: GalleryTranslationItemEntity,
        settings: Settings,
        thumbnail: Boolean,
    ): Bitmap? = safely(item, if (thumbnail) "thumbnail" else "preview") {
        if (
            !galleryResultUsesTranslatedPreview(
                status = item.status,
                renderMode = galleryExportRenderMode(settings),
            )
        ) {
            return@safely null
        }
        val previewFile = withContext(Dispatchers.IO) {
            ensurePreview(item, settings)
        } ?: return@safely null
        withContext(Dispatchers.IO) {
            if (thumbnail) {
                imageDecoder.decodeThumbnail(
                    sourceUri = "",
                    localPath = previewFile.absolutePath,
                )
            } else {
                imageDecoder.decodePreview(
                    sourceUri = "",
                    localPath = previewFile.absolutePath,
                )
            }
        }
    }

    private suspend fun ensurePreview(
        item: GalleryTranslationItemEntity,
        settings: Settings,
    ): File? = generationMutex.withLock {
        val target = previewFile(item)
        if (target.isFile && target.length() > 0L) return@withLock target

        val segments = json.decodeFromString<List<GalleryTranslationSegment>>(item.segmentsJson)
            .filter { it.translatedText.isNotBlank() }
        if (segments.isEmpty()) return@withLock null

        var decoded: GalleryDecodedImage? = null
        try {
            decoded = imageDecoder.decode(item)
            writePreview(
                item = item,
                source = decoded.bitmap,
                processedWidth = item.processedWidth,
                processedHeight = item.processedHeight,
                segments = segments,
                settings = settings,
            )
        } finally {
            decoded?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun writePreview(
        item: GalleryTranslationItemEntity,
        source: Bitmap,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
        settings: Settings,
    ): File {
        val target = previewFile(item)
        val directory = requireNotNull(target.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        val temporary = File(directory, "${item.id}.tmp")
        var rendered: Bitmap? = null
        try {
            val outputBitmap = renderer.render(
                source = source,
                processedWidth = processedWidth,
                processedHeight = processedHeight,
                segments = segments,
                settings = settings,
            )
            rendered = outputBitmap
            FileOutputStream(temporary).use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Unable to encode translated preview."
                }
            }
            if (target.exists()) check(target.delete())
            check(temporary.renameTo(target)) {
                "Unable to publish translated preview."
            }
            return target
        } finally {
            temporary.delete()
            rendered
                ?.takeIf { it !== source && !it.isRecycled }
                ?.recycle()
        }
    }

    private fun previewFile(item: GalleryTranslationItemEntity): File =
        File(File(root, item.taskId), "${item.id}.jpg")

    private suspend fun <T> safely(
        item: GalleryTranslationItemEntity,
        operation: String,
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Timber.w(
            error,
            "Gallery translated preview %s failed task=%s item=%s",
            operation,
            item.taskId,
            item.id,
        )
        null
    }

    private companion object {
        const val CACHE_ROOT = "gallery-translated-previews-v1"
        const val JPEG_QUALITY = 90
    }
}

internal fun galleryResultUsesTranslatedPreview(
    status: GalleryItemStatus,
    renderMode: GalleryExportRenderMode,
): Boolean = status == GalleryItemStatus.SUCCEEDED &&
    renderMode != GalleryExportRenderMode.UNSUPPORTED_FLOATING
