package com.gameocr.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class AutoOcrLocalityTest {
    @Test fun settingsAndPresetsNeverTransferLocalRoutes_tableDriven() {
        assertEquals(SettingsPortability.DEVICE_LOCAL,
            SettingsFieldPolicy.rules.single { it.name == "autoOcr" }.portability)
        val custom = AutoOcrSettings(
            routes = mapOf("ja" to AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA),
                "fr" to AutoOcrRoute(OcrEngineKind.UMI_OCR)),
            additionalLanguages = listOf("fr"),
        )
        for (config in listOf(AutoOcrSettings(), custom)) {
            val current = Settings(autoOcr = config)
            val preset = TranslationPresetCatalog.fromSettings("auto-local", "Local", "Local", current)
            assertFalse(Json.encodeToJsonElement(TranslationPreset.serializer(), preset).toString().contains("autoOcr"))
            assertEquals(config, preset.applyTo(current).autoOcr)
            val exported = SettingsFieldPolicy.encodePortable(current.copy(translationPresets = listOf(preset)))
            assertFalse(exported.toString().contains("autoOcr"))
            val incoming = JsonObject(mapOf("autoOcr" to Json.encodeToJsonElement(custom)))
            assertEquals(config, SettingsFieldPolicy.applyPortable(current,
                SettingsFieldPolicy.decodePortable(incoming).settings).autoOcr)
            val output = ByteArrayOutputStream()
            SettingsBundleTransfer.write(output, current.copy(translationPresets = listOf(preset)), resolveFontFile = { null })
            val preview = SettingsBundleTransfer.readPreview(ByteArrayInputStream(output.toByteArray()))
            assertEquals(AutoOcrSettings(), preview.settings!!.autoOcr)
            assertEquals(config, SettingsFieldPolicy.applyPortable(current, preview.settings).autoOcr)
            preview.presets.forEach { assertEquals(config, it.applyTo(current).autoOcr) }
        }
    }
}
