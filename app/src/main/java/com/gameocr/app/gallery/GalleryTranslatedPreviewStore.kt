package com.gameocr.app.gallery

import android.content.Context
import android.graphics.Bitmap
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.PageOcrFrame
import com.gameocr.app.translate.PreparedPageTranslation
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
    private val root = File(context.filesDir, "gallery_translation")
    private val legacyRoot = File(context.cacheDir, "gallery-translated-previews-v1")
    private val generationMutex = Mutex()

    internal suspend fun storeTranslatedPreview(
        item: GalleryTranslationItemEntity,
        source: Bitmap,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
        settings: Settings,
        frame: PageOcrFrame? = null,
        preparedPage: PreparedPageTranslation? = null,
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
                    frame = frame,
                    preparedPage = preparedPage,
                    rebuildVisualContext = false,
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
        File(File(root, taskId), "rendered").deleteRecursively()
        File(legacyRoot, taskId).deleteRecursively()
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // A historical task may have lost its original but still have a readable result.
            // Prefer rebuilding through the shared pipeline; retain that last copy on failure.
            val legacy = File(File(legacyRoot, item.taskId), "${item.id}.jpg")
            if (!legacy.isFile || legacy.length() == 0L) throw error
            Timber.w(error, "Gallery source unavailable; retaining legacy result item=%s", item.id)
            legacy
        } finally {
            decoded?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private suspend fun writePreview(
        item: GalleryTranslationItemEntity,
        source: Bitmap,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
        settings: Settings,
        frame: PageOcrFrame? = null,
        preparedPage: PreparedPageTranslation? = null,
        rebuildVisualContext: Boolean = true,
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
                frame = frame,
                preparedPage = preparedPage,
                rebuildVisualContext = rebuildVisualContext,
            )
            rendered = outputBitmap
            FileOutputStream(temporary).use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
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
        File(File(File(root, item.taskId), "rendered"), "${item.id}.png")

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

}

internal fun galleryResultUsesTranslatedPreview(
    status: GalleryItemStatus,
    renderMode: GalleryExportRenderMode,
): Boolean = status == GalleryItemStatus.SUCCEEDED &&
    renderMode != GalleryExportRenderMode.UNSUPPORTED_FLOATING
