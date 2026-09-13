package com.gameocr.app.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DictionaryPackStatus(
    val spec: DictionaryPackSpec,
    val installed: Boolean,
    val version: String = "",
    val entryCount: Long? = null,
    val byteCount: Long = 0L,
    val invalidReason: String? = null,
)

sealed interface DictionaryPackInstallResult {
    data class Success(val status: DictionaryPackStatus) : DictionaryPackInstallResult
    data class Failure(val reason: String) : DictionaryPackInstallResult
}

@Singleton
class DictionaryPackInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun statuses(): List<DictionaryPackStatus> = withContext(Dispatchers.IO) {
        DictionaryPackCatalog.all.map { status(it) }
    }

    suspend fun importPack(
        uri: Uri,
        expectedId: DictionaryPackId,
    ): DictionaryPackInstallResult = withContext(Dispatchers.IO) {
        val directory = packDirectory(context).apply { mkdirs() }
        val temporary = File.createTempFile("dictionary-import-", ".db", directory)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext DictionaryPackInstallResult.Failure("无法读取所选文件")
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PACK_BYTES) {
                            return@withContext DictionaryPackInstallResult.Failure("词典包超过大小限制")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val validated = validate(temporary, expectedId)
            if (validated.invalidReason != null) {
                return@withContext DictionaryPackInstallResult.Failure(validated.invalidReason)
            }

            val target = packFile(context, expectedId)
            val backup = File(directory, "${target.name}.backup")
            if (backup.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                return@withContext DictionaryPackInstallResult.Failure("无法替换现有词典包")
            }
            if (!temporary.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                return@withContext DictionaryPackInstallResult.Failure("无法保存词典包")
            }
            backup.delete()
            DictionaryPackInstallResult.Success(status(DictionaryPackCatalog.byId(expectedId)))
        } catch (error: Throwable) {
            DictionaryPackInstallResult.Failure(error.message ?: "词典包导入失败")
        } finally {
            temporary.delete()
        }
    }

    suspend fun delete(id: DictionaryPackId): Boolean = withContext(Dispatchers.IO) {
        val file = packFile(context, id)
        !file.exists() || file.delete()
    }

    private fun status(spec: DictionaryPackSpec): DictionaryPackStatus {
        val file = packFile(context, spec.id)
        if (!file.isFile) return DictionaryPackStatus(spec = spec, installed = false)
        return validate(file, spec.id).copy(byteCount = file.length())
    }

    private fun validate(file: File, expectedId: DictionaryPackId): DictionaryPackStatus {
        val spec = DictionaryPackCatalog.byId(expectedId)
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                OPEN_READONLY or NO_LOCALIZED_COLLATORS,
            ).use { database ->
                val integrity = database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "invalid"
                }
                require(integrity.equals("ok", ignoreCase = true)) { "词典包数据库损坏" }
                DictionaryPackContract.requiredColumns.forEach { (table, required) ->
                    val actual = database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                        buildSet {
                            while (cursor.moveToNext()) add(cursor.getString(1))
                        }
                    }
                    require(actual.containsAll(required)) { "词典包结构不兼容：$table" }
                }
                val metadata = database.rawQuery(
                    "SELECT key, value FROM ${DictionaryPackContract.METADATA_TABLE}",
                    null,
                ).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
                    }
                }
                require(metadata["pack_id"] == expectedId.wireId) { "所选文件不是 ${spec.displayName} 词典包" }
                require(metadata["schema_version"]?.toIntOrNull() == DictionaryPackContract.SCHEMA_VERSION) {
                    "词典包版本不兼容"
                }
                DictionaryPackStatus(
                    spec = spec,
                    installed = true,
                    version = metadata["data_version"].orEmpty(),
                    entryCount = metadata["entry_count"]?.toLongOrNull(),
                    byteCount = file.length(),
                )
            }
        }.getOrElse { error ->
            DictionaryPackStatus(
                spec = spec,
                installed = false,
                byteCount = file.length(),
                invalidReason = error.message ?: "无法读取词典包",
            )
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "dictionaries"
        private const val MAX_PACK_BYTES = 2L * 1024L * 1024L * 1024L

        fun packDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

        fun packFile(context: Context, id: DictionaryPackId): File =
            File(packDirectory(context), id.fileName)
    }
}
