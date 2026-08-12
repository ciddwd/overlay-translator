package com.gameocr.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFieldPolicyTest {

    @Test
    fun retiredCrossLineSetting_tableDriven_isIgnoredAtEveryImportBoundary() {
        data class Case(val name: String, val legacyValue: Boolean)
        val legacyJson = Json { ignoreUnknownKeys = true }

        listOf(
            Case("legacy enabled representation", false),
            Case("legacy disabled representation", true),
        ).forEach { case ->
            val field = "disableCrossLineContextTranslation"
            val portable = SettingsFieldPolicy.decodePortable(
                JsonObject(mapOf(field to JsonPrimitive(case.legacyValue)))
            )
            val fullSettings = legacyJson.decodeFromString<Settings>(
                """{"$field":${case.legacyValue}}"""
            )

            assertFalse(case.name, field in SettingsFieldPolicy.portableFieldNames)
            assertFalse(case.name, field in SettingsFieldPolicy.encodePortable(Settings()))
            assertEquals(case.name, TranslationContextMode.FAST_PER_SEGMENT, portable.settings.translationContextMode)
            assertEquals(case.name, TranslationContextMode.FAST_PER_SEGMENT, fullSettings.translationContextMode)
        }
    }

    @Test
    fun failedTranslationRetry_legacyNamesMigrateWithoutOverridingTheNewName() {
        data class Case(
            val name: String,
            val values: JsonObject,
            val expected: Boolean,
        )

        listOf(
            Case(
                "legacy portable field",
                JsonObject(mapOf("retryEmptyTranslation" to JsonPrimitive(true))),
                true,
            ),
            Case(
                "new portable field",
                JsonObject(mapOf("retryFailedTranslation" to JsonPrimitive(true))),
                true,
            ),
            Case(
                "new field wins when both exist",
                JsonObject(
                    mapOf(
                        "retryEmptyTranslation" to JsonPrimitive(true),
                        "retryFailedTranslation" to JsonPrimitive(false),
                    )
                ),
                false,
            ),
        ).forEach { case ->
            val decoded = SettingsFieldPolicy.decodePortable(case.values)
            assertEquals(case.name, case.expected, decoded.settings.retryFailedTranslation)
            assertTrue(case.name, decoded.skippedFields.isEmpty())
        }

        assertTrue(
            "legacy full settings JSON",
            Json.decodeFromString<Settings>("""{"retryEmptyTranslation":true}""")
                .retryFailedTranslation,
        )
        assertTrue(
            "legacy preset JSON",
            Json.decodeFromString<TranslationPreset>(
                """{"id":"legacy","name":"Legacy","retryEmptyTranslation":true}"""
            )
                .retryFailedTranslation,
        )
    }

    @Test
    fun llmOutboundEncoding_tableDriven_roundTripsPortableSettings() {
        data class Case(val name: String, val options: OpenAiRequestOptions)

        listOf(
            Case("disabled", OpenAiRequestOptions()),
            Case("Base64", OpenAiRequestOptions(encodeUserTextBase64 = true)),
            Case("Unicode", OpenAiRequestOptions(encodeUserTextUnicode = true)),
            Case("thinking enabled", OpenAiRequestOptions(thinkingModeEnabled = true)),
        ).forEach { case ->
            val decoded = SettingsFieldPolicy.decodePortable(
                SettingsFieldPolicy.encodePortable(Settings(openAiRequestOptions = case.options))
            )

            assertEquals(case.name, case.options, decoded.settings.openAiRequestOptions)
            assertTrue(case.name, decoded.skippedFields.isEmpty())
        }
    }

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
    fun floatingWindowAutoHide_roundTripsAndDefaultsOff() {
        assertFalse(Settings().floatingWindowAutoHideWhenObstructing)

        val decoded = SettingsFieldPolicy.decodePortable(
            SettingsFieldPolicy.encodePortable(
                Settings(floatingWindowAutoHideWhenObstructing = true)
            )
        )

        assertTrue(decoded.settings.floatingWindowAutoHideWhenObstructing)
        assertTrue(decoded.skippedFields.isEmpty())
    }

    @Test
    fun requestScopedPromptContext_isNeverExportedOrImported() {
        val context = RuntimeTranslationPromptContext(
            currentApplication = "Game",
            glossary = listOf(RuntimeGlossaryTerm("Alice", "爱丽丝")),
            currentPage = listOf("Current line"),
            previousFrame = listOf(RuntimeDialogueTurn("Previous", "上一句")),
        )
        val encoded = SettingsFieldPolicy.encodePortable(
            Settings(
                runtimeTranslationContext = "runtime-only",
                runtimeTranslationPromptContext = context,
            )
        )

        assertFalse("generic runtime context", "runtimeTranslationContext" in encoded)
        assertFalse("typed runtime context", "runtimeTranslationPromptContext" in encoded)

        val decoded = SettingsFieldPolicy.decodePortable(encoded).settings
        assertEquals("", decoded.runtimeTranslationContext)
        assertEquals(RuntimeTranslationPromptContext(), decoded.runtimeTranslationPromptContext)
    }

    @Test
    fun sourcePreservationMasterGate_roundTripsAsPortableSetting() {
        data class Case(val name: String, val enabled: Boolean)

        listOf(
            Case("enabled", true),
            Case("disabled", false),
        ).forEach { case ->
            val encoded = SettingsFieldPolicy.encodePortable(
                Settings(sourcePreservationEnabled = case.enabled)
            )
            val decoded = SettingsFieldPolicy.decodePortable(encoded).settings

            assertEquals(case.name, case.enabled, decoded.sourcePreservationEnabled)
            assertTrue(case.name, "sourcePreservationEnabled" in encoded)
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
        assertEquals(imported.localLlmContextSize, merged.localLlmContextSize)
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
