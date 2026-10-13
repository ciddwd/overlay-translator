package com.gameocr.app.data

import com.gameocr.app.dictionary.supportsOnlineDictionaryLookup
import com.gameocr.app.gallery.GalleryTranslationWorkPolicy
import com.gameocr.app.translate.TranslationCache
import com.gameocr.app.translate.TranslationPromptContextPolicy
import com.gameocr.app.translate.TranslationVisualContextPolicy
import com.gameocr.app.translate.deepSeekBalanceUrl
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
    @Test
    fun providerResolution_tableDriven_onlyOverridesSelectedOpenAiRequests() {
        TranslatorEngine.entries.forEach { engine ->
            OpenAiProvider.entries.forEach { provider ->
                val saved = Settings(
                    translatorEngine = engine,
                    openAiProvider = provider,
                    baseUrl = "https://api.deepseek.com/v1/",
                    apiKey = "private-test-key",
                    model = "custom-model",
                )
                val expected = engine == TranslatorEngine.OPENAI && provider == OpenAiProvider.BILIBILI
                val resolved = saved.resolveOpenAiProvider()
                assertEquals("$engine/$provider", expected, saved.usesBilibiliTranslation())
                if (expected) {
                    assertEquals("https://index-translate.bilibili.com/v1/", resolved.baseUrl)
                    assertEquals("index", resolved.apiKey)
                    assertEquals("Index-Translate-35B-A3B", resolved.model)
                    assertEquals(TranslatorEngine.OPENAI, resolved.translatorEngine)
                    assertEquals(saved, resolved.copy(baseUrl = saved.baseUrl, apiKey = saved.apiKey, model = saved.model))
                    assertEquals(resolved, resolved.resolveOpenAiProvider())
                } else {
                    assertSame(saved, resolved)
                }
                assertEquals("private-test-key", saved.apiKey)
            }
        }
    }

    @Test
    fun emptyCustomAccountIsNotRequiredAndReturningToCustomRestoresIt_tableDriven() {
        listOf("", "private-test-key").forEach { key ->
            val custom = Settings(apiKey = key)
            val selected = custom.copy(openAiProvider = OpenAiProvider.BILIBILI)
            assertEquals("index", selected.resolveOpenAiProvider().apiKey)
            assertEquals(custom, selected.copy(openAiProvider = OpenAiProvider.CUSTOM).resolveOpenAiProvider())
            assertNull(deepSeekBalanceUrl(selected))
        }
    }

    @Test
    fun requestSnapshotPreservesAdvancedOptionsAndContext_tableDriven() {
        listOf(false, true).forEach { streaming ->
            val saved = Settings(
                openAiProvider = OpenAiProvider.BILIBILI,
                sourceLang = "ko",
                targetLang = "zh-CN",
                promptTemplate = "custom translation prompt",
                dictionaryPrompt = "custom dictionary prompt",
                openAiRequestOptions = OpenAiRequestOptions(
                    encodeUserTextBase64 = true,
                    sendScreenImage = true,
                    thinkingModeEnabled = false,
                ),
                streamingTranslate = streaming,
                apiTimeoutSeconds = 60,
                translationContextMode = TranslationContextMode.PAGE_CONTEXT,
                runtimeTranslationPromptContext = RuntimeTranslationPromptContext(
                    currentPage = listOf("source"),
                    previousFrame = listOf(RuntimeDialogueTurn("previous source", "translation")),
                ),
            )
            val request = saved.resolveOpenAiProvider()
            assertEquals(saved, request.copy(baseUrl = saved.baseUrl, apiKey = saved.apiKey, model = saved.model))
            assertTrue(TranslationPromptContextPolicy.supportsContext(request.translatorEngine))
            assertTrue(supportsOnlineDictionaryLookup(request.translatorEngine))
            assertTrue(TranslationVisualContextPolicy.supportsEngine(request.translatorEngine))
            assertTrue(GalleryTranslationWorkPolicy.requiresNetwork(OcrEngineKind.ML_KIT_KOREAN, request.translatorEngine))
        }
    }

    @Test
    fun settingsAndPresetRoundTripsKeepSelectionWithoutSavingFixedConnection_tableDriven() {
        val json = Json { encodeDefaults = true }
        OpenAiProvider.entries.forEach { provider ->
            val saved = Settings(openAiProvider = provider, apiKey = "private-test-key", model = "custom-model")
            val decoded = json.decodeFromString<Settings>(json.encodeToString(saved))
            assertEquals(saved, decoded)
            val portable = SettingsFieldPolicy.encodePortable(saved)
            assertEquals(provider, SettingsFieldPolicy.decodePortable(portable).settings.openAiProvider)
            assertFalse(portable.containsKey("apiKey"))
            assertFalse(portable.toString().contains("private-test-key"))
            val preset = TranslationPresetCatalog.fromSettings("bili-test", "test", "test", saved)
            val imported = json.decodeFromString<TranslationPreset>(json.encodeToString(preset))
            val applied = imported.applyTo(Settings(apiKey = "current-test-key"))
            assertEquals(provider, applied.openAiProvider)
            assertEquals("custom-model", applied.model)
            assertEquals("current-test-key", applied.apiKey)
            assertTrue(TranslationPresetCatalog.matchesSettings(preset, applied))
        }
    }

    @Test
    fun providerIsPartOfPresetIdentityAndUnknownImportsFallBackToCustom() {
        val custom = Settings()
        val preset = TranslationPresetCatalog.fromSettings("bili-test", "test", "test", custom)
        assertFalse(TranslationPresetCatalog.matchesSettings(preset, custom.copy(openAiProvider = OpenAiProvider.BILIBILI)))
        val decoded = SettingsFieldPolicy.decodePortable(
            JsonObject(mapOf("openAiProvider" to JsonPrimitive("unknown-provider"))),
        )
        assertEquals(OpenAiProvider.CUSTOM, decoded.settings.openAiProvider)
        assertEquals(listOf("openAiProvider"), decoded.skippedFields)
    }

    @Test
    fun cacheIsolation_tableDriven_distinguishesProvidersAndNormalizesTrailingSlash() {
        val cache = TranslationCache()
        val bili = Settings(openAiProvider = OpenAiProvider.BILIBILI).resolveOpenAiProvider()
        val custom = bili.copy(openAiProvider = OpenAiProvider.CUSTOM)
        val differentEndpoint = custom.copy(baseUrl = "https://api.deepseek.com/v1/")
        fun key(settings: Settings) = cache.key("hello", settings.openAiCacheModelKey(), "zh-CN", "prompt")
        listOf(custom, differentEndpoint).forEach { other -> assertNotEquals(key(bili), key(other)) }
        assertEquals(key(bili), key(bili.copy(baseUrl = bili.baseUrl.trimEnd('/'))))
        cache.put(key(bili), "translation", bili)
        assertEquals("translation", cache.get(key(bili), bili))
        assertNull(cache.get(key(custom), custom))
        assertNull(cache.get(key(bili), bili.copy(developerOptionsEnabled = true, disableTranslationCache = true)))
    }
}
