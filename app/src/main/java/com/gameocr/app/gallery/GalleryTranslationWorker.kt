package com.gameocr.app.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gameocr.app.R
import com.gameocr.app.data.Settings
import com.gameocr.app.data.needsRawBitmap
import com.gameocr.app.ocr.BitmapPreprocessor
import com.gameocr.app.ocr.RoutingOcrEngine
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.translate.RoutingTranslator
import com.gameocr.app.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class GalleryTranslationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: GalleryTranslationRepository,
    private val imageDecoder: GalleryImageDecoder,
    private val ocrEngine: RoutingOcrEngine,
    private val translator: RoutingTranslator,
    private val translatedPreviewStore: GalleryTranslatedPreviewStore,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty()
        if (taskId.isBlank()) return Result.failure()
        val task = repository.getTask(taskId) ?: return Result.failure()
        if (task.status == GalleryTaskStatus.CANCELED) return Result.success()

        val settings = repository.settingsForTask(task)
        val useForeground = GalleryTranslationWorkPolicy.shouldUseForeground(
            imageCount = task.totalCount,
            translatorEngine = settings.translatorEngine,
        )
        if (useForeground) {
            setForeground(createForegroundInfo(task, task.completedCount))
        }

        repository.resetInterruptedItems(taskId)
        repository.markTaskRunning(taskId)
        Timber.i("Gallery translation started task=%s images=%d", taskId, task.totalCount)

        while (true) {
            val latestTask = repository.getTask(taskId) ?: return Result.failure()
            if (latestTask.status == GalleryTaskStatus.CANCELED) return Result.success()
            val item = repository.nextPendingItem(taskId) ?: break
            repository.markItemRunning(taskId, item)

            try {
                processItem(item, settings)
                val progress = repository.refreshProgress(taskId)
                if (useForeground) {
                    setForeground(createForegroundInfo(latestTask, progress.completed))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val message = error.message?.take(500) ?: error.javaClass.simpleName
                val nextAttemptCount = item.attemptCount + 1
                val retry = if (error is EmptyTranslationException) {
                    GalleryTranslationWorkPolicy.shouldRetryEmptyTranslation(
                        enabled = settings.retryEmptyTranslation,
                        attemptCount = nextAttemptCount,
                    )
                } else {
                    GalleryTranslationWorkPolicy.shouldRetry(error, nextAttemptCount)
                }
                Timber.w(
                    error,
                    "Gallery translation item failed task=%s item=%s retry=%s attempt=%d",
                    taskId,
                    item.id,
                    retry,
                    nextAttemptCount,
                )
                repository.markItemFailure(item.id, retry, message)
                repository.refreshProgress(taskId, message)
                if (retry) {
                    repository.markTaskWaitingRetry(taskId, message)
                    return Result.retry()
                }
            }
        }

        val status = repository.finishTask(taskId)
        Timber.i("Gallery translation finished task=%s status=%s", taskId, status)
        return Result.success()
    }

    private suspend fun processItem(
        item: GalleryTranslationItemEntity,
        settings: Settings,
    ) {
        var decoded: GalleryDecodedImage? = null
        var preprocessed: Bitmap? = null
        try {
            decoded = imageDecoder.decode(item)
            val segments = if (translator.isEndToEndFor(settings)) {
                translator.ocrAndTranslate(decoded.bitmap, settings).map { (block, text) ->
                    GalleryTranslationSegment.from(block, text)
                }
            } else {
                val preprocessOptions = if (settings.ocrEngine.needsRawBitmap) {
                    settings.preprocess.copy(invert = false, binarize = false)
                } else {
                    settings.preprocess
                }
                preprocessed = BitmapPreprocessor.apply(decoded.bitmap, preprocessOptions)
                val rawBlocks = ocrEngine.recognizeWithSettings(
                    bitmap = preprocessed,
                    kind = settings.ocrEngine,
                    settings = settings,
                )
                val blocks = if (preprocessOptions.upscale2x) {
                    rawBlocks.map(::downscaleTwo)
                } else {
                    rawBlocks
                }
                if (blocks.isEmpty()) throw NoTextDetectedException()
                val translated = translator.translateBatch(blocks.map(TextBlock::text), settings)
                blocks.mapIndexed { index, block ->
                    GalleryTranslationSegment.from(block, translated.getOrNull(index))
                }
            }
            if (segments.isEmpty()) throw NoTextDetectedException()
            if (segments.all { it.translatedText.isBlank() }) {
                throw EmptyTranslationException()
            }
            translatedPreviewStore.storeTranslatedPreview(
                item = item,
                source = decoded.bitmap,
                processedWidth = decoded.bitmap.width,
                processedHeight = decoded.bitmap.height,
                segments = segments,
                settings = settings,
            )
            repository.completeItem(
                itemId = item.id,
                decoded = decoded,
                processedWidth = decoded.bitmap.width,
                processedHeight = decoded.bitmap.height,
                segments = segments,
            )
        } finally {
            preprocessed?.takeIf { it !== decoded?.bitmap && !it.isRecycled }?.recycle()
            decoded?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun createForegroundInfo(
        task: GalleryTranslationTaskEntity,
        completed: Int,
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    applicationContext.getString(R.string.gallery_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val openTasks = PendingIntent.getActivity(
            applicationContext,
            task.id.hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_ROUTE, ROUTE_GALLERY_TASKS)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val progressText = applicationContext.getString(
            R.string.gallery_notification_progress,
            completed,
            task.totalCount,
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(applicationContext.getString(R.string.gallery_notification_title))
            .setContentText(progressText)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openTasks)
            .setProgress(task.totalCount, completed, false)
            .build()
        val notificationId = NOTIFICATION_ID_BASE + (task.id.hashCode() and 0x0FFF)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private class NoTextDetectedException : IllegalStateException("No text detected.")
    private class EmptyTranslationException : IllegalStateException("No translation returned.")

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val ROUTE_GALLERY_TASKS = "GalleryTasks"
        private const val NOTIFICATION_CHANNEL_ID = "gallery_translation"
        private const val NOTIFICATION_ID_BASE = 6100
    }
}

private fun downscaleTwo(block: TextBlock): TextBlock {
    fun Rect.half(): Rect = Rect(left / 2, top / 2, right / 2, bottom / 2)
    return block.copy(
        boundingBox = block.boundingBox.half(),
        sourceBoxes = block.sourceBoxes.map { it.half() },
    )
}
