package com.gameocr.app.gallery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryTranslationManager @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: GalleryTranslationRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(task: GalleryPreparedTask) {
        val constraints = Constraints.Builder().apply {
            if (
                GalleryTranslationWorkPolicy.requiresNetwork(
                    task.ocrEngine,
                    task.translatorEngine,
                )
            ) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val request = OneTimeWorkRequestBuilder<GalleryTranslationWorker>()
            .setInputData(workDataOf(GalleryTranslationWorker.KEY_TASK_ID to task.id))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .addTag(GalleryTranslationWorkPolicy.WORK_TAG)
            .addTag(task.id)
            .build()
        workManager.enqueueUniqueWork(
            GalleryTranslationWorkPolicy.uniqueWorkName(task.id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun cancel(taskId: String) {
        repository.cancelTask(taskId)
        workManager.cancelUniqueWork(GalleryTranslationWorkPolicy.uniqueWorkName(taskId))
    }

    suspend fun retryFailed(taskId: String): Boolean {
        val reset = repository.resetFailedTask(taskId)
        if (reset <= 0) return false
        val task = repository.getTask(taskId) ?: return false
        enqueue(
            GalleryPreparedTask(
                id = task.id,
                sourceLang = task.sourceLang,
                targetLang = task.targetLang,
                ocrEngine = enumValueOrDefault(task.ocrEngine),
                translatorEngine = enumValueOrDefault(task.translatorEngine),
                imageCount = task.totalCount,
            )
        )
        return true
    }

    suspend fun delete(taskId: String) {
        workManager.cancelUniqueWork(GalleryTranslationWorkPolicy.uniqueWorkName(taskId))
        repository.deleteTask(taskId)
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: enumValues<T>().first()
