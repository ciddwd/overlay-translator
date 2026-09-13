package com.gameocr.app.onboarding

import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.data.TtsProvider
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelReadiness
import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {
    @Test
    fun steps_areTableDrivenByLanguageUsageAndTranslationMethod() {
        data class Case(
            val sourceLang: String,
            val targetLang: String,
            val usage: OnboardingUsage,
            val method: OnboardingTranslationMethod,
            val localLlmSupported: Boolean,
            val expected: List<OnboardingStep>,
        )

        val commonStart = listOf(
            OnboardingStep.WELCOME,
            OnboardingStep.SOURCE_LANGUAGE,
            OnboardingStep.TARGET_LANGUAGE,
            OnboardingStep.USAGE,
        )
        val dailyStart = commonStart + OnboardingStep.DISPLAY_MODE
        val mangaStart = commonStart + OnboardingStep.MANGA_DIRECTION
        val cases = listOf(
            Case(
                "ja", "zh-CN", OnboardingUsage.DAILY, OnboardingTranslationMethod.OFFLINE, true,
                dailyStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD + OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.DAILY, OnboardingTranslationMethod.CLOUD_LLM, true,
                dailyStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.CLOUD_CONFIG + OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, true,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ko", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, true,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "fr", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, true,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.CLOUD_LLM, true,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD +
                    OnboardingStep.CLOUD_CONFIG +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, false,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD +
                    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ko", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, false,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD +
                    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "fr", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, false,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD +
                    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.CLOUD_LLM, false,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD +
                    OnboardingStep.CLOUD_CONFIG +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                "${case.sourceLang}->${case.targetLang}/${case.usage}/${case.method}/" +
                    "local=${case.localLlmSupported}",
                case.expected,
                OnboardingPolicy.stepsFor(
                    OnboardingDraft(
                        sourceLang = case.sourceLang,
                        targetLang = case.targetLang,
                        usage = case.usage,
                        translationMethod = case.method,
                    ),
                    localLlmSupported = case.localLlmSupported,
                ),
            )
        }
    }

    @Test
    fun recommendedModelsStep_combinesOcrAndHyMt2AcrossSupportedCases() {
        data class Case(
            val sourceLang: String,
            val usage: OnboardingUsage,
            val method: OnboardingTranslationMethod,
            val expectsPaddle: Boolean,
            val expectsHyMt2: Boolean,
            val expectsStep: Boolean,
        )
        val cases = listOf(
            Case("ja", OnboardingUsage.DAILY, OnboardingTranslationMethod.OFFLINE, false, false, false),
            Case("ko-KR", OnboardingUsage.DAILY, OnboardingTranslationMethod.OFFLINE, false, false, false),
            Case("zh-TW", OnboardingUsage.DAILY, OnboardingTranslationMethod.CLOUD_LLM, false, false, false),
            Case("fr", OnboardingUsage.DAILY, OnboardingTranslationMethod.OFFLINE, false, false, false),
            Case("ja", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, false, false, false),
            Case("zh-TW", OnboardingUsage.MANGA, OnboardingTranslationMethod.CLOUD_LLM, true, false, true),
            Case("en-US", OnboardingUsage.MANGA, OnboardingTranslationMethod.CLOUD_LLM, true, false, true),
            Case("fr", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, true, true, true),
            Case("ko-KR", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, true, true, true),
        )

        cases.forEach { case ->
            val draft = OnboardingDraft(
                sourceLang = case.sourceLang,
                usage = case.usage,
                translationMethod = case.method,
            )
            val steps = OnboardingPolicy.stepsFor(draft)
            val caseName = "${case.sourceLang}/${case.usage}/${case.method}"
            assertEquals(caseName, case.expectsPaddle, OnboardingPolicy.shouldRecommendPaddleOcr(draft))
            assertEquals(caseName, case.expectsHyMt2, OnboardingPolicy.usesHyMt2MangaTranslation(draft))
            assertEquals(
                caseName,
                case.expectsStep,
                OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD in steps,
            )
            assertTrue(
                "$caseName must contain at most one combined model step",
                steps.count { it == OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD } <= 1,
            )
            if (case.expectsStep) {
                assertTrue(
                    steps.indexOf(OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD) >
                        steps.indexOf(OnboardingStep.TRANSLATION_METHOD)
                )
            }
        }
    }

    @Test
    fun dailyOcrPrefersMlKitWhenTheSourceLanguageIsSupported() {
        data class Case(val sourceLang: String, val expected: OcrEngineKind)
        val cases = listOf(
            Case("ja", OcrEngineKind.ML_KIT_JAPANESE),
            Case("ja-JP", OcrEngineKind.ML_KIT_JAPANESE),
            Case("ko", OcrEngineKind.ML_KIT_KOREAN),
            Case("ko_KR", OcrEngineKind.ML_KIT_KOREAN),
            Case("zh-CN", OcrEngineKind.ML_KIT_CHINESE),
            Case("ZH-tw", OcrEngineKind.ML_KIT_CHINESE),
            Case("en", OcrEngineKind.ML_KIT_LATIN),
            Case("en-US", OcrEngineKind.ML_KIT_LATIN),
            Case("fr", OcrEngineKind.ML_KIT_LATIN),
            Case("de-DE", OcrEngineKind.ML_KIT_LATIN),
            Case("ru-RU", OcrEngineKind.PADDLE_ONNX),
            Case("ar", OcrEngineKind.PADDLE_ONNX),
            Case("th", OcrEngineKind.PADDLE_ONNX),
        )

        cases.forEach { case ->
            assertEquals(
                case.sourceLang,
                case.expected,
                OnboardingPolicy.ocrEngineForSourceLanguage(case.sourceLang),
            )
        }
    }

    @Test
    fun mangaOcrUsesLanguageSpecificPaddleModelForEachMangaSourceLanguage() {
        data class Case(
            val sourceLang: String,
            val expectedEngine: OcrEngineKind,
            val expectedPaddleVersion: PaddleModelVersion?,
        )
        val cases = listOf(
            Case("ja", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_SMALL),
            Case("zh-TW", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL),
            Case("en-US", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL),
            Case("fr", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL),
            Case("ko-KR", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V5_KOREAN),
        )

        cases.forEach { case ->
            val draft = OnboardingDraft(
                sourceLang = case.sourceLang,
                usage = OnboardingUsage.MANGA,
            )
            assertEquals(case.sourceLang, case.expectedEngine, OnboardingPolicy.recommendedOcrEngine(draft))
            assertEquals(
                case.sourceLang,
                case.expectedPaddleVersion,
                OnboardingPolicy.recommendedPaddleModelVersion(draft),
            )
        }
    }

    @Test
    fun applyingOnboarding_setsRecommendedOcrAndPaddleVersion() {
        data class Case(
            val sourceLang: String,
            val usage: OnboardingUsage,
            val method: OnboardingTranslationMethod,
            val expectedEngine: OcrEngineKind,
            val expectedVersion: PaddleModelVersion,
        )
        val cases = listOf(
            Case(
                "ja",
                OnboardingUsage.DAILY,
                OnboardingTranslationMethod.OFFLINE,
                OcrEngineKind.ML_KIT_JAPANESE,
                PaddleModelVersion.V6_TINY,
            ),
            Case(
                "ko",
                OnboardingUsage.DAILY,
                OnboardingTranslationMethod.CLOUD_LLM,
                OcrEngineKind.ML_KIT_KOREAN,
                PaddleModelVersion.V6_TINY,
            ),
            Case(
                "fr",
                OnboardingUsage.DAILY,
                OnboardingTranslationMethod.CLOUD_LLM,
                OcrEngineKind.ML_KIT_LATIN,
                PaddleModelVersion.V6_TINY,
            ),
            Case(
                "fr",
                OnboardingUsage.MANGA,
                OnboardingTranslationMethod.OFFLINE,
                OcrEngineKind.PADDLE_ONNX,
                PaddleModelVersion.V6_SMALL,
            ),
            Case(
                "ja",
                OnboardingUsage.MANGA,
                OnboardingTranslationMethod.OFFLINE,
                OcrEngineKind.MANGA_OCR_JA,
                PaddleModelVersion.V6_SMALL,
            ),
            Case(
                "ko-KR",
                OnboardingUsage.MANGA,
                OnboardingTranslationMethod.OFFLINE,
                OcrEngineKind.PADDLE_ONNX,
                PaddleModelVersion.V5_KOREAN,
            ),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                Settings(paddleModelVersion = PaddleModelVersion.V6_TINY),
                OnboardingDraft(
                    sourceLang = case.sourceLang,
                    usage = case.usage,
                    translationMethod = case.method,
                ),
            )
            assertEquals(case.sourceLang, case.expectedEngine, actual.ocrEngine)
            assertEquals(case.sourceLang, case.expectedVersion, actual.paddleModelVersion)
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
    fun mangaDirections_forceAdaptiveNonMergingMangaBaseline() {
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
                Settings(
                    mergeAdjacentBlocks = true,
                    mergeStrength = MergeStrength.AGGRESSIVE,
                ),
                OnboardingDraft(
                    usage = OnboardingUsage.MANGA,
                    displayMode = OnboardingDisplayMode.FLOATING_WINDOW,
                    mangaDirection = case.direction,
                ),
            )
            assertEquals(RenderMode.BLOCKS, actual.renderMode)
            assertEquals(OverlayStyleMode.ADAPTIVE, actual.overlayStyleMode)
            assertEquals(OverlayPlacement.OVERLAP, actual.overlayPlacement)
            assertEquals(false, actual.mergeAdjacentBlocks)
            assertEquals(MergeStrength.AGGRESSIVE, actual.mergeStrength)
            assertEquals(case.follow, actual.translationOutputFollowRecognition)
            assertEquals(case.layout, actual.translationOutputLayout)
            assertEquals(case.outputDirection, actual.translationOutputDirection)
        }
    }

    @Test
    fun mangaMergePolicy_enablesStandardMergeOnlyForNonJapaneseSources() {
        data class Case(
            val sourceLang: String,
            val expectedEnabled: Boolean,
            val expectedStrength: MergeStrength,
        )
        val cases = listOf(
            Case("ja", false, MergeStrength.AGGRESSIVE),
            Case("ja-JP", false, MergeStrength.AGGRESSIVE),
            Case("ko", true, MergeStrength.STANDARD),
            Case("ko_KR", true, MergeStrength.STANDARD),
            Case("zh-TW", true, MergeStrength.STANDARD),
            Case("en-US", true, MergeStrength.STANDARD),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                settings = Settings(
                    mergeAdjacentBlocks = false,
                    mergeStrength = MergeStrength.AGGRESSIVE,
                ),
                draft = OnboardingDraft(
                    sourceLang = case.sourceLang,
                    usage = OnboardingUsage.MANGA,
                ),
            )
            assertEquals(case.sourceLang, case.expectedEnabled, actual.mergeAdjacentBlocks)
            assertEquals(case.sourceLang, case.expectedStrength, actual.mergeStrength)
        }
    }

    @Test
    fun cloudProviders_haveVerifiedAddressesAndNoModelDefaults() {
        data class Case(
            val provider: CloudProvider,
            val url: String,
            val protocol: CloudApiProtocol,
        )
        val cases = listOf(
            Case(
                CloudProvider.DEEPSEEK,
                "https://api.deepseek.com/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.KIMI,
                "https://api.moonshot.cn/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.MINIMAX,
                "https://api.minimaxi.com/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.GLM,
                "https://open.bigmodel.cn/api/paas/v4/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.MIMO,
                "https://api.xiaomimimo.com/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.OPENAI,
                "https://api.openai.com/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.CLAUDE,
                "https://api.anthropic.com",
                CloudApiProtocol.ANTHROPIC,
            ),
            Case(
                CloudProvider.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.MODELSCOPE,
                "https://api-inference.modelscope.cn/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.OPENROUTER,
                "https://openrouter.ai/api/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.AIHUBMIX,
                "https://aihubmix.com/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.AI_302,
                "https://api.302ai.cn/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.SCNET,
                "https://api.scnet.cn/api/llm/v1/",
                CloudApiProtocol.OPENAI,
            ),
            Case(
                CloudProvider.CUSTOM,
                "",
                CloudApiProtocol.OPENAI,
            ),
        )

        assertEquals(CloudProvider.entries.size, cases.size)
        cases.forEach { case ->
            assertEquals(case.provider.name, case.url, case.provider.baseUrl)
            assertEquals(case.provider.name, "", OnboardingDraft(cloudProvider = case.provider).cloudModel)
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
    fun translationMethod_setsTheExpectedDefaultContextMode_tableDriven() {
        data class Case(
            val name: String,
            val method: OnboardingTranslationMethod,
            val provider: CloudProvider,
            val existing: TranslationContextMode,
            val expected: TranslationContextMode,
        )
        val cases = listOf(
            Case(
                "offline from fast",
                OnboardingTranslationMethod.OFFLINE,
                CloudProvider.DEEPSEEK,
                TranslationContextMode.FAST_PER_SEGMENT,
                TranslationContextMode.FAST_PER_SEGMENT,
            ),
            Case(
                "offline resets page to fast",
                OnboardingTranslationMethod.OFFLINE,
                CloudProvider.DEEPSEEK,
                TranslationContextMode.PAGE_CONTEXT,
                TranslationContextMode.FAST_PER_SEGMENT,
            ),
            Case(
                "OpenAI-compatible cloud uses page context",
                OnboardingTranslationMethod.CLOUD_LLM,
                CloudProvider.DEEPSEEK,
                TranslationContextMode.FAST_PER_SEGMENT,
                TranslationContextMode.PAGE_CONTEXT,
            ),
            Case(
                "Anthropic cloud uses page context",
                OnboardingTranslationMethod.CLOUD_LLM,
                CloudProvider.CLAUDE,
                TranslationContextMode.CONTINUOUS_CONTEXT,
                TranslationContextMode.PAGE_CONTEXT,
            ),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                settings = Settings(translationContextMode = case.existing),
                draft = OnboardingDraft(
                    translationMethod = case.method,
                    cloudProvider = case.provider,
                    cloudBaseUrl = case.provider.baseUrl,
                    cloudApiKey = "key",
                    cloudModel = "chosen-model",
                ),
            )
            assertEquals(case.name, case.expected, actual.translationContextMode)
        }
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
    fun offlineTranslationAvailability_followsLocalLlmThenMlKitFallback() {
        data class Case(
            val name: String,
            val source: String,
            val target: String,
            val usage: OnboardingUsage,
            val localLlmSupported: Boolean,
            val expectedEngine: TranslatorEngine,
            val available: Boolean,
        )
        val cases = listOf(
            Case("daily ML Kit", "ja", "zh-CN", OnboardingUsage.DAILY, true,
                TranslatorEngine.GOOGLE_ML_KIT, true),
            Case("daily unsupported pair", "yue", "zh-CN", OnboardingUsage.DAILY, true,
                TranslatorEngine.GOOGLE_ML_KIT, false),
            Case("Japanese manga Sakura", "ja", "zh-CN", OnboardingUsage.MANGA, true,
                TranslatorEngine.LOCAL_SAKURA, true),
            Case("multilingual manga Hy-MT2", "yue", "zh-CN", OnboardingUsage.MANGA, true,
                TranslatorEngine.LOCAL_HY_MT2, true),
            Case("Japanese manga ML Kit fallback", "ja", "zh-CN", OnboardingUsage.MANGA, false,
                TranslatorEngine.GOOGLE_ML_KIT, true),
            Case("unsupported manga fallback pair", "yue", "zh-CN", OnboardingUsage.MANGA, false,
                TranslatorEngine.GOOGLE_ML_KIT, false),
        )

        cases.forEach { case ->
            val draft = OnboardingDraft(
                sourceLang = case.source,
                targetLang = case.target,
                usage = case.usage,
                translationMethod = OnboardingTranslationMethod.OFFLINE,
            )
            assertEquals(
                case.name,
                case.expectedEngine,
                OnboardingPolicy.offlineTranslatorEngine(draft, case.localLlmSupported),
            )
            assertEquals(
                case.name,
                case.available,
                OnboardingPolicy.canUseOfflineTranslation(draft, case.localLlmSupported),
            )
        }
    }

    @Test
    fun offlineUsage_mapsToLanguageAppropriateOcrAndTranslator() {
        data class Case(
            val sourceLang: String,
            val targetLang: String,
            val usage: OnboardingUsage,
            val localLlmSupported: Boolean,
            val expectedTranslator: TranslatorEngine,
            val expectedOcr: OcrEngineKind,
        )
        val cases = listOf(
            Case(
                "ja", "zh-CN", OnboardingUsage.DAILY, true,
                TranslatorEngine.GOOGLE_ML_KIT, OcrEngineKind.ML_KIT_JAPANESE,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, true,
                TranslatorEngine.LOCAL_SAKURA, OcrEngineKind.MANGA_OCR_JA,
            ),
            Case(
                "ko", "zh-CN", OnboardingUsage.MANGA, true,
                TranslatorEngine.LOCAL_HY_MT2, OcrEngineKind.PADDLE_ONNX,
            ),
            Case(
                "fr", "zh-CN", OnboardingUsage.MANGA, true,
                TranslatorEngine.LOCAL_HY_MT2, OcrEngineKind.PADDLE_ONNX,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, false,
                TranslatorEngine.GOOGLE_ML_KIT, OcrEngineKind.MANGA_OCR_JA,
            ),
            Case(
                "ko", "zh-CN", OnboardingUsage.MANGA, false,
                TranslatorEngine.GOOGLE_ML_KIT, OcrEngineKind.PADDLE_ONNX,
            ),
            Case(
                "fr", "zh-CN", OnboardingUsage.MANGA, false,
                TranslatorEngine.GOOGLE_ML_KIT, OcrEngineKind.PADDLE_ONNX,
            ),
        )

        cases.forEach { case ->
            val actual = OnboardingPolicy.apply(
                Settings(ocrEngine = OcrEngineKind.MANGA_OCR_JA),
                OnboardingDraft(
                    sourceLang = case.sourceLang,
                    targetLang = case.targetLang,
                    usage = case.usage,
                    translationMethod = OnboardingTranslationMethod.OFFLINE,
                ),
                localLlmSupported = case.localLlmSupported,
            )
            val caseName = "${case.sourceLang}/local=${case.localLlmSupported}"
            assertEquals(caseName, case.expectedTranslator, actual.translatorEngine)
            assertEquals(caseName, case.expectedOcr, actual.ocrEngine)
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
    fun mangaOfflineDownloads_includeOnlyMissingRequiredModels() {
        data class Case(
            val paddleReady: Boolean,
            val mangaOcrReady: Boolean,
            val sakuraInstalled: Boolean,
            val sakuraSupported: Boolean,
            val includeSakura: Boolean,
            val expected: List<ModelDownloadSpec>,
            val expectedAllReady: Boolean,
        )
        val paddle = ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)
        val mangaOcr = ModelDownloadSpec.mangaOcr()
        val sakura = ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4)
        val cases = listOf(
            Case(false, false, false, true, true, listOf(paddle, mangaOcr, sakura), false),
            Case(true, false, false, true, true, listOf(mangaOcr, sakura), false),
            Case(false, true, true, true, true, listOf(paddle), false),
            Case(true, true, false, true, true, listOf(sakura), false),
            Case(true, true, true, true, true, emptyList(), true),
            Case(false, false, false, true, false, listOf(paddle, mangaOcr), false),
            Case(true, true, false, true, false, emptyList(), true),
            Case(true, true, true, false, true, emptyList(), false),
            Case(true, true, false, false, true, emptyList(), false),
        )

        cases.forEach { case ->
            val readiness = MangaOfflineModelReadiness(
                paddle = ModelReadiness(paddle, case.paddleReady, true),
                mangaOcr = ModelReadiness(mangaOcr, case.mangaOcrReady, true),
                sakura = sakura.takeIf { case.includeSakura }?.let {
                    ModelReadiness(it, case.sakuraInstalled, case.sakuraSupported)
                },
            )
            val caseName = "paddle=${case.paddleReady}/manga=${case.mangaOcrReady}/" +
                "sakura=${case.sakuraInstalled}/${case.sakuraSupported}/" +
                "include=${case.includeSakura}"
            assertEquals(
                caseName,
                case.expected,
                mangaOfflineDownloadSpecs(readiness),
            )
            assertEquals(caseName, case.expectedAllReady, readiness.allReady)
            assertEquals(caseName, case.expected.isNotEmpty(), readiness.hasDownloadableModels)
        }
    }

    @Test
    fun recommendedModelDownloads_includeOnlyMissingOcrAndTranslationModels() {
        data class Case(
            val paddleVersion: PaddleModelVersion?,
            val paddleReady: Boolean,
            val includeHyMt2: Boolean,
            val hyMt2Installed: Boolean,
            val hyMt2Supported: Boolean,
            val expected: List<ModelDownloadSpec>,
            val expectedRequiredReady: Boolean,
            val expectedAllReady: Boolean,
        )
        val paddle = ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)
        val hyMt2 = ModelDownloadSpec.llm(LlmModelKind.HY_MT2_1_8B_Q4_K_M)
        val cases = listOf(
            Case(PaddleModelVersion.V6_SMALL, false, true, false, true, listOf(paddle, hyMt2), false, false),
            Case(PaddleModelVersion.V6_SMALL, true, true, false, true, listOf(hyMt2), false, false),
            Case(PaddleModelVersion.V6_SMALL, false, true, true, true, listOf(paddle), true, false),
            Case(PaddleModelVersion.V6_SMALL, true, true, true, true, emptyList(), true, true),
            Case(null, true, true, false, true, listOf(hyMt2), false, false),
            Case(PaddleModelVersion.V6_SMALL, true, true, false, false, emptyList(), false, false),
            Case(PaddleModelVersion.V6_SMALL, true, true, true, false, emptyList(), false, false),
            Case(PaddleModelVersion.V6_SMALL, false, false, true, true, listOf(paddle), true, false),
            Case(null, true, false, true, true, emptyList(), true, true),
        )

        cases.forEach { case ->
            val readiness = RecommendedModelsReadiness(
                paddleVersion = case.paddleVersion,
                paddle = case.paddleVersion?.let {
                    ModelReadiness(ModelDownloadSpec.paddle(it), case.paddleReady, true)
                },
                hyMt2 = hyMt2.takeIf { case.includeHyMt2 }?.let {
                    ModelReadiness(it, case.hyMt2Installed, case.hyMt2Supported)
                },
            )
            val caseName = "paddle=${case.paddleVersion}/${case.paddleReady}," +
                "hyMt2=${case.includeHyMt2}/${case.hyMt2Installed}/${case.hyMt2Supported}"
            assertEquals(
                caseName,
                case.expected,
                recommendedModelsDownloadSpecs(readiness),
            )
            assertEquals(caseName, case.expectedAllReady, readiness.allReady)
            assertEquals(caseName, case.expectedRequiredReady, readiness.requiredModelsReady)
            assertEquals(caseName, case.expected.isNotEmpty(), readiness.hasDownloadableModels)
        }
    }

    @Test
    fun validCloudConfigurationReturnsNoError() {
        assertNull(
            OnboardingPolicy.cloudConfigError(
                OnboardingDraft(
                    cloudBaseUrl = CloudProvider.DEEPSEEK.baseUrl,
                    cloudApiKey = "secret",
                    cloudModel = "chosen-model",
                )
            )
        )
    }
}
