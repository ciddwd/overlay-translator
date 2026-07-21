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
                anthropicBaseUrl = "https://anthropic-private.example/v1/",
                anthropicApiKey = "anthropic-secret",
                anthropicModel = "claude-portable",
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
        assertEquals(JsonPrimitive("claude-portable"), encoded["anthropicModel"])
        SettingsFieldPolicy.protectedFieldNames.forEach { field ->
            assertFalse("protected export field: $field", field in encoded)
        }
    }

    @Test
    fun applyPortable_preservesLocalFieldsAndAppliesPortableFields() {
        val current = Settings(
            baseUrl = "https://local.example/v1/",
            apiKey = "local-secret",
            anthropicBaseUrl = "https://anthropic-local.example/v1/",
            anthropicApiKey = "anthropic-local-secret",
            anthropicModel = "claude-local",
            cleartextAllowedHosts = listOf("local.example"),
            floatingWindowX = 99,
            targetLang = "en",
        )
        val imported = Settings(
            baseUrl = "https://must-not-import.example/v1/",
            apiKey = "must-not-import",
            anthropicBaseUrl = "https://anthropic-must-not-import.example/v1/",
            anthropicApiKey = "anthropic-must-not-import",
            anthropicModel = "claude-imported",
            cleartextAllowedHosts = listOf("must-not-import.example"),
            floatingWindowX = 500,
            targetLang = "zh-TW",
            localLlmTemperature = 0.42f,
        )

        val merged = SettingsFieldPolicy.applyPortable(current, imported)

        assertEquals(current.baseUrl, merged.baseUrl)
        assertEquals(current.apiKey, merged.apiKey)
        assertEquals(current.anthropicBaseUrl, merged.anthropicBaseUrl)
        assertEquals(current.anthropicApiKey, merged.anthropicApiKey)
        assertEquals(imported.anthropicModel, merged.anthropicModel)
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

    @Test
    fun retiredMangaAdvancedSettings_areZeroForEveryPortableBoundary() {
        data class Case(val name: String, val settings: Settings)
        val cases = listOf(
            Case(
                "portable encoding",
                SettingsFieldPolicy.decodePortable(
                    SettingsFieldPolicy.encodePortable(
                        Settings(bubbleClusterGap = 47, mangaOcrCropPaddingPx = 23)
                    )
                ).settings,
            ),
            Case(
                "legacy portable decoding",
                SettingsFieldPolicy.decodePortable(
                    JsonObject(
                        mapOf(
                            "bubbleClusterGap" to JsonPrimitive(47),
                            "mangaOcrCropPaddingPx" to JsonPrimitive(23),
                        )
                    )
                ).settings,
            ),
            Case(
                "portable merge",
                SettingsFieldPolicy.applyPortable(
                    current = Settings(bubbleClusterGap = 19, mangaOcrCropPaddingPx = 11),
                    imported = Settings(bubbleClusterGap = 47, mangaOcrCropPaddingPx = 23),
                ),
            ),
        )

        cases.forEach { case ->
            assertEquals("${case.name} bubble gap", 0, case.settings.bubbleClusterGap)
            assertEquals("${case.name} crop padding", 0, case.settings.mangaOcrCropPaddingPx)
        }
    }

}
