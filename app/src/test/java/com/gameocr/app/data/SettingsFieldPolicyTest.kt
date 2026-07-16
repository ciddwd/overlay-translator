package com.gameocr.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFieldPolicyTest {

    @Test
    fun portableEncoding_isAnAllowlistAndNeverIncludesProtectedFields() {
        val encoded = SettingsFieldPolicy.encodePortable(
            Settings(
                baseUrl = "https://private.example/v1/",
                apiKey = "secret",
                umiOcrBaseUrl = "http://192.168.1.5:1224/api/ocr",
                cleartextAllowedHosts = listOf("192.168.1.5"),
                floatingWindowX = 123,
                floatingWindowY = 456,
                promptTemplate = "portable prompt",
                pinnedLanguages = listOf("ja", "zh-TW"),
            )
        )

        assertTrue("portable prompt", "promptTemplate" in encoded)
        assertTrue("portable pinned languages", "pinnedLanguages" in encoded)
        SettingsFieldPolicy.protectedFieldNames.forEach { field ->
            assertFalse("protected export field: $field", field in encoded)
        }
    }

    @Test
    fun applyPortable_preservesLocalFieldsAndAppliesPortableFields() {
        val current = Settings(
            baseUrl = "https://local.example/v1/",
            apiKey = "local-secret",
            cleartextAllowedHosts = listOf("local.example"),
            floatingWindowX = 99,
            targetLang = "en",
        )
        val imported = Settings(
            baseUrl = "https://must-not-import.example/v1/",
            apiKey = "must-not-import",
            cleartextAllowedHosts = listOf("must-not-import.example"),
            floatingWindowX = 500,
            targetLang = "zh-TW",
            localLlmTemperature = 0.42f,
        )

        val merged = SettingsFieldPolicy.applyPortable(current, imported)

        assertEquals(current.baseUrl, merged.baseUrl)
        assertEquals(current.apiKey, merged.apiKey)
        assertEquals(current.cleartextAllowedHosts, merged.cleartextAllowedHosts)
        assertEquals(current.floatingWindowX, merged.floatingWindowX)
        assertEquals(imported.targetLang, merged.targetLang)
        assertEquals(imported.localLlmTemperature, merged.localLlmTemperature)
    }

    @Test
    fun decodePortable_skipsOneFutureEnumWithoutRejectingThePackage() {
        val values = SettingsFieldPolicy.encodePortable(
            Settings(targetLang = "zh-TW", translatorEngine = TranslatorEngine.DEEPL)
        ).toMutableMap()
        values["translatorEngine"] = JsonPrimitive("FUTURE_ENGINE")

        val decoded = SettingsFieldPolicy.decodePortable(JsonObject(values))

        assertEquals(listOf("translatorEngine"), decoded.skippedFields)
        assertEquals(Settings().translatorEngine, decoded.settings.translatorEngine)
        assertEquals("zh-TW", decoded.settings.targetLang)
    }

}
