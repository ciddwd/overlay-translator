package com.gameocr.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class MlKitRecentLanguageLocalityTest {
    @Test fun historyIsNeverExportedOrImported_tableDriven() {
        val field = "mlKitRecentSourceLanguages"
        assertEquals(SettingsPortability.DEVICE_LOCAL, SettingsFieldPolicy.rules.single { it.name == field }.portability)
        for (history in listOf(listOf("fr", "ru", "ja", "en"), listOf("ko", "de", "zh-CN", "es"))) {
            val local = Settings(mlKitRecentSourceLanguages = history)
            assertFalse(SettingsFieldPolicy.encodePortable(local).containsKey(field))
            val forgedImport = buildJsonObject { put(field, buildJsonArray { add("it"); add("pt") }) }
            val decoded = SettingsFieldPolicy.decodePortable(forgedImport)
            assertEquals(history, SettingsFieldPolicy.applyPortable(local, decoded.settings).mlKitRecentSourceLanguages)
            val output = ByteArrayOutputStream()
            SettingsBundleTransfer.write(output, local, resolveFontFile = { null })
            val preview = SettingsBundleTransfer.readPreview(ByteArrayInputStream(output.toByteArray()))
            assertNotEquals(history, preview.settings!!.mlKitRecentSourceLanguages)
            assertEquals(history, SettingsFieldPolicy.applyPortable(local, preview.settings).mlKitRecentSourceLanguages)
            val preset = TranslationPresetCatalog.fromSettings(
                id = "local_test", name = "Local test", shortName = "Local", settings = local,
            )
            assertFalse(Json.encodeToJsonElement(TranslationPreset.serializer(), preset).toString().contains(field))
            assertEquals(history, preset.applyTo(local).mlKitRecentSourceLanguages)
        }
    }
}
