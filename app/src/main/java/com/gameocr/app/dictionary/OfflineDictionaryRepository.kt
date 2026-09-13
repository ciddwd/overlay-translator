package com.gameocr.app.dictionary

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import com.gameocr.app.translate.WordResult
import com.gameocr.app.translate.WordSense
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import timber.log.Timber

@Singleton
class OfflineDictionaryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : OfflineWordDictionary {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookup(word: String, languageCode: String): WordResult? =
        withContext(Dispatchers.IO) {
            val spec = DictionaryPackCatalog.forLanguage(languageCode) ?: return@withContext null
            val file = DictionaryPackInstaller.packFile(context, spec.id)
            if (!file.isFile) {
                Timber.d("OfflineDictionary lookup pack=%s result=missing_pack", spec.id.wireId)
                return@withContext null
            }
            val normalized = normalizeDictionaryHeadword(word)
            if (normalized.isBlank()) return@withContext null

            try {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    OPEN_READONLY or NO_LOCALIZED_COLLATORS,
                ).use { database -> query(database, normalized) }.also { result ->
                    Timber.d("OfflineDictionary lookup pack=%s result=%s senses=%d", spec.id.wireId,
                        if (result == null) "miss" else "hit", result?.senses?.size ?: 0)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.e(error, "OfflineDictionary lookup failed pack=%s language=%s termLength=%d",
                    spec.id.wireId, spec.languageCode, normalized.length)
                // The caller already separates a database failure from an ordinary miss.
                throw error
            }
        }

    private fun query(database: SQLiteDatabase, normalized: String): WordResult? {
        val entries = database.rawQuery(
            """
            SELECT DISTINCT e.id, e.lemma, e.reading, e.phonetic, e.inflections
            FROM ${DictionaryPackContract.ENTRIES_TABLE} e
            LEFT JOIN ${DictionaryPackContract.FORMS_TABLE} f ON f.entry_id = e.id
            WHERE e.normalized_headword = ? OR f.normalized_form = ?
            ORDER BY CASE WHEN e.normalized_headword = ? THEN 0 ELSE 1 END, e.id
            LIMIT 8
            """.trimIndent(),
            arrayOf(normalized, normalized, normalized),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EntryRow(
                            id = cursor.getLong(0),
                            lemma = cursor.stringOrEmpty(1),
                            reading = cursor.stringOrEmpty(2),
                            phonetic = cursor.stringOrEmpty(3),
                            inflections = decodeStringList(cursor.stringOrEmpty(4)),
                        )
                    )
                }
            }
        }
        if (entries.isEmpty()) return null

        val senses = entries.flatMap { entry -> querySenses(database, entry.id) }
        if (senses.isEmpty() && entries.all { it.phonetic.isBlank() && it.reading.isBlank() }) {
            return null
        }
        val primary = entries.first()
        return WordResult(
            phonetic = primary.phonetic.ifBlank { primary.reading },
            inflections = entries.flatMap(EntryRow::inflections).distinct(),
            lemma = primary.lemma,
            senses = senses,
        )
    }

    private fun querySenses(database: SQLiteDatabase, entryId: Long): List<WordSense> =
        database.rawQuery(
            """
            SELECT part_of_speech, definition, form_note
            FROM ${DictionaryPackContract.SENSES_TABLE}
            WHERE entry_id = ?
            ORDER BY position
            """.trimIndent(),
            arrayOf(entryId.toString()),
        ).use { cursor ->
            val grouped = linkedMapOf<Pair<String, String>, MutableList<String>>()
            while (cursor.moveToNext()) {
                val pos = cursor.stringOrEmpty(0).trim()
                val definition = cursor.stringOrEmpty(1).trim()
                val formNote = cursor.stringOrEmpty(2).trim()
                if (definition.isNotEmpty()) {
                    grouped.getOrPut(pos to formNote, ::mutableListOf).add(definition)
                }
            }
            grouped.map { (key, definitions) ->
                WordSense(
                    partOfSpeech = key.first,
                    definitions = definitions.distinct(),
                    formNote = key.second,
                )
            }
        }

    private fun decodeStringList(raw: String): List<String> = raw
        .takeIf(String::isNotBlank)
        ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
        .orEmpty()

    private data class EntryRow(
        val id: Long,
        val lemma: String,
        val reading: String,
        val phonetic: String,
        val inflections: List<String>,
    )
}

private fun Cursor.stringOrEmpty(index: Int): String = if (isNull(index)) "" else getString(index).orEmpty()
