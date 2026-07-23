package com.gameocr.app.onboarding

import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.data.TtsProvider
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {
    @Test
    fun steps_areTableDrivenByUsageAndTranslationMethod() {
        data class Case(
            val usage: OnboardingUsage,
            val method: OnboardingTranslationMethod,
            val expected: List<OnboardingStep>,
        )

        val commonStart = listOf(
            OnboardingStep.SOURCE_LANGUAGE,
            OnboardingStep.TARGET_LANGUAGE,
            OnboardingStep.DISPLAY_MODE,
            OnboardingStep.USAGE,
        )
        val cases = listOf(
            Case(
                OnboardingUsage.DAILY,
                OnboardingTranslationMethod.OFFLINE,
                commonStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD + OnboardingStep.TTS +
                    OnboardingStep.SUMMARY,
            ),
            Case(
                OnboardingUsage.DAILY,
                OnboardingTranslationMethod.CLOUD_LLM,
                commonStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.CLOUD_CONFIG + OnboardingStep.TTS +
                    OnboardingStep.SUMMARY,
            ),
            Case(
                OnboardingUsage.MANGA,
                OnboardingTranslationMethod.OFFLINE,
                commonStart + OnboardingStep.MANGA_DIRECTION +
                    OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD + OnboardingStep.TTS +
                    OnboardingStep.SUMMARY,
            ),
            Case(
                OnboardingUsage.MANGA,
                OnboardingTranslationMethod.CLOUD_LLM,
                commonStart + OnboardingStep.MANGA_DIRECTION +
                    OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.CLOUD_CONFIG + OnboardingStep.TTS +
                    OnboardingStep.SUMMARY,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                "${case.usage}/${case.method}",
                case.expected,
                OnboardingPolicy.stepsFor(
                    OnboardingDraft(
                        usage = case.usage,
                        translationMethod = case.method,
                    )
                ),
            )
        }
    }

    @Test
    fun dailyDisplayModes_mapToExpectedSettings() {
        data class Case(
            val display: OnboardingDisplayMode,
            val renderMode: RenderMode,
            val styleMode: OverlayStyleMode,
            val placement: OverlayPlacement,
        )
        val cases = listOf(
            Case(
                OnboardingDisplayMode.ADAPTIVE_OVERLAY,
                RenderMode.BLOCKS,
                OverlayStyleMode.ADAPTIVE,
                OverlayPlacement.OVERLAP,
            ),
            Case(
                OnboardingDisplayMode.BELOW_SOURCE,
                RenderMode.BLOCKS,
                OverlayStyleMode.FIXED,
                OverlayPlacement.BELOW,
            ),
            Case(
                OnboardingDisplayMode.FLOATING_WINDOW,
                RenderMode.FLOATING_WINDOW,
                OverlayStyleMode.FIXED,
                OverlayPlacement.ABOVE,
            ),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                Settings(
                    overlayPlacement = OverlayPlacement.ABOVE,
                    mergeAdjacentBlocks = true,
                    translationOutputFollowRecognition = false,
                    translationOutputLayout = TranslationOutputLayout.VERTICAL,
                    translationOutputDirection = TranslationOutputDirection.RIGHT_TO_LEFT,
                ),
                OnboardingDraft(
                    usage = OnboardingUsage.DAILY,
                    displayMode = case.display,
                ),
            )
            assertEquals(case.display.name, case.renderMode, actual.renderMode)
            assertEquals(case.display.name, case.styleMode, actual.overlayStyleMode)
            assertEquals(case.display.name, case.placement, actual.overlayPlacement)
            assertEquals(false, actual.mergeAdjacentBlocks)
            assertTrue(actual.translationOutputFollowRecognition)
            assertEquals(
                TranslationOutputLayout.FOLLOW_RECOGNITION,
                actual.translationOutputLayout,
            )
            assertEquals(
                TranslationOutputDirection.FOLLOW_RECOGNITION,
                actual.translationOutputDirection,
            )
        }
    }

    @Test
    fun mangaDirections_forceAdaptiveStandardMangaBaseline() {
        data class Case(
            val direction: OnboardingMangaDirection,
            val follow: Boolean,
            val layout: TranslationOutputLayout,
            val outputDirection: TranslationOutputDirection,
        )
        val cases = listOf(
            Case(
                OnboardingMangaDirection.FOLLOW_RECOGNITION,
                true,
                TranslationOutputLayout.FOLLOW_RECOGNITION,
                TranslationOutputDirection.FOLLOW_RECOGNITION,
            ),
            Case(
                OnboardingMangaDirection.HORIZONTAL_LEFT_TO_RIGHT,
                false,
                TranslationOutputLayout.HORIZONTAL,
                TranslationOutputDirection.LEFT_TO_RIGHT,
            ),
            Case(
                OnboardingMangaDirection.VERTICAL_RIGHT_TO_LEFT,
                false,
                TranslationOutputLayout.VERTICAL,
                TranslationOutputDirection.RIGHT_TO_LEFT,
            ),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                Settings(),
                OnboardingDraft(
                    usage = OnboardingUsage.MANGA,
                    displayMode = OnboardingDisplayMode.FLOATING_WINDOW,
                    mangaDirection = case.direction,
                ),
            )
            assertEquals(RenderMode.BLOCKS, actual.renderMode)
            assertEquals(OverlayStyleMode.ADAPTIVE, actual.overlayStyleMode)
            assertEquals(OverlayPlacement.OVERLAP, actual.overlayPlacement)
            assertTrue(actual.mergeAdjacentBlocks)
            assertEquals(MergeStrength.STANDARD, actual.mergeStrength)
            assertEquals(case.follow, actual.translationOutputFollowRecognition)
            assertEquals(case.layout, actual.translationOutputLayout)
            assertEquals(case.outputDirection, actual.translationOutputDirection)
        }
    }

    @Test
    fun cloudProviderPresets_haveVerifiedNonBlankConfiguration() {
        data class Case(
            val provider: CloudProvider,
            val url: String,
            val model: String,
            val protocol: CloudApiProtocol,
        )
        val cases = listOf(
            Case(
                CloudProvider.DEEPSEEK,
                "https://api.deepseek.com/v1/",
                "deepseek-v4-flash",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.KIMI,
                "https://api.moonshot.cn/v1/",
                "kimi-k3",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.MINIMAX,
                "https://api.minimaxi.com/v1/",
                "MiniMax-M3",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.GLM,
                "https://open.bigmodel.cn/api/paas/v4/",
                "glm-5.2",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.MIMO,
                "https://api.xiaomimimo.com/v1/",
                "mimo-v2.5-pro",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.OPENAI,
                "https://api.openai.com/v1/",
                "gpt-4.1-mini",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.CLAUDE,
                "https://api.anthropic.com",
                "claude-sonnet-4-5",
                CloudApiProtocol.ANTHROPIC,
            ),
            Case(
                CloudProvider.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                "gemini-3.6-flash",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.CUSTOM,
                "",
                "",
                CloudApiProtocol.OPENAI,
            ),
        )

        assertEquals(CloudProvider.entries.size, cases.size)
        cases.forEach { case ->
            assertEquals(case.provider.name, case.url, case.provider.baseUrl)
            assertEquals(case.provider.name, case.model, case.provider.model)
            assertEquals(case.provider.name, case.protocol, case.provider.protocol)
        }
    }

    @Test
    fun cloudValidation_coversAllFieldFailuresAndSuccess() {
        data class Case(
            val url: String,
            val key: String,
            val model: String,
            val expected: CloudConfigError?,
        )
        val cases = listOf(
            Case("", "key", "model", CloudConfigError.BASE_URL_REQUIRED),
            Case("not a url", "key", "model", CloudConfigError.BASE_URL_INVALID),
            Case("ftp://example.com", "key", "model", CloudConfigError.BASE_URL_INVALID),
            Case("https://example.com/v1", "", "model", CloudConfigError.API_KEY_REQUIRED),
            Case("https://example.com/v1", "key", "", CloudConfigError.MODEL_REQUIRED),
            Case("https://example.com/v1", "key", "model", null),
        )

        cases.forEach { case ->
            assertEquals(
                case.url,
                case.expected,
                OnboardingPolicy.cloudConfigError(
                    OnboardingDraft(
                        cloudBaseUrl = case.url,
                        cloudApiKey = case.key,
                        cloudModel = case.model,
                    )
                ),
            )
        }
    }

    @Test
    fun translationMethods_mapToCorrectEngineAndCredentialFields() {
        val offline = OnboardingPolicy.apply(
            Settings(),
            OnboardingDraft(translationMethod = OnboardingTranslationMethod.OFFLINE),
        )
        assertEquals(TranslatorEngine.GOOGLE_ML_KIT, offline.translatorEngine)

        val openAi = OnboardingPolicy.apply(
            Settings(anthropicApiKey = "keep-anthropic"),
            OnboardingDraft(
                translationMethod = OnboardingTranslationMethod.CLOUD_LLM,
                cloudProvider = CloudProvider.GEMINI,
                cloudBaseUrl = "https://example.com/v1",
                cloudApiKey = "open-key",
                cloudModel = "open-model",
            ),
        )
        assertEquals(TranslatorEngine.OPENAI, openAi.translatorEngine)
        assertEquals("https://example.com/v1/", openAi.baseUrl)
        assertEquals("open-key", openAi.apiKey)
        assertEquals("open-model", openAi.model)
        assertEquals("keep-anthropic", openAi.anthropicApiKey)

        val anthropic = OnboardingPolicy.apply(
            Settings(apiKey = "keep-openai"),
            OnboardingDraft(
                translationMethod = OnboardingTranslationMethod.CLOUD_LLM,
                cloudProvider = CloudProvider.CLAUDE,
                cloudBaseUrl = "https://api.anthropic.com",
                cloudApiKey = "claude-key",
                cloudModel = "claude-model",
            ),
        )
        assertEquals(TranslatorEngine.ANTHROPIC, anthropic.translatorEngine)
        assertEquals("https://api.anthropic.com", anthropic.anthropicBaseUrl)
        assertEquals("claude-key", anthropic.anthropicApiKey)
        assertEquals("claude-model", anthropic.anthropicModel)
        assertEquals("keep-openai", anthropic.apiKey)
    }

    @Test
    fun supportedLanguagePairs_areTableDriven() {
        data class Case(val source: String, val target: String, val supported: Boolean)
        val cases = listOf(
            Case("ja", "zh-CN", true),
            Case("en", "de", true),
            Case("zh-TW", "en", true),
            Case("auto", "en", false),
            Case("yue", "zh-CN", false),
            Case("en", "unknown", false),
        )

        cases.forEach { case ->
            assertEquals(
                "${case.source}->${case.target}",
                case.supported,
                OnboardingPolicy.isMlKitPairSupported(case.source, case.target),
            )
        }
    }

    @Test
    fun mangaOfflinePairSupport_isTableDriven() {
        data class Case(val source: String, val target: String, val supported: Boolean)
        val cases = listOf(
            Case("ja", "zh-CN", true),
            Case("ja", "en", false),
            Case("en", "zh-CN", false),
            Case("ja", "zh-TW", false),
        )

        cases.forEach { case ->
            assertEquals(
                "${case.source}->${case.target}",
                case.supported,
                OnboardingPolicy.isSakuraPairSupported(case.source, case.target),
            )
        }
    }

    @Test
    fun offlineUsage_mapsToDailyLanguagePacksOrMangaModels() {
        data class Case(
            val usage: OnboardingUsage,
            val expectedTranslator: TranslatorEngine,
            val expectedOcr: OcrEngineKind,
        )
        val cases = listOf(
            Case(
                OnboardingUsage.DAILY,
                TranslatorEngine.GOOGLE_ML_KIT,
                OcrEngineKind.ML_KIT_AUTO,
            ),
            Case(
                OnboardingUsage.MANGA,
                TranslatorEngine.LOCAL_SAKURA,
                OcrEngineKind.MANGA_OCR_JA,
            ),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                Settings(ocrEngine = OcrEngineKind.MANGA_OCR_JA),
                OnboardingDraft(
                    usage = case.usage,
                    translationMethod = OnboardingTranslationMethod.OFFLINE,
                ),
            )
            assertEquals(case.usage.name, case.expectedTranslator, actual.translatorEngine)
            assertEquals(case.usage.name, case.expectedOcr, actual.ocrEngine)
        }
    }

    @Test
    fun ttsChoices_mapToSettingsForEveryTranslationMethod() {
        data class Case(
            val choice: OnboardingTtsChoice,
            val enabled: Boolean,
            val provider: TtsProvider,
        )
        val cases = listOf(
            Case(OnboardingTtsChoice.DISABLED, false, TtsProvider.MINIMAX),
            Case(OnboardingTtsChoice.SYSTEM, true, TtsProvider.SYSTEM),
            Case(OnboardingTtsChoice.GENERIC_HTTP, true, TtsProvider.GENERIC_HTTP),
            Case(OnboardingTtsChoice.VOLCENGINE, true, TtsProvider.VOLCENGINE),
            Case(OnboardingTtsChoice.MINIMAX, true, TtsProvider.MINIMAX),
            Case(OnboardingTtsChoice.MIMO, true, TtsProvider.MIMO),
        )

        OnboardingTranslationMethod.entries.forEach { method ->
            cases.forEach { case ->
                val actual = OnboardingPolicy.apply(
                    Settings(ttsEnabled = true, ttsProvider = TtsProvider.MINIMAX),
                    OnboardingDraft(
                        translationMethod = method,
                        ttsChoice = case.choice,
                        cloudApiKey = "key",
                    ),
                )
                val caseName = "$method/${case.choice}"
                assertEquals(caseName, case.enabled, actual.ttsEnabled)
                assertEquals(caseName, case.provider, actual.ttsProvider)
            }
        }
    }

    @Test
    fun mangaOfflineDownloads_includeOnlyMissingModels() {
        data class Case(
            val mangaOcrReady: Boolean,
            val sakuraReady: Boolean,
            val expected: List<ModelDownloadSpec>,
        )
        val mangaOcr = ModelDownloadSpec.mangaOcr()
        val sakura = ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4)
        val cases = listOf(
            Case(false, false, listOf(mangaOcr, sakura)),
            Case(true, false, listOf(sakura)),
            Case(false, true, listOf(mangaOcr)),
            Case(true, true, emptyList()),
        )

        cases.forEach { case ->
            assertEquals(
                "${case.mangaOcrReady}/${case.sakuraReady}",
                case.expected,
                mangaOfflineDownloadSpecs(
                    MangaOfflineModelReadiness(
                        mangaOcrReady = case.mangaOcrReady,
                        sakuraReady = case.sakuraReady,
                    )
                ),
            )
        }
    }

    @Test
    fun validCloudConfigurationReturnsNoError() {
        assertNull(
            OnboardingPolicy.cloudConfigError(
                OnboardingDraft(
                    cloudBaseUrl = CloudProvider.DEEPSEEK.baseUrl,
                    cloudApiKey = "secret",
                    cloudModel = CloudProvider.DEEPSEEK.model,
                )
            )
        )
    }
}
