package com.gameocr.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gameocr.app.R
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.llm.LlmModelInstaller
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.OrientationModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import timber.log.Timber

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val llmInstaller: LlmModelInstaller,
    private val paddleInstaller: PaddleModelInstaller,
    private val mangaOcrInstaller: MangaOcrModelInstaller,
    private val orientationModelInstaller: OrientationModelInstaller,
) : CoroutineWorker(appContext, workerParams) {

    private var lastProgressUpdateAt = 0L
    private var lastSpec: ModelDownloadSpec? = null
    private var lastFile = ""
    private var lastDownloaded = 0L
    private var lastTotal = -1L
    private var lastBatchIndex = 0
    private var lastBatchCount = 0

    override suspend fun doWork(): Result {
        val specs = ModelDownloadSpec.decodeAll(inputData.getStringArray(KEY_SPECS).orEmpty())
            ?: return Result.failure(
                terminalData(
                    specs = emptyList(),
                    status = "Invalid model download request",
                    error = "Invalid model download request",
                )
            )
        val ownerPresetId = inputData.getString(KEY_OWNER_PRESET_ID).orEmpty()
        Timber.i(
            "Background model download started id=%s ownerPresetId=%s attempt=%d specs=%s",
            id,
            ownerPresetId.ifBlank { "none" },
            runAttemptCount,
            specs.joinToString { it.encode() },
        )

        return try {
            specs.forEachIndexed { index, spec ->
                download(spec, index, specs.size)
            }
            val status = applicationContext.getString(R.string.model_download_complete)
            publish(status, "", 1, 1, force = true, batchIndex = specs.size, batchCount = specs.size)
            Timber.i("Background model download completed id=%s specs=%d", id, specs.size)
            Result.success(terminalData(specs, status))
        } catch (t: CancellationException) {
            Timber.i("Background model download cancelled id=%s", id)
            throw t
        } catch (t: Throwable) {
            Timber.w(t, "Background model download failed attempt=$runAttemptCount")
            val detail = t.message ?: t.javaClass.simpleName
            val retry = ModelDownloadWorkPolicy.shouldRetry(runAttemptCount)
            val status = applicationContext.getString(
                if (retry) R.string.model_download_retrying_format else R.string.model_download_failed_format,
                detail,
            )
            publish(
                status = status,
                file = lastFile,
                downloaded = lastDownloaded,
                total = lastTotal,
                force = true,
                spec = lastSpec,
                batchIndex = lastBatchIndex,
                batchCount = lastBatchCount.takeIf { it > 0 } ?: specs.size,
            )
            if (retry) {
                Result.retry()
            } else {
                Result.failure(terminalData(specs, status, detail))
            }
        }
    }

    private suspend fun download(spec: ModelDownloadSpec, index: Int, count: Int) {
        val label = displayName(spec)
        publish(
            applicationContext.getString(R.string.model_download_starting_format, label, index + 1, count),
            label,
            0,
            -1,
            force = true,
            spec = spec,
            batchIndex = index + 1,
            batchCount = count,
        )

        when (spec.type) {
            ModelDownloadType.LLM -> {
                val kind = LlmModelKind.valueOf(spec.variant)
                llmInstaller.download(kind).collect { progress ->
                    publishProgress(spec, label, index, count, progress.fileName(), progress.downloaded, progress.total, progress.done, progress.error)
                }
            }
            ModelDownloadType.PADDLE -> {
                val version = PaddleModelVersion.valueOf(spec.variant)
                paddleInstaller.downloadAll(version).collect { progress ->
                    publishProgress(spec, label, index, count, progress.file, progress.downloaded, progress.total, progress.done, progress.error)
                }
            }
            ModelDownloadType.MANGA_OCR -> {
                mangaOcrInstaller.downloadAll().collect { progress ->
                    publishProgress(spec, label, index, count, progress.file, progress.downloaded, progress.total, progress.done, progress.error)
                }
            }
            ModelDownloadType.ORIENTATION -> {
                orientationModelInstaller.downloadAll().collect { progress ->
                    publishProgress(spec, label, index, count, progress.file, progress.downloaded, progress.total, progress.done, progress.error)
                }
            }
        }
    }

    private fun LlmModelInstaller.Progress.fileName(): String = kind.fileName

    private suspend fun publishProgress(
        spec: ModelDownloadSpec,
        label: String,
        index: Int,
        count: Int,
        file: String,
        downloaded: Long,
        total: Long,
        done: Boolean,
        error: String?,
    ) {
        val status = when {
            error != null -> applicationContext.getString(R.string.model_download_failed_format, error)
            total > 0 -> applicationContext.getString(
                R.string.model_download_progress_batch_format,
                label,
                (downloaded * 100 / total).coerceIn(0, 100),
                file,
                index + 1,
                count,
            )
            else -> applicationContext.getString(
                R.string.model_download_progress_bytes_batch_format,
                label,
                downloaded / 1024 / 1024,
                file,
                index + 1,
                count,
            )
        }
        publish(
            status,
            file,
            downloaded,
            total,
            force = done || error != null,
            spec = spec,
            batchIndex = index + 1,
            batchCount = count,
        )
    }

    private suspend fun publish(
        status: String,
        file: String,
        downloaded: Long,
        total: Long,
        force: Boolean,
        spec: ModelDownloadSpec? = null,
        batchIndex: Int,
        batchCount: Int,
    ) {
        if (spec != null) lastSpec = spec
        if (file.isNotBlank()) lastFile = file
        lastDownloaded = downloaded
        lastTotal = total
        lastBatchIndex = batchIndex
        lastBatchCount = batchCount

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProgressUpdateAt < PROGRESS_UPDATE_INTERVAL_MS) return
        lastProgressUpdateAt = now

        setProgress(
            workDataOf(
                KEY_STATUS to status,
                KEY_FILE to file,
                KEY_DOWNLOADED to downloaded,
                KEY_TOTAL to total,
                KEY_CURRENT_SPEC to spec?.encode().orEmpty(),
                KEY_BATCH_INDEX to batchIndex,
                KEY_BATCH_COUNT to batchCount,
            )
        )
        setForeground(createForegroundInfo(status, downloaded, total))
    }

    private fun createForegroundInfo(status: String, downloaded: Long, total: Long): ForegroundInfo {
        createNotificationChannel()
        val cancelIntent = androidx.work.WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.model_download_notification_title))
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                if (total > 0) 100 else 0,
                if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0,
                total <= 0,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.model_download_cancel),
                cancelIntent,
            )
            .build()
        return ForegroundInfo(
            notificationId(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun terminalData(
        specs: List<ModelDownloadSpec>,
        status: String,
        error: String = "",
    ): androidx.work.Data =
        androidx.work.Data.Builder()
            .putStringArray(KEY_SPECS, specs.map { it.encode() }.toTypedArray())
            .putString(KEY_STATUS, status)
            .putString(KEY_ERROR, error)
            .putString(KEY_FILE, lastFile)
            .putLong(KEY_DOWNLOADED, lastDownloaded)
            .putLong(KEY_TOTAL, lastTotal)
            .putString(KEY_CURRENT_SPEC, lastSpec?.encode().orEmpty())
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .build()

    private fun notificationId(): Int =
        NOTIFICATION_ID_BASE + ((id.hashCode() and Int.MAX_VALUE) % NOTIFICATION_ID_RANGE)

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.model_download_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun displayName(spec: ModelDownloadSpec): String = when (spec.type) {
        ModelDownloadType.LLM -> LlmModelKind.valueOf(spec.variant).displayName
        ModelDownloadType.PADDLE -> applicationContext.getString(PaddleModelVersion.valueOf(spec.variant).displayNameRes)
        ModelDownloadType.MANGA_OCR -> applicationContext.getString(R.string.settings_manga_ocr_model_name)
        ModelDownloadType.ORIENTATION -> applicationContext.getString(R.string.settings_orientation_model_name)
    }

    companion object {
        const val KEY_SPECS = "model_download_specs"
        const val KEY_STATUS = "model_download_status"
        const val KEY_ERROR = "model_download_error"
        const val KEY_FILE = "model_download_file"
        const val KEY_DOWNLOADED = "model_download_downloaded"
        const val KEY_TOTAL = "model_download_total"
        const val KEY_CURRENT_SPEC = "model_download_current_spec"
        const val KEY_BATCH_INDEX = "model_download_batch_index"
        const val KEY_BATCH_COUNT = "model_download_batch_count"
        const val KEY_OWNER_PRESET_ID = "model_download_owner_preset_id"
        const val KEY_FINISHED_AT = "model_download_finished_at"

        private const val NOTIFICATION_CHANNEL_ID = "model_downloads_visible_v2"
        private const val NOTIFICATION_ID_BASE = 2002
        private const val NOTIFICATION_ID_RANGE = 100_000
        private const val PROGRESS_UPDATE_INTERVAL_MS = 750L
    }
}
