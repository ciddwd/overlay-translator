package com.gameocr.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val enqueueMutex = Mutex()

    val workInfos: Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagFlow(ModelDownloadWorkPolicy.WORK_TAG)

    suspend fun enqueueAndAwait(
        specs: List<ModelDownloadSpec>,
        onProgress: (String) -> Unit,
        ownerPresetId: String? = null,
    ) {
        val workId = enqueue(specs, ownerPresetId)
        val terminal = workManager.getWorkInfoByIdFlow(workId)
            .filterNotNull()
            .onEach { info ->
                val status = info.progress.getString(ModelDownloadWorker.KEY_STATUS)
                    ?: info.outputData.getString(ModelDownloadWorker.KEY_STATUS)
                if (!status.isNullOrBlank()) onProgress(status)
            }
            .first { it.state.isFinished }

        if (terminal.state != WorkInfo.State.SUCCEEDED) {
            val detail = terminal.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                ?: terminal.outputData.getString(ModelDownloadWorker.KEY_STATUS)
                ?: "Model download ${terminal.state.name.lowercase()}"
            throw RuntimeException(detail)
        }
    }

    fun cancel(workId: UUID) {
        workManager.cancelWorkById(workId)
    }

    private suspend fun enqueue(specs: List<ModelDownloadSpec>, ownerPresetId: String?): UUID {
        require(specs.isNotEmpty()) { "At least one model download is required" }
        val uniqueName = ModelDownloadWorkPolicy.uniqueWorkName(specs)
        return enqueueMutex.withLock {
            val existing = workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
                .first()
                .lastOrNull { !it.state.isFinished }
            if (existing != null) return@withLock existing.id

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    androidx.work.Data.Builder()
                        .putStringArray(ModelDownloadWorker.KEY_SPECS, specs.map { it.encode() }.toTypedArray())
                        .putString(ModelDownloadWorker.KEY_OWNER_PRESET_ID, ownerPresetId.orEmpty())
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ModelDownloadWorkPolicy.WORK_TAG)
                .apply {
                    ownerPresetId?.takeIf { it.isNotBlank() }?.let {
                        addTag(ModelDownloadWorkPolicy.ownerTag(it))
                    }
                }
                .build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
            request.id
        }
    }
}
