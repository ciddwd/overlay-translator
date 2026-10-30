package com.gameocr.app.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gameocr.app.R
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.PageOcrFrame
import com.gameocr.app.ocr.PageOcrPipeline
import com.gameocr.app.translate.RoutingTranslator
import com.gameocr.app.translate.DialogueHistorySession
import com.gameocr.app.translate.DialogueTranslationContextPolicy
import com.gameocr.app.translate.PageTranslationPlan
import com.gameocr.app.translate.PageTranslationPreparer
import com.gameocr.app.translate.PreparedPageTranslation
import com.gameocr.app.translate.TranslationVisualDebugStore
import com.gameocr.app.translate.pageTranslationRowUpdates
import com.gameocr.app.translate.planPageTranslation
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
    private val pageOcrPipeline: PageOcrPipeline,
    private val pageTranslationPreparer: PageTranslationPreparer,
    private val translator: RoutingTranslator,
    private val translatedPreviewStore: GalleryTranslatedPreviewStore,
) : CoroutineWorker(appContext, workerParams) {

    private val translationVisualDebugStore by lazy {
        TranslationVisualDebugStore.forContext(applicationContext)
    }

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
        val historySession = DialogueHistorySession()

        while (true) {
            val latestTask = repository.getTask(taskId) ?: return Result.failure()
            if (latestTask.status == GalleryTaskStatus.CANCELED) return Result.success()
            val item = repository.nextPendingItem(taskId) ?: break
            repository.markItemRunning(taskId, item)

            try {
                processItem(item, settings, historySession)
                val progress = repository.refreshProgress(taskId)
                if (useForeground) {
                    setForeground(createForegroundInfo(latestTask, progress.completed))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                val message = error.message?.take(500) ?: error.javaClass.simpleName
                val nextAttemptCount = item.attemptCount + 1
                val retry = if (
                    error is EmptyTranslationException ||
                    error is TranslationRequestException
                ) {
                    GalleryTranslationWorkPolicy.shouldRetryFailedTranslation(
                        enabled = settings.retryFailedTranslation &&
                            !translator.handlesTranslationFailureRetry(settings),
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
        historySession: DialogueHistorySession,
    ) {
        var decoded: GalleryDecodedImage? = null
        var visualFrame: PageOcrFrame? = null
        var completedPagePlan: PageTranslationPlan? = null
        var completedTranslations = emptyMap<Int, String>()
        var preparedPage: PreparedPageTranslation? = null
        try {
            decoded = imageDecoder.decode(item)
            val segments = if (translator.isEndToEndFor(settings)) {
                translateOrThrow {
                    translator.ocrAndTranslate(decoded.bitmap, settings)
                }.map { (block, text) ->
                    GalleryTranslationSegment.from(block, text)
                }
            } else {
                val page = pageOcrPipeline.recognize(decoded.bitmap, settings)
                visualFrame = page.frame
                if (page.blocks.isEmpty()) throw NoTextDetectedException()
                val content = pageTranslationPreparer.prepare(
                    bitmap = decoded.bitmap,
                    blocks = page.blocks,
                    settings = settings,
                    readingOrientation = page.frame.readingOrientation,
                    presentation = RenderMode.BLOCKS,
                    origin = "gallery-${item.id}",
                    debugStore = translationVisualDebugStore,
                )
                preparedPage = content
                val blocks = content.blocks
                val visualPreparation = content.visual
                visualPreparation.context?.let { visual ->
                    Timber.i(
                        "Gallery visual context item=%s image=%dx%d bytes=%d sha256=%s debugPath=%s",
                        item.id,
                        visual.width,
                        visual.height,
                        visual.byteCount,
                        visual.sha256,
                        visualPreparation.debugArtifact?.absolutePath ?: "none",
                    )
                }
                val pagePlan = planPageTranslation(
                    blocks = blocks,
                    settings = visualPreparation.settings,
                    presentation = RenderMode.BLOCKS,
                    translator = translator,
                    historySession = historySession,
                )
                val pendingIndexes = pagePlan.units.indices
                    .filterNot(pagePlan.reusedTranslationsByIndex::containsKey)
                val translated = translateOrThrow {
                    translator.translateBatch(
                        pendingIndexes.map { pagePlan.units[it].sourceText },
                        pagePlan.contextualSettings,
                    )
                }
                val translationsByIndex = pagePlan.reusedTranslationsByIndex.toMutableMap()
                pendingIndexes.forEachIndexed { pendingIndex, index ->
                    translated.getOrNull(pendingIndex)?.takeIf(String::isNotBlank)?.let {
                        translationsByIndex[index] = it
                    }
                }
                val translationsByBlock = translationsByIndex.flatMap { (index, text) ->
                    pageTranslationRowUpdates(text, pagePlan.units[index])
                }.associate { it.blockIndex to it.text }
                completedPagePlan = pagePlan
                completedTranslations = translationsByIndex
                blocks.mapIndexed { index, block ->
                    GalleryTranslationSegment.from(block, translationsByBlock[index], page.frame.readingOrientation)
                }
            }
            val allSourcePreserved = preparedPage?.let {
                it.blocks.isEmpty() && it.preservation.preservedIndexes.isNotEmpty()
            } == true
            if (segments.isEmpty() && !allSourcePreserved) throw NoTextDetectedException()
            if (!allSourcePreserved && segments.all { it.translatedText.isBlank() }) {
                throw EmptyTranslationException()
            }
            translatedPreviewStore.storeTranslatedPreview(
                item = item,
                source = decoded.bitmap,
                processedWidth = decoded.bitmap.width,
                processedHeight = decoded.bitmap.height,
                segments = segments,
                settings = settings,
                frame = visualFrame,
                preparedPage = preparedPage,
            )
            repository.completeItem(
                itemId = item.id,
                decoded = decoded,
                processedWidth = decoded.bitmap.width,
                processedHeight = decoded.bitmap.height,
                segments = segments,
            )
            completedPagePlan?.let { plan ->
                if (DialogueTranslationContextPolicy.shouldCommitHistory(
                        plan.contextualSettings, plan.units.size, completedTranslations)) {
                    historySession.commitUnits(
                        DialogueTranslationContextPolicy.contextKey(plan.contextualSettings),
                        plan.units,
                        completedTranslations,
                    )
                }
            }
        } finally {
            decoded?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private suspend fun <T> translateOrThrow(block: suspend () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        throw TranslationRequestException(error)
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
    private class TranslationRequestException(cause: Throwable) :
        IllegalStateException(cause.message ?: cause.javaClass.simpleName, cause)

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val ROUTE_GALLERY_TASKS = "GalleryTasks"
        private const val NOTIFICATION_CHANNEL_ID = "gallery_translation"
        private const val NOTIFICATION_ID_BASE = 6100
    }
}
