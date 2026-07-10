package com.gameocr.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TranslationPresetImportPlan(
    val importedPresets: List<TranslationPreset>,
    val importedCount: Int,
    val overwrittenNames: List<String>,
    val addedNames: List<String>,
)

data class TranslationPresetImportResult(
    val presets: List<TranslationPreset>,
    val importedCount: Int,
    val overwrittenNames: List<String>,
    val addedNames: List<String>,
)

object TranslationPresetTransfer {
    const val DEFAULT_FILE_NAME: String = "overlay-translator-presets.otpresets"

    private const val FORMAT = "overlay-translator.translation-presets"
    private const val EXPORT_VERSION = 1
    private const val ENVELOPE_VERSION = 1
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val KEY_PURPOSE = "game_ocr_translation_preset_export_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val secureRandom = SecureRandom()

    fun encodeEncrypted(presets: List<TranslationPreset>): String {
        val payload = TranslationPresetExportPayload(
            version = EXPORT_VERSION,
            presets = prepareImportedPresets(presets)
        )
        val plainText = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, nonce, plainText)
        return json.encodeToString(
            TranslationPresetEncryptedEnvelope(
                format = FORMAT,
                version = ENVELOPE_VERSION,
                algorithm = TRANSFORMATION,
                nonce = Base64.getEncoder().encodeToString(nonce),
                ciphertext = Base64.getEncoder().encodeToString(encrypted),
            )
        )
    }

    fun decodeEncrypted(text: String): List<TranslationPreset> {
        val envelope = runCatching {
            json.decodeFromString<TranslationPresetEncryptedEnvelope>(text)
        }.getOrElse {
            throw IllegalArgumentException("Invalid preset export file.", it)
        }
        require(envelope.format == FORMAT) { "Unsupported preset export format." }
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported preset export version." }
        require(envelope.algorithm == TRANSFORMATION) { "Unsupported preset export encryption." }

        val nonce = runCatching { Base64.getDecoder().decode(envelope.nonce) }.getOrElse {
            throw IllegalArgumentException("Invalid preset export nonce.", it)
        }
        require(nonce.size == NONCE_BYTES) { "Invalid preset export nonce." }
        val ciphertext = runCatching { Base64.getDecoder().decode(envelope.ciphertext) }.getOrElse {
            throw IllegalArgumentException("Invalid preset export ciphertext.", it)
        }
        val plainText = runCatching { crypt(Cipher.DECRYPT_MODE, nonce, ciphertext) }.getOrElse {
            throw IllegalArgumentException("Preset export decrypt failed.", it)
        }
        val payload = runCatching {
            json.decodeFromString<TranslationPresetExportPayload>(plainText.toString(Charsets.UTF_8))
        }.getOrElse {
            throw IllegalArgumentException("Invalid preset export payload.", it)
        }
        require(payload.version == EXPORT_VERSION) { "Unsupported preset payload version." }
        return prepareImportedPresets(payload.presets)
    }

    fun planImport(
        existing: List<TranslationPreset>,
        imported: List<TranslationPreset>,
    ): TranslationPresetImportPlan {
        val prepared = prepareImportedPresets(imported)
        val existingNames = existing.asSequence()
            .filterNot { TranslationPresetCatalog.isBuiltIn(it.id) }
            .associateBy { normalizedName(it.name) }
        val overwritten = prepared.mapNotNull { preset ->
            existingNames[normalizedName(preset.name)]?.name
        }
        val added = prepared.mapNotNull { preset ->
            preset.name.takeIf { normalizedName(it) !in existingNames }
        }
        return TranslationPresetImportPlan(
            importedPresets = prepared,
            importedCount = prepared.size,
            overwrittenNames = overwritten,
            addedNames = added,
        )
    }

    fun mergeImportedPresets(
        existing: List<TranslationPreset>,
        imported: List<TranslationPreset>,
    ): TranslationPresetImportResult {
        val prepared = prepareImportedPresets(imported)
        val next = existing
            .filterNot { TranslationPresetCatalog.isBuiltIn(it.id) }
            .toMutableList()
        val usedIds = next.mapTo(mutableSetOf()) { it.id }
        val overwritten = mutableListOf<String>()
        val added = mutableListOf<String>()

        prepared.forEach { importedPreset ->
            val existingIndex = next.indexOfFirst {
                normalizedName(it.name) == normalizedName(importedPreset.name)
            }
            if (existingIndex >= 0) {
                val existingPreset = next[existingIndex]
                overwritten += existingPreset.name
                next[existingIndex] = normalizePreset(
                    importedPreset.copy(
                        id = existingPreset.id,
                        name = existingPreset.name,
                        shortName = existingPreset.shortName.ifBlank {
                            translationPresetShortName(existingPreset.name)
                        },
                    )
                )
            } else {
                val newId = uniqueImportedId(importedPreset, usedIds)
                usedIds += newId
                val addedPreset = normalizePreset(importedPreset.copy(id = newId))
                next += addedPreset
                added += addedPreset.name
            }
        }

        return TranslationPresetImportResult(
            presets = next,
            importedCount = prepared.size,
            overwrittenNames = overwritten,
            addedNames = added,
        )
    }

    private fun prepareImportedPresets(presets: List<TranslationPreset>): List<TranslationPreset> {
        val uniqueByName = linkedMapOf<String, TranslationPreset>()
        presets.forEach { preset ->
            if (TranslationPresetCatalog.isBuiltIn(preset.id)) return@forEach
            val name = normalizedDisplayName(preset.name) ?: return@forEach
            val cleaned = preset.copy(
                id = preset.id.trim(),
                name = name,
                shortName = preset.shortName.trim().ifBlank { translationPresetShortName(name) },
            )
            uniqueByName[normalizedName(name)] = normalizePreset(cleaned)
        }
        return uniqueByName.values.toList()
    }

    private fun normalizePreset(preset: TranslationPreset): TranslationPreset =
        TranslationPresetCatalog.fromSettings(
            id = preset.id,
            name = preset.name,
            shortName = preset.shortName.ifBlank { translationPresetShortName(preset.name) },
            settings = preset.applyTo(Settings())
        )

    private fun uniqueImportedId(
        preset: TranslationPreset,
        usedIds: Set<String>,
    ): String {
        val importedId = preset.id.trim()
        if (
            importedId.isNotBlank() &&
            importedId !in usedIds &&
            !TranslationPresetCatalog.isBuiltIn(importedId)
        ) {
            return importedId
        }
        val hash = sha256(preset.name, preset.settingsHash, preset.model).take(12)
        var candidate = "custom_imported_$hash"
        var suffix = 2
        while (candidate in usedIds || TranslationPresetCatalog.isBuiltIn(candidate)) {
            candidate = "custom_imported_${hash}_$suffix"
            suffix++
        }
        return candidate
    }

    private fun crypt(mode: Int, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, transferKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(FORMAT.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(input)
    }

    private fun transferKey(): SecretKeySpec =
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256")
                .digest(KEY_PURPOSE.toByteArray(Charsets.UTF_8)),
            "AES"
        )

    private fun sha256(vararg parts: Any?): String {
        val source = buildString {
            parts.forEach { part ->
                val value = part?.toString().orEmpty()
                append(value.length)
                append(':')
                append(value)
                append('|')
            }
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(((byte.toInt() and 0xff) + 0x100).toString(16).substring(1))
            }
        }
    }

    private fun normalizedDisplayName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() }

    private fun normalizedName(name: String): String =
        name.trim().lowercase()

    private fun translationPresetShortName(name: String): String =
        name.take(8)
}

@Serializable
private data class TranslationPresetExportPayload(
    val version: Int,
    val presets: List<TranslationPreset>,
)

@Serializable
private data class TranslationPresetEncryptedEnvelope(
    val format: String,
    val version: Int,
    val algorithm: String,
    val nonce: String,
    val ciphertext: String,
)
