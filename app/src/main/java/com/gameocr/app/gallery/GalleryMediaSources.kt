package com.gameocr.app.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

data class GalleryAlbum(
    val bucketId: String,
    val name: String,
    val count: Int,
    val coverUri: String,
)

/**
 * Reads whole albums (MediaStore buckets, needs the photos permission) and single folders
 * (a system folder-picker tree, no permission). Folders list only their own images, not subfolders.
 */
@Singleton
class GalleryMediaSources @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun albums(): List<GalleryAlbum> = withContext(Dispatchers.IO) {
        val counts = linkedMapOf<String, Triple<String, Int, String>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                ),
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucket = cursor.getString(bucketColumn) ?: continue
                    val existing = counts[bucket]
                    if (existing == null) {
                        val cover = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn),
                        ).toString()
                        counts[bucket] = Triple(cursor.getString(nameColumn).orEmpty(), 1, cover)
                    } else {
                        counts[bucket] = existing.copy(second = existing.second + 1)
                    }
                }
            }
        }.onFailure { Timber.w(it, "[gallery-import] album query failed") }
        counts.map { (bucket, value) ->
            GalleryAlbum(bucketId = bucket, name = value.first, count = value.second, coverUri = value.third)
        }
    }

    suspend fun albumEntries(bucketId: String): List<GalleryImportEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<GalleryImportEntry>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                ),
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(bucketId),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val taken = cursor.getLong(takenColumn)
                    entries += GalleryImportEntry(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn),
                        ).toString(),
                        name = cursor.getString(nameColumn).orEmpty(),
                        dateMs = if (taken > 0) taken else cursor.getLong(modifiedColumn) * 1_000L,
                    )
                }
            }
        }.onFailure { Timber.w(it, "[gallery-import] album entries query failed") }
        entries
    }

    /** Name of the picked folder and the images directly inside it, in natural file-name order. */
    suspend fun folder(treeUri: Uri): Pair<String, List<GalleryImportEntry>> = withContext(Dispatchers.IO) {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val folderName = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
        }.getOrDefault("")
        val entries = mutableListOf<GalleryImportEntry>()
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2).orEmpty()
                    if (!mime.startsWith("image/")) continue
                    entries += GalleryImportEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)).toString(),
                        name = cursor.getString(1).orEmpty(),
                        dateMs = cursor.getLong(3),
                    )
                }
            }
        }.onFailure { Timber.w(it, "[gallery-import] folder listing failed") }
        folderName to entries
    }
}
