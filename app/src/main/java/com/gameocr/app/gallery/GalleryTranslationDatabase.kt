package com.gameocr.app.gallery

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "gallery_translation_tasks")
data class GalleryTranslationTaskEntity(
    @PrimaryKey val id: String,
    val createdAtMs: Long,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val status: GalleryTaskStatus = GalleryTaskStatus.QUEUED,
    val sourceLang: String,
    val targetLang: String,
    val ocrEngine: String,
    val translatorEngine: String,
    val presetName: String,
    val settingsSnapshotJson: String,
    val scopePackage: String = "",
    val scopeLabel: String = "",
    val totalCount: Int,
    val completedCount: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val currentItemName: String = "",
    val lastError: String = "",
)

@Entity(
    tableName = "gallery_translation_items",
    foreignKeys = [
        ForeignKey(
            entity = GalleryTranslationTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "position"], unique = true),
        Index(value = ["taskId", "status"]),
        Index(value = ["sourceUri"]),
    ],
)
data class GalleryTranslationItemEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val position: Int,
    val sourceUri: String,
    val localPath: String = "",
    val displayName: String,
    val status: GalleryItemStatus = GalleryItemStatus.QUEUED,
    val attemptCount: Int = 0,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val processedWidth: Int = 0,
    val processedHeight: Int = 0,
    val sourceText: String = "",
    val translatedText: String = "",
    val segmentsJson: String = "[]",
    val errorMessage: String = "",
    val updatedAtMs: Long,
)

data class GalleryProgressRow(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
)

@Dao
interface GalleryTranslationDao {
    @Query("SELECT * FROM gallery_translation_tasks ORDER BY createdAtMs DESC")
    fun observeTasks(): Flow<List<GalleryTranslationTaskEntity>>

    @Query("SELECT * FROM gallery_translation_tasks ORDER BY createdAtMs DESC LIMIT 1")
    fun observeLatestTask(): Flow<GalleryTranslationTaskEntity?>

    @Query(
        "SELECT * FROM gallery_translation_tasks " +
            "ORDER BY CASE WHEN status IN ('QUEUED', 'RUNNING', 'WAITING_RETRY') " +
            "THEN 0 ELSE 1 END, createdAtMs DESC LIMIT 1"
    )
    fun observeFeaturedTask(): Flow<GalleryTranslationTaskEntity?>

    @Query("SELECT * FROM gallery_translation_tasks WHERE id = :taskId LIMIT 1")
    fun observeTask(taskId: String): Flow<GalleryTranslationTaskEntity?>

    @Query("SELECT * FROM gallery_translation_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTask(taskId: String): GalleryTranslationTaskEntity?

    @Query(
        "SELECT * FROM gallery_translation_items WHERE taskId = :taskId " +
            "ORDER BY position"
    )
    fun observeItems(taskId: String): Flow<List<GalleryTranslationItemEntity>>

    @Query(
        "SELECT * FROM gallery_translation_items WHERE taskId = :taskId " +
            "ORDER BY position"
    )
    suspend fun getItems(taskId: String): List<GalleryTranslationItemEntity>

    @Query("SELECT * FROM gallery_translation_items WHERE id = :itemId LIMIT 1")
    fun observeItem(itemId: String): Flow<GalleryTranslationItemEntity?>

    @Query(
        "SELECT * FROM gallery_translation_items WHERE taskId = :taskId " +
            "AND status IN ('QUEUED', 'RUNNING') ORDER BY position LIMIT 1"
    )
    suspend fun nextPendingItem(taskId: String): GalleryTranslationItemEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: GalleryTranslationTaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<GalleryTranslationItemEntity>)

    @Transaction
    suspend fun insertTaskWithItems(
        task: GalleryTranslationTaskEntity,
        items: List<GalleryTranslationItemEntity>,
    ) {
        insertTask(task)
        insertItems(items)
    }

    @Query(
        "UPDATE gallery_translation_tasks SET status = :status, " +
            "startedAtMs = COALESCE(startedAtMs, :now), finishedAtMs = NULL, lastError = '' " +
            "WHERE id = :taskId AND status != 'CANCELED'"
    )
    suspend fun markTaskRunning(taskId: String, status: GalleryTaskStatus, now: Long)

    @Query(
        "UPDATE gallery_translation_tasks SET status = 'WAITING_RETRY', " +
            "currentItemName = '', lastError = :lastError WHERE id = :taskId AND status != 'CANCELED'"
    )
    suspend fun markTaskWaitingRetry(taskId: String, lastError: String)

    @Query(
        "UPDATE gallery_translation_tasks SET status = :status, finishedAtMs = :finishedAtMs, " +
            "currentItemName = '', lastError = :lastError WHERE id = :taskId"
    )
    suspend fun finishTask(
        taskId: String,
        status: GalleryTaskStatus,
        finishedAtMs: Long,
        lastError: String,
    )

    @Query(
        "UPDATE gallery_translation_tasks SET status = 'CANCELED', finishedAtMs = :now, " +
            "currentItemName = '' WHERE id = :taskId"
    )
    suspend fun cancelTask(taskId: String, now: Long)

    @Query(
        "UPDATE gallery_translation_items SET status = 'CANCELED', updatedAtMs = :now " +
            "WHERE taskId = :taskId AND status IN ('QUEUED', 'RUNNING')"
    )
    suspend fun cancelPendingItems(taskId: String, now: Long)

    @Transaction
    suspend fun cancelTaskAndItems(taskId: String, now: Long) {
        cancelTask(taskId, now)
        cancelPendingItems(taskId, now)
    }

    @Query(
        "UPDATE gallery_translation_items SET status = 'QUEUED', updatedAtMs = :now " +
            "WHERE taskId = :taskId AND status = 'RUNNING'"
    )
    suspend fun resetInterruptedItems(taskId: String, now: Long)

    @Query(
        "UPDATE gallery_translation_items SET status = 'RUNNING', " +
            "attemptCount = attemptCount + 1, errorMessage = '', updatedAtMs = :now " +
            "WHERE id = :itemId"
    )
    suspend fun markItemRunning(itemId: String, now: Long)

    @Query(
        "UPDATE gallery_translation_tasks SET currentItemName = :displayName WHERE id = :taskId"
    )
    suspend fun updateCurrentItem(taskId: String, displayName: String)

    @Query(
        "UPDATE gallery_translation_items SET status = 'SUCCEEDED', " +
            "originalWidth = :originalWidth, originalHeight = :originalHeight, " +
            "processedWidth = :processedWidth, processedHeight = :processedHeight, " +
            "sourceText = :sourceText, translatedText = :translatedText, " +
            "segmentsJson = :segmentsJson, errorMessage = '', updatedAtMs = :now " +
            "WHERE id = :itemId"
    )
    suspend fun completeItem(
        itemId: String,
        originalWidth: Int,
        originalHeight: Int,
        processedWidth: Int,
        processedHeight: Int,
        sourceText: String,
        translatedText: String,
        segmentsJson: String,
        now: Long,
    )

    @Query(
        "UPDATE gallery_translation_items SET status = :status, errorMessage = :message, " +
            "updatedAtMs = :now WHERE id = :itemId"
    )
    suspend fun markItemFailed(
        itemId: String,
        status: GalleryItemStatus,
        message: String,
        now: Long,
    )

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded, " +
            "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed " +
            "FROM gallery_translation_items WHERE taskId = :taskId"
    )
    suspend fun progress(taskId: String): GalleryProgressRow

    @Query(
        "UPDATE gallery_translation_tasks SET totalCount = :total, " +
            "completedCount = :completed, successCount = :succeeded, failedCount = :failed, " +
            "lastError = :lastError WHERE id = :taskId"
    )
    suspend fun updateProgress(
        taskId: String,
        total: Int,
        completed: Int,
        succeeded: Int,
        failed: Int,
        lastError: String,
    )

    @Query(
        "UPDATE gallery_translation_items SET status = 'QUEUED', errorMessage = '', " +
            "attemptCount = 0, updatedAtMs = :now WHERE taskId = :taskId AND status = 'FAILED'"
    )
    suspend fun resetFailedItems(taskId: String, now: Long): Int

    @Query(
        "UPDATE gallery_translation_tasks SET status = 'QUEUED', finishedAtMs = NULL, " +
            "lastError = '', currentItemName = '' WHERE id = :taskId"
    )
    suspend fun resetTaskForRetry(taskId: String)

    @Transaction
    suspend fun resetFailedTask(taskId: String, now: Long): Int {
        val count = resetFailedItems(taskId, now)
        if (count > 0) resetTaskForRetry(taskId)
        return count
    }

    @Query("SELECT COUNT(*) FROM gallery_translation_items WHERE sourceUri = :sourceUri")
    suspend fun countSourceUriReferences(sourceUri: String): Int

    @Query("DELETE FROM gallery_translation_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)
}

class GalleryTranslationTypeConverters {
    @TypeConverter
    fun fromTaskStatus(value: GalleryTaskStatus): String = value.name

    @TypeConverter
    fun toTaskStatus(value: String): GalleryTaskStatus =
        GalleryTaskStatus.entries.firstOrNull { it.name == value } ?: GalleryTaskStatus.FAILED

    @TypeConverter
    fun fromItemStatus(value: GalleryItemStatus): String = value.name

    @TypeConverter
    fun toItemStatus(value: String): GalleryItemStatus =
        GalleryItemStatus.entries.firstOrNull { it.name == value } ?: GalleryItemStatus.FAILED
}

@Database(
    entities = [GalleryTranslationTaskEntity::class, GalleryTranslationItemEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(GalleryTranslationTypeConverters::class)
abstract class GalleryTranslationDatabase : RoomDatabase() {
    abstract fun galleryTranslationDao(): GalleryTranslationDao
}
