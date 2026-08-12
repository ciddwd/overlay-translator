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
    fun steps_areTableDrivenByLanguageUsageAndTranslationMethod() {
        data class Case(
            val sourceLang: String,
            val targetLang: String,
            val usage: OnboardingUsage,
            val method: OnboardingTranslationMethod,
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
                "ja", "zh-CN", OnboardingUsage.DAILY, OnboardingTranslationMethod.OFFLINE,
                dailyStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD + OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.DAILY, OnboardingTranslationMethod.CLOUD_LLM,
                dailyStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.CLOUD_CONFIG + OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ko", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "fr", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA, OnboardingTranslationMethod.CLOUD_LLM,
                mangaStart + OnboardingStep.TRANSLATION_METHOD +
                    OnboardingStep.MANGA_OFFLINE_DOWNLOAD +
                    OnboardingStep.CLOUD_CONFIG +
                    OnboardingStep.TTS + OnboardingStep.SUMMARY,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                "${case.sourceLang}->${case.targetLang}/${case.usage}/${case.method}",
                case.expected,
                OnboardingPolicy.stepsFor(
                    OnboardingDraft(
                        sourceLang = case.sourceLang,
                        targetLang = case.targetLang,
                        usage = case.usage,
                        translationMethod = case.method,
                    )
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
            Case("ko-KR", OnboardingUsage.MANGA, OnboardingTranslationMethod.OFFLINE, false, true, true),
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
    fun mangaOcrUsesMangaRecognizerWithV6SmallDetectorForJapaneseChinese() {
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
            // PP-OCRv6 Small has no Korean recognizer; ML Kit Korean is the safe fallback.
            Case("ko-KR", OcrEngineKind.ML_KIT_KOREAN, null),
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
                OcrEngineKind.ML_KIT_KOREAN,
                PaddleModelVersion.V6_TINY,
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
    fun offlineUsage_mapsToLanguageAppropriateOcrAndTranslator() {
        data class Case(
            val sourceLang: String,
            val targetLang: String,
            val usage: OnboardingUsage,
            val expectedTranslator: TranslatorEngine,
            val expectedOcr: OcrEngineKind,
        )
        val cases = listOf(
            Case(
                "ja", "zh-CN", OnboardingUsage.DAILY,
                TranslatorEngine.GOOGLE_ML_KIT, OcrEngineKind.ML_KIT_JAPANESE,
            ),
            Case(
                "ja", "zh-CN", OnboardingUsage.MANGA,
                TranslatorEngine.LOCAL_SAKURA, OcrEngineKind.MANGA_OCR_JA,
            ),
            Case(
                "ko", "zh-CN", OnboardingUsage.MANGA,
                TranslatorEngine.LOCAL_HY_MT2, OcrEngineKind.ML_KIT_KOREAN,
            ),
            Case(
                "fr", "zh-CN", OnboardingUsage.MANGA,
                TranslatorEngine.LOCAL_HY_MT2, OcrEngineKind.PADDLE_ONNX,
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
            )
            assertEquals(case.sourceLang, case.expectedTranslator, actual.translatorEngine)
            assertEquals(case.sourceLang, case.expectedOcr, actual.ocrEngine)
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
            val sakuraReady: Boolean,
            val includeSakura: Boolean,
            val expected: List<ModelDownloadSpec>,
        )
        val paddle = ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)
        val mangaOcr = ModelDownloadSpec.mangaOcr()
        val sakura = ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4)
        val cases = listOf(
            Case(false, false, false, true, listOf(paddle, mangaOcr, sakura)),
            Case(true, false, false, true, listOf(mangaOcr, sakura)),
            Case(false, true, true, true, listOf(paddle)),
            Case(true, true, false, true, listOf(sakura)),
            Case(true, true, true, true, emptyList()),
            Case(false, false, false, false, listOf(paddle, mangaOcr)),
            Case(true, true, false, false, emptyList()),
        )

        cases.forEach { case ->
            val readiness = MangaOfflineModelReadiness(
                paddleReady = case.paddleReady,
                mangaOcrReady = case.mangaOcrReady,
                sakuraReady = case.sakuraReady,
                includeSakura = case.includeSakura,
            )
            val caseName = "paddle=${case.paddleReady}/manga=${case.mangaOcrReady}/" +
                "sakura=${case.sakuraReady}/include=${case.includeSakura}"
            assertEquals(
                caseName,
                case.expected,
                mangaOfflineDownloadSpecs(readiness),
            )
            assertEquals(caseName, case.expected.isEmpty(), readiness.allReady)
        }
    }

    @Test
    fun recommendedModelDownloads_includeOnlyMissingOcrAndTranslationModels() {
        data class Case(
            val paddleVersion: PaddleModelVersion?,
            val paddleReady: Boolean,
            val includeHyMt2: Boolean,
            val hyMt2Ready: Boolean,
            val expected: List<ModelDownloadSpec>,
            val expectedRequiredReady: Boolean,
        )
        val paddle = ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)
        val hyMt2 = ModelDownloadSpec.llm(LlmModelKind.HY_MT2_1_8B_Q4_K_M)
        val cases = listOf(
            Case(PaddleModelVersion.V6_SMALL, false, true, false, listOf(paddle, hyMt2), false),
            Case(PaddleModelVersion.V6_SMALL, true, true, false, listOf(hyMt2), false),
            Case(PaddleModelVersion.V6_SMALL, false, true, true, listOf(paddle), true),
            Case(PaddleModelVersion.V6_SMALL, true, true, true, emptyList(), true),
            Case(null, true, true, false, listOf(hyMt2), false),
            Case(PaddleModelVersion.V6_SMALL, false, false, true, listOf(paddle), true),
            Case(null, true, false, true, emptyList(), true),
        )

        cases.forEach { case ->
            val readiness = RecommendedModelsReadiness(
                paddleVersion = case.paddleVersion,
                paddleReady = case.paddleReady,
                includeHyMt2 = case.includeHyMt2,
                hyMt2Ready = case.hyMt2Ready,
            )
            val caseName = "paddle=${case.paddleVersion}/${case.paddleReady}," +
                "hyMt2=${case.includeHyMt2}/${case.hyMt2Ready}"
            assertEquals(
                caseName,
                case.expected,
                recommendedModelsDownloadSpecs(readiness),
            )
            assertEquals(caseName, case.expected.isEmpty(), readiness.allReady)
            assertEquals(caseName, case.expectedRequiredReady, readiness.requiredModelsReady)
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
