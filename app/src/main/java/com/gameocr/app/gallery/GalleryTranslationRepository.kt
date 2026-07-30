package com.gameocr.app.gallery

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

internal fun galleryTaskMatchingPreset(settings: Settings): TranslationPreset? {
    val savedPresets = TranslationPresetCatalog.all(settings.translationPresets)
        .filterNot { it.id == TranslationPresetCatalog.UNSAVED_DRAFT_ID }
    val settingsHash = TranslationPresetCatalog.hashForSettings(settings)
    return savedPresets.firstOrNull {
        it.id == settings.activeTranslationPresetId &&
            TranslationPresetCatalog.matchesHash(it, settingsHash)
    } ?: savedPresets.firstOrNull {
        TranslationPresetCatalog.matchesHash(it, settingsHash)
    }
}

@Singleton
class GalleryTranslationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: GalleryTranslationDao,
    private val settingsRepository: SettingsRepository,
    private val translatedPreviewStore: GalleryTranslatedPreviewStore,
    private val json: Json,
) {
    fun observeTasks(): Flow<List<GalleryTranslationTaskEntity>> = dao.observeTasks()

    fun observeLatestTask(): Flow<GalleryTranslationTaskEntity?> = dao.observeLatestTask()

    fun observeFeaturedTask(): Flow<GalleryTranslationTaskEntity?> = dao.observeFeaturedTask()

    fun observeTask(taskId: String): Flow<GalleryTranslationTaskEntity?> = dao.observeTask(taskId)

    fun observeItems(taskId: String): Flow<List<GalleryTranslationItemEntity>> =
        dao.observeItems(taskId)

    fun observeItem(itemId: String): Flow<GalleryTranslationItemEntity?> = dao.observeItem(itemId)

    suspend fun getTask(taskId: String): GalleryTranslationTaskEntity? = dao.getTask(taskId)

    suspend fun getItems(taskId: String): List<GalleryTranslationItemEntity> = dao.getItems(taskId)

    suspend fun createTask(uriStrings: List<String>): GalleryPreparedTask = withContext(Dispatchers.IO) {
        val selected = GalleryTranslationWorkPolicy.normalizeSelection(uriStrings)
        require(selected.isNotEmpty()) { "No images selected." }

        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val settings = settingsRepository.get()
        val activePreset = galleryTaskMatchingPreset(settings)
        val snapshot = TranslationPresetCatalog.fromSettings(
            id = "gallery_$taskId",
            name = activePreset?.name.orEmpty(),
            shortName = activePreset?.shortName.orEmpty(),
            settings = settings,
        )
        val items = selected.mapIndexed { index, uriString ->
            prepareItem(
                taskId = taskId,
                position = index,
                uriString = uriString,
                now = now,
            )
        }
        val failedCount = items.count { it.status == GalleryItemStatus.FAILED }
        val task = GalleryTranslationTaskEntity(
            id = taskId,
            createdAtMs = now,
            sourceLang = settings.sourceLang,
            targetLang = settings.targetLang,
            ocrEngine = settings.ocrEngine.name,
            translatorEngine = settings.translatorEngine.name,
            presetName = activePreset?.name.orEmpty(),
            settingsSnapshotJson = json.encodeToString(snapshot),
            totalCount = items.size,
            completedCount = failedCount,
            failedCount = failedCount,
            lastError = items.firstOrNull { it.status == GalleryItemStatus.FAILED }
                ?.errorMessage
                .orEmpty(),
        )
        dao.insertTaskWithItems(task, items)
        GalleryPreparedTask(
            id = taskId,
            sourceLang = settings.sourceLang,
            targetLang = settings.targetLang,
            ocrEngine = settings.ocrEngine,
            translatorEngine = settings.translatorEngine,
            imageCount = items.size,
        )
    }

    suspend fun settingsForTask(task: GalleryTranslationTaskEntity): Settings {
        val current = settingsRepository.get()
        val snapshot = runCatching {
            json.decodeFromString<TranslationPreset>(task.settingsSnapshotJson)
        }.getOrElse {
            Timber.w(it, "Gallery task %s has an invalid settings snapshot", task.id)
            return current.copy(
                sourceLang = task.sourceLang,
                targetLang = task.targetLang,
            )
        }
        return snapshot.applyTo(current).copy(
            // Gallery work has no active foreground game. Global glossary entries remain
            // available, while app-specific memory must not attach to whichever app happens
            // to be visible when WorkManager starts.
            sendAppNameToTranslator = false,
            runtimeTranslationScopePackage = task.scopePackage,
            runtimeTranslationScopeLabel = task.scopeLabel,
        )
    }

    internal fun exportRenderModeForTask(task: GalleryTranslationTaskEntity): GalleryExportRenderMode =
        runCatching {
            val snapshot = json.decodeFromString<TranslationPreset>(task.settingsSnapshotJson)
            galleryExportRenderMode(snapshot.renderMode, snapshot.overlayStyleMode)
        }.getOrElse {
            Timber.w(it, "Gallery task %s has an invalid export render snapshot", task.id)
            GalleryExportRenderMode.FIXED_BLOCKS
        }

    suspend fun resetInterruptedItems(taskId: String) {
        dao.resetInterruptedItems(taskId, System.currentTimeMillis())
    }

    suspend fun markTaskRunning(taskId: String) {
        dao.markTaskRunning(taskId, GalleryTaskStatus.RUNNING, System.currentTimeMillis())
    }

    suspend fun markTaskWaitingRetry(taskId: String, lastError: String) {
        dao.markTaskWaitingRetry(taskId, lastError)
    }

    suspend fun nextPendingItem(taskId: String): GalleryTranslationItemEntity? =
        dao.nextPendingItem(taskId)

    suspend fun markItemRunning(taskId: String, item: GalleryTranslationItemEntity) {
        val now = System.currentTimeMillis()
        dao.markItemRunning(item.id, now)
        dao.updateCurrentItem(taskId, item.displayName)
    }

    suspend fun completeItem(
        itemId: String,
        decoded: GalleryDecodedImage,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
    ) {
        dao.completeItem(
            itemId = itemId,
            originalWidth = decoded.originalWidth,
            originalHeight = decoded.originalHeight,
            processedWidth = processedWidth,
            processedHeight = processedHeight,
            sourceText = segments.joinToString("\n") { it.sourceText },
            translatedText = segments.joinToString("\n") { it.translatedText },
            segmentsJson = json.encodeToString(segments),
            now = System.currentTimeMillis(),
        )
    }

    suspend fun markItemFailure(
        itemId: String,
        retry: Boolean,
        message: String,
    ) {
        dao.markItemFailed(
            itemId = itemId,
            status = if (retry) GalleryItemStatus.QUEUED else GalleryItemStatus.FAILED,
            message = message,
            now = System.currentTimeMillis(),
        )
    }

    suspend fun refreshProgress(taskId: String, lastError: String = ""): GalleryTaskProgress {
        val row = dao.progress(taskId)
        val progress = GalleryTaskProgress(
            total = row.total,
            succeeded = row.succeeded,
            failed = row.failed,
        )
        dao.updateProgress(
            taskId = taskId,
            total = progress.total,
            completed = progress.completed,
            succeeded = progress.succeeded,
            failed = progress.failed,
            lastError = lastError,
        )
        return progress
    }

    suspend fun finishTask(taskId: String, lastError: String = ""): GalleryTaskStatus {
        val task = dao.getTask(taskId)
        val progress = refreshProgress(taskId, lastError)
        val status = GalleryTranslationWorkPolicy.terminalStatus(
            progress = progress,
            canceled = task?.status == GalleryTaskStatus.CANCELED,
        )
        dao.finishTask(
            taskId = taskId,
            status = status,
            finishedAtMs = System.currentTimeMillis(),
            lastError = lastError,
        )
        return status
    }

    suspend fun cancelTask(taskId: String) {
        dao.cancelTaskAndItems(taskId, System.currentTimeMillis())
        refreshProgress(taskId)
    }

    suspend fun resetFailedTask(taskId: String): Int =
        dao.resetFailedTask(taskId, System.currentTimeMillis())

    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        val items = dao.getItems(taskId)
        dao.deleteTask(taskId)
        items.map(GalleryTranslationItemEntity::sourceUri)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { sourceUri ->
                if (dao.countSourceUriReferences(sourceUri) == 0) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(sourceUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
        }
        File(context.filesDir, "$TASK_FILES_DIR/$taskId").deleteRecursively()
        translatedPreviewStore.deleteTask(taskId)
    }

    private fun prepareItem(
        taskId: String,
        position: Int,
        uriString: String,
        now: Long,
    ): GalleryTranslationItemEntity {
        val uri = Uri.parse(uriString)
        val itemId = UUID.randomUUID().toString()
        val displayName = displayName(uri).ifBlank { "image_${position + 1}" }
        val persisted = persistReadPermission(uri)
        if (persisted) {
            return GalleryTranslationItemEntity(
                id = itemId,
                taskId = taskId,
                position = position,
                sourceUri = uriString,
                displayName = displayName,
                updatedAtMs = now,
            )
        }

        return runCatching {
            val targetDir = File(context.filesDir, "$TASK_FILES_DIR/$taskId/source").apply {
                check(mkdirs() || isDirectory)
            }
            val extension = displayName.substringAfterLast('.', "")
                .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                ?.let { ".$it" }
                .orEmpty()
            val target = File(targetDir, "$itemId$extension")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected image." }
                FileOutputStream(target).use(input::copyTo)
            }
            GalleryTranslationItemEntity(
                id = itemId,
                taskId = taskId,
                position = position,
                sourceUri = uriString,
                localPath = target.absolutePath,
                displayName = displayName,
                updatedAtMs = now,
            )
        }.getOrElse { error ->
            GalleryTranslationItemEntity(
                id = itemId,
                taskId = taskId,
                position = position,
                sourceUri = uriString,
                displayName = displayName,
                status = GalleryItemStatus.FAILED,
                errorMessage = error.message ?: error.javaClass.simpleName,
                updatedAtMs = now,
            )
        }
    }

    private fun persistReadPermission(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return false
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected image." }
                input.read()
            }
            true
        }.getOrElse {
            Timber.i(it, "URI permission could not be persisted; copying selected image")
            false
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.lastPathSegment.orEmpty()
        }
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use ""
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    private companion object {
        const val TASK_FILES_DIR = "gallery_translation"
    }
}
