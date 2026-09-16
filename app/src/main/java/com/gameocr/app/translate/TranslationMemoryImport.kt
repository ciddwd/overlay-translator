package com.gameocr.app.translate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

internal const val TM_IMPORT_MAX_ENTRIES: Int = 100_000
internal const val TM_IMPORT_MAX_FILE_BYTES: Int = 10 * 1024 * 1024

data class TranslationMemoryImportEntry(
    val source: String,
    val target: String
)

data class TranslationMemoryImportDocument(
    val packageName: String,
    val sourceLang: String,
    val targetLang: String,
    val entries: List<TranslationMemoryImportEntry>
)

internal class TranslationMemoryImportFormatException(message: String) : IllegalArgumentException(message)

object TranslationMemoryImportJson {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parseDocument(content: String): TranslationMemoryImportDocument {
        if (content.length > TM_IMPORT_MAX_FILE_BYTES) {
            throw TranslationMemoryImportFormatException("File exceeds size limit")
        }
        val root = runCatching { json.parseToJsonElement(content).jsonObject }
            .getOrElse { throw TranslationMemoryImportFormatException("Invalid JSON format") }

        val packageName = root.stringValue("packageName")?.trim()
            ?: throw TranslationMemoryImportFormatException("Missing packageName")
        if (packageName.isEmpty()) throw TranslationMemoryImportFormatException("Empty packageName")

        val sourceLang = root.stringValue("sourceLang")?.trim()
            ?: throw TranslationMemoryImportFormatException("Missing sourceLang")
        if (sourceLang.isEmpty()) throw TranslationMemoryImportFormatException("Empty sourceLang")

        val targetLang = root.stringValue("targetLang")?.trim()
            ?: throw TranslationMemoryImportFormatException("Missing targetLang")
        if (targetLang.isEmpty()) throw TranslationMemoryImportFormatException("Empty targetLang")

        val entriesArray = root["entries"]?.jsonArray
            ?: throw TranslationMemoryImportFormatException("Missing entries array")
        if (entriesArray.size > TM_IMPORT_MAX_ENTRIES) {
            throw TranslationMemoryImportFormatException("Too many entries (max $TM_IMPORT_MAX_ENTRIES)")
        }

        val parsedEntries = mutableListOf<TranslationMemoryImportEntry>()
        for (element in entriesArray) {
            val obj = element as? JsonObject ?: continue
            val source = obj.stringValue("source")?.trim() ?: continue
            val target = obj.stringValue("target")?.trim() ?: continue
            if (source.isEmpty() || target.isEmpty()) continue
            parsedEntries.add(TranslationMemoryImportEntry(source, target))
        }

        if (parsedEntries.isEmpty()) {
            throw TranslationMemoryImportFormatException("No valid entries found")
        }

        return TranslationMemoryImportDocument(packageName, sourceLang, targetLang, parsedEntries)
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.takeIf(JsonPrimitive::isString)?.content
    }
}
