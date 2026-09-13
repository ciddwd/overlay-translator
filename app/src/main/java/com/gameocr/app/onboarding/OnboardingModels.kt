package com.gameocr.app.onboarding

import com.gameocr.app.data.Languages
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
import com.gameocr.app.ocr.OcrLanguageCapability
import com.gameocr.app.translate.MlKitLanguagePolicy
import java.net.URI
import java.util.Locale

enum class OnboardingStep {
    WELCOME,
    SOURCE_LANGUAGE,
    TARGET_LANGUAGE,
    DISPLAY_MODE,
    USAGE,
    MANGA_DIRECTION,
    TRANSLATION_METHOD,
    RECOMMENDED_MODELS_DOWNLOAD,
    OFFLINE_LANGUAGE_DOWNLOAD,
    MANGA_OFFLINE_DOWNLOAD,
    CLOUD_CONFIG,
    TTS,
    SUMMARY,
}

enum class OnboardingUsage {
    DAILY,
    MANGA,
}

enum class OnboardingDisplayMode {
    ADAPTIVE_OVERLAY,
    BELOW_SOURCE,
    FLOATING_WINDOW,
}

enum class OnboardingMangaDirection {
    FOLLOW_RECOGNITION,
    HORIZONTAL_LEFT_TO_RIGHT,
    VERTICAL_RIGHT_TO_LEFT,
}

enum class OnboardingTranslationMethod {
    OFFLINE,
    CLOUD_LLM,
}

enum class OnboardingTtsChoice(val provider: TtsProvider?) {
    DISABLED(null),
    SYSTEM(TtsProvider.SYSTEM),
    GENERIC_HTTP(TtsProvider.GENERIC_HTTP),
    VOLCENGINE(TtsProvider.VOLCENGINE),
    MINIMAX(TtsProvider.MINIMAX),
    MIMO(TtsProvider.MIMO),
}

enum class CloudApiProtocol {
    OPENAI,
    ANTHROPIC,
}

enum class CloudApiRegion {
    MAINLAND_CHINA,
    INTERNATIONAL,
}

enum class CloudProvider(
    val displayName: String,
    val baseUrl: String,
    val protocol: CloudApiProtocol = CloudApiProtocol.OPENAI,
    val internationalBaseUrl: String? = null,
    val sortName: String = displayName,
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1/",
    ),
    KIMI(
        displayName = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1/",
        internationalBaseUrl = "https://api.moonshot.ai/v1/",
    ),
    MINIMAX(
        displayName = "MiniMax",
        baseUrl = "https://api.minimaxi.com/v1/",
        internationalBaseUrl = "https://api.minimax.io/v1/",
    ),
    GLM(
        displayName = "GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
        internationalBaseUrl = "https://api.z.ai/api/paas/v4/",
    ),
    MIMO(
        displayName = "MiMo",
        baseUrl = "https://api.xiaomimimo.com/v1/",
    ),
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/",
    ),
    CLAUDE(
        displayName = "Claude",
        baseUrl = "https://api.anthropic.com",
        protocol = CloudApiProtocol.ANTHROPIC,
    ),
    GEMINI(
        displayName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
    ),
    // Provider entries contain connection information, never a preselected model.
    MODELSCOPE(
        displayName = "魔搭 ModelScope",
        baseUrl = "https://api-inference.modelscope.cn/v1/",
        sortName = "ModelScope",
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
    ),
    AIHUBMIX(
        displayName = "AIHubMix",
        baseUrl = "https://aihubmix.com/v1/",
    ),
    AI_302(
        displayName = "302.AI",
        baseUrl = "https://api.302ai.cn/v1/",
        internationalBaseUrl = "https://api.302.ai/v1/",
    ),
    SCNET(
        displayName = "国家超算互联网（SCNet）",
        baseUrl = "https://api.scnet.cn/api/llm/v1/",
        sortName = "SCNet",
    ),
    CUSTOM(
        displayName = "Custom",
        baseUrl = "",
    );

    val supportsRegions: Boolean get() = internationalBaseUrl != null

    fun baseUrlFor(region: CloudApiRegion): String? = when {
        !supportsRegions -> null
        region == CloudApiRegion.MAINLAND_CHINA -> baseUrl
        else -> internationalBaseUrl
    }

    companion object {
        // Presentation order only: keep enum identities independent from the UI's ordering.
        val sortedChoices: List<CloudProvider> = entries.sortedWith(
            compareBy<CloudProvider> { it == CUSTOM }
                .thenBy { it.sortName.lowercase(Locale.ROOT) },
        )
    }
}

data class OnboardingDraft(
    val sourceLang: String = "ja",
    val targetLang: String = "zh-CN",
    val displayMode: OnboardingDisplayMode = OnboardingDisplayMode.ADAPTIVE_OVERLAY,
    val usage: OnboardingUsage = OnboardingUsage.DAILY,
    val mangaDirection: OnboardingMangaDirection =
        OnboardingMangaDirection.FOLLOW_RECOGNITION,
    val translationMethod: OnboardingTranslationMethod = OnboardingTranslationMethod.OFFLINE,
    val cloudProvider: CloudProvider = CloudProvider.DEEPSEEK,
    val cloudBaseUrl: String = CloudProvider.DEEPSEEK.baseUrl,
    val cloudApiKey: String = "",
    val cloudModel: String = "",
    val ttsChoice: OnboardingTtsChoice = OnboardingTtsChoice.DISABLED,
)

object OnboardingPolicy {
    fun stepsFor(
        draft: OnboardingDraft,
        localLlmSupported: Boolean = true,
        mangaOcrReady: Boolean = false,
    ): List<OnboardingStep> = buildList {
        add(OnboardingStep.WELCOME)
        add(OnboardingStep.SOURCE_LANGUAGE)
        add(OnboardingStep.TARGET_LANGUAGE)
        add(OnboardingStep.USAGE)
        if (draft.usage == OnboardingUsage.MANGA) {
            add(OnboardingStep.MANGA_DIRECTION)
        } else {
            add(OnboardingStep.DISPLAY_MODE)
        }
        add(OnboardingStep.TRANSLATION_METHOD)
        val usesJapaneseMangaOcr = usesJapaneseMangaOcr(draft)
        if (usesJapaneseMangaOcr) {
            if (!mangaOcrReady || draft.translationMethod != OnboardingTranslationMethod.CLOUD_LLM) {
                add(OnboardingStep.MANGA_OFFLINE_DOWNLOAD)
            }
        } else if (needsRecommendedModelsDownload(draft, localLlmSupported)) {
            add(OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD)
        }
        when (draft.translationMethod) {
            OnboardingTranslationMethod.OFFLINE -> when (
                offlineTranslatorEngine(draft, localLlmSupported)
            ) {
                TranslatorEngine.LOCAL_SAKURA,
                TranslatorEngine.LOCAL_HY_MT2 -> Unit // Included with the model page above.
                else -> add(OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD)
            }
            OnboardingTranslationMethod.CLOUD_LLM -> add(OnboardingStep.CLOUD_CONFIG)
        }
        add(OnboardingStep.TTS)
        add(OnboardingStep.SUMMARY)
    }

    /** Resolve against step identities, including when a completed preparation page disappears. */
    internal fun adjacentStepIndex(
        draft: OnboardingDraft,
        localLlmSupported: Boolean,
        mangaOcrReady: Boolean,
        currentStep: OnboardingStep,
        forward: Boolean,
    ): Int {
        val allSteps = stepsFor(draft, localLlmSupported)
        val visibleSteps = stepsFor(draft, localLlmSupported, mangaOcrReady)
        val currentIndex = allSteps.indexOf(currentStep)
        require(currentIndex >= 0)
        val candidates = if (forward) {
            allSteps.drop(currentIndex + 1)
        } else {
            allSteps.take(currentIndex).asReversed()
        }
        val target = candidates.firstOrNull { it in visibleSteps }
            ?: if (forward) visibleSteps.last() else visibleSteps.first()
        return visibleSteps.indexOf(target)
    }

    fun isMlKitPairSupported(sourceLang: String, targetLang: String): Boolean =
        MlKitLanguagePolicy.isSupportedLanguageTag(sourceLang) &&
            MlKitLanguagePolicy.isSupportedLanguageTag(targetLang)

    fun isSakuraPairSupported(sourceLang: String, targetLang: String): Boolean =
        sourceLang == "ja" && targetLang == "zh-CN"

    /** Sakura is only a translation choice. OCR routing is decided independently below. */
    fun usesSakuraMangaTranslation(draft: OnboardingDraft): Boolean =
        draft.usage == OnboardingUsage.MANGA &&
            draft.translationMethod == OnboardingTranslationMethod.OFFLINE &&
            isSakuraPairSupported(draft.sourceLang, draft.targetLang)

    fun usesJapaneseMangaOcr(draft: OnboardingDraft): Boolean =
        draft.usage == OnboardingUsage.MANGA &&
            isSakuraPairSupported(draft.sourceLang, draft.targetLang)

    fun usesHyMt2MangaTranslation(draft: OnboardingDraft): Boolean =
        draft.usage == OnboardingUsage.MANGA &&
            draft.translationMethod == OnboardingTranslationMethod.OFFLINE &&
            !usesSakuraMangaTranslation(draft)

    fun offlineTranslatorEngine(
        draft: OnboardingDraft,
        localLlmSupported: Boolean,
    ): TranslatorEngine = when {
        draft.usage != OnboardingUsage.MANGA || !localLlmSupported ->
            TranslatorEngine.GOOGLE_ML_KIT
        usesSakuraMangaTranslation(draft) -> TranslatorEngine.LOCAL_SAKURA
        else -> TranslatorEngine.LOCAL_HY_MT2
    }

    fun canUseOfflineTranslation(
        draft: OnboardingDraft,
        localLlmSupported: Boolean,
    ): Boolean = when (offlineTranslatorEngine(draft, localLlmSupported)) {
        TranslatorEngine.GOOGLE_ML_KIT ->
            isMlKitPairSupported(draft.sourceLang, draft.targetLang)
        else -> true
    }

    private fun normalizedSourceLanguage(sourceLang: String): String =
        sourceLang.trim().replace('_', '-')

    private fun isJapaneseSourceLanguage(sourceLang: String): Boolean =
        normalizedSourceLanguage(sourceLang).substringBefore('-').equals("ja", ignoreCase = true)

    private fun mlKitOcrEngineForSourceLanguage(sourceLang: String): OcrEngineKind? {
        val normalized = normalizedSourceLanguage(sourceLang)
        val candidate = when (normalized.substringBefore('-').lowercase()) {
            "ja" -> OcrEngineKind.ML_KIT_JAPANESE
            "ko" -> OcrEngineKind.ML_KIT_KOREAN
            "zh" -> OcrEngineKind.ML_KIT_CHINESE
            else -> OcrEngineKind.ML_KIT_LATIN
        }
        return candidate.takeIf {
            OcrLanguageCapability.supports(it, normalized)
        }
    }

    private fun supportsPaddleV6Small(sourceLang: String): Boolean =
        OcrLanguageCapability.supports(
            engine = OcrEngineKind.PADDLE_ONNX,
            sourceCode = normalizedSourceLanguage(sourceLang),
            paddleModelVersion = PaddleModelVersion.V6_SMALL,
        )

    private fun supportsPaddleV5Korean(sourceLang: String): Boolean =
        normalizedSourceLanguage(sourceLang).substringBefore('-').equals("ko", ignoreCase = true) &&
            OcrLanguageCapability.supports(
                engine = OcrEngineKind.PADDLE_ONNX,
                sourceCode = normalizedSourceLanguage(sourceLang),
                paddleModelVersion = PaddleModelVersion.V5_KOREAN,
            )

    /**
     * Everyday use prioritizes ML Kit when it has a recognizer for the source language.
     * If it does not, fall back to PaddleOCR v6 Small.
     */
    fun ocrEngineForSourceLanguage(sourceLang: String): OcrEngineKind =
        mlKitOcrEngineForSourceLanguage(sourceLang)
            ?: OcrEngineKind.PADDLE_ONNX

    fun recommendedOcrEngine(draft: OnboardingDraft): OcrEngineKind =
        when (draft.usage) {
            OnboardingUsage.DAILY -> ocrEngineForSourceLanguage(draft.sourceLang)
            OnboardingUsage.MANGA -> when {
                usesJapaneseMangaOcr(draft) -> OcrEngineKind.MANGA_OCR_JA
                supportsPaddleV5Korean(draft.sourceLang) -> OcrEngineKind.PADDLE_ONNX
                supportsPaddleV6Small(draft.sourceLang) -> OcrEngineKind.PADDLE_ONNX
                else -> mlKitOcrEngineForSourceLanguage(draft.sourceLang)
                    ?: OcrEngineKind.PADDLE_ONNX
            }
        }

    fun shouldRecommendPaddleOcr(draft: OnboardingDraft): Boolean =
        recommendedOcrEngine(draft) == OcrEngineKind.PADDLE_ONNX

    fun needsRecommendedModelsDownload(
        draft: OnboardingDraft,
        localLlmSupported: Boolean = true,
    ): Boolean =
        shouldRecommendPaddleOcr(draft) ||
            (localLlmSupported && usesHyMt2MangaTranslation(draft))

    fun recommendedPaddleModelVersion(draft: OnboardingDraft): PaddleModelVersion? =
        when {
            recommendedOcrEngine(draft) == OcrEngineKind.MANGA_OCR_JA ->
                PaddleModelVersion.V6_SMALL
            recommendedOcrEngine(draft) == OcrEngineKind.PADDLE_ONNX &&
                supportsPaddleV5Korean(draft.sourceLang) ->
                PaddleModelVersion.V5_KOREAN
            recommendedOcrEngine(draft) == OcrEngineKind.PADDLE_ONNX ->
                PaddleModelVersion.V6_SMALL
            else -> null
        }

    fun cloudConfigError(draft: OnboardingDraft): CloudConfigError? {
        if (draft.cloudBaseUrl.isBlank()) return CloudConfigError.BASE_URL_REQUIRED
        val uri = runCatching { URI(draft.cloudBaseUrl.trim()) }.getOrNull()
        if (
            uri == null ||
            uri.host.isNullOrBlank() ||
            uri.scheme?.lowercase() !in setOf("http", "https")
        ) {
            return CloudConfigError.BASE_URL_INVALID
        }
        if (draft.cloudApiKey.isBlank()) return CloudConfigError.API_KEY_REQUIRED
        if (draft.cloudModel.isBlank()) return CloudConfigError.MODEL_REQUIRED
        return null
    }

    fun selectCloudProvider(
        draft: OnboardingDraft,
        provider: CloudProvider,
    ): OnboardingDraft = if (provider == draft.cloudProvider) draft else draft.copy(
        cloudProvider = provider,
        cloudBaseUrl = provider.baseUrl,
        cloudModel = "",
        cloudApiKey = "",
    )

    fun cloudApiRegion(draft: OnboardingDraft): CloudApiRegion? {
        if (!draft.cloudProvider.supportsRegions) return null
        val current = normalizedBaseUrl(draft.cloudBaseUrl) ?: return null
        return CloudApiRegion.entries.firstOrNull {
            normalizedBaseUrl(draft.cloudProvider.baseUrlFor(it).orEmpty()) == current
        }
    }

    fun selectCloudApiRegion(draft: OnboardingDraft, region: CloudApiRegion): OnboardingDraft {
        val baseUrl = draft.cloudProvider.baseUrlFor(region) ?: return draft
        if (cloudApiRegion(draft) == region) return draft
        return draft.copy(
            cloudBaseUrl = baseUrl,
            // Regional accounts can use different credentials. Only clear this unsaved form field.
            cloudApiKey = "",
        )
    }

    fun fromSettings(settings: Settings): OnboardingDraft {
        val protocol = if (settings.translatorEngine == TranslatorEngine.ANTHROPIC) {
            CloudApiProtocol.ANTHROPIC
        } else {
            CloudApiProtocol.OPENAI
        }
        val baseUrl = if (protocol == CloudApiProtocol.ANTHROPIC) {
            settings.anthropicBaseUrl
        } else {
            settings.baseUrl
        }
        val model = if (protocol == CloudApiProtocol.ANTHROPIC) {
            settings.anthropicModel
        } else {
            settings.model
        }
        val apiKey = if (protocol == CloudApiProtocol.ANTHROPIC) {
            settings.anthropicApiKey
        } else {
            settings.apiKey
        }
        val provider = CloudProvider.entries.firstOrNull {
            it != CloudProvider.CUSTOM &&
                it.protocol == protocol &&
                listOfNotNull(it.baseUrl, it.internationalBaseUrl).any { candidate ->
                    normalizedBaseUrl(candidate) == normalizedBaseUrl(baseUrl)
                }
        } ?: CloudProvider.CUSTOM
        return OnboardingDraft(
            sourceLang = settings.sourceLang.takeUnless { it == Languages.AUTO.code } ?: "ja",
            targetLang = settings.targetLang.takeUnless { it == Languages.AUTO.code } ?: "zh-CN",
            displayMode = when {
                settings.renderMode == RenderMode.FLOATING_WINDOW ->
                    OnboardingDisplayMode.FLOATING_WINDOW
                settings.overlayStyleMode == OverlayStyleMode.ADAPTIVE ->
                    OnboardingDisplayMode.ADAPTIVE_OVERLAY
                else -> OnboardingDisplayMode.BELOW_SOURCE
            },
            usage = if (
                settings.ocrEngine == OcrEngineKind.MANGA_OCR_JA ||
                settings.translatorEngine == TranslatorEngine.LOCAL_SAKURA ||
                (
                    settings.overlayStyleMode == OverlayStyleMode.ADAPTIVE &&
                        settings.mergeAdjacentBlocks &&
                        settings.mergeStrength == MergeStrength.STANDARD
                    )
            ) {
                OnboardingUsage.MANGA
            } else {
                OnboardingUsage.DAILY
            },
            mangaDirection = when {
                settings.translationOutputFollowRecognition ->
                    OnboardingMangaDirection.FOLLOW_RECOGNITION
                settings.translationOutputLayout == TranslationOutputLayout.VERTICAL &&
                    settings.translationOutputDirection ==
                    TranslationOutputDirection.RIGHT_TO_LEFT ->
                    OnboardingMangaDirection.VERTICAL_RIGHT_TO_LEFT
                else -> OnboardingMangaDirection.HORIZONTAL_LEFT_TO_RIGHT
            },
            translationMethod = if (
                settings.translatorEngine in setOf(
                    TranslatorEngine.GOOGLE_ML_KIT,
                    TranslatorEngine.LOCAL_SAKURA,
                    TranslatorEngine.LOCAL_HY_MT2,
                )
            ) {
                OnboardingTranslationMethod.OFFLINE
            } else {
                OnboardingTranslationMethod.CLOUD_LLM
            },
            cloudProvider = provider,
            cloudBaseUrl = baseUrl,
            cloudApiKey = apiKey,
            cloudModel = model,
            ttsChoice = if (!settings.ttsEnabled) {
                OnboardingTtsChoice.DISABLED
            } else {
                OnboardingTtsChoice.entries.firstOrNull {
                    it.provider == settings.ttsProvider
                } ?: OnboardingTtsChoice.SYSTEM
            },
        )
    }

    fun apply(
        settings: Settings,
        draft: OnboardingDraft,
        localLlmSupported: Boolean = true,
    ): Settings {
        val displaySettings = when (draft.displayMode) {
            OnboardingDisplayMode.ADAPTIVE_OVERLAY -> Triple(
                RenderMode.BLOCKS,
                OverlayStyleMode.ADAPTIVE,
                OverlayPlacement.OVERLAP,
            )
            OnboardingDisplayMode.BELOW_SOURCE -> Triple(
                RenderMode.BLOCKS,
                OverlayStyleMode.FIXED,
                OverlayPlacement.BELOW,
            )
            OnboardingDisplayMode.FLOATING_WINDOW -> Triple(
                RenderMode.FLOATING_WINDOW,
                OverlayStyleMode.FIXED,
                settings.overlayPlacement,
            )
        }
        var next = settings.copy(
            sourceLang = draft.sourceLang,
            targetLang = draft.targetLang,
            renderMode = displaySettings.first,
            overlayStyleMode = displaySettings.second,
            overlayPlacement = displaySettings.third,
            translationContextMode = when (draft.translationMethod) {
                OnboardingTranslationMethod.CLOUD_LLM -> TranslationContextMode.PAGE_CONTEXT
                OnboardingTranslationMethod.OFFLINE -> TranslationContextMode.FAST_PER_SEGMENT
            },
            translatorEngine = when (draft.translationMethod) {
                OnboardingTranslationMethod.OFFLINE ->
                    offlineTranslatorEngine(draft, localLlmSupported)
                OnboardingTranslationMethod.CLOUD_LLM ->
                    if (draft.cloudProvider.protocol == CloudApiProtocol.ANTHROPIC) {
                        TranslatorEngine.ANTHROPIC
                    } else {
                        TranslatorEngine.OPENAI
                    }
            },
            ocrEngine = recommendedOcrEngine(draft),
            paddleModelVersion = recommendedPaddleModelVersion(draft)
                ?: settings.paddleModelVersion,
            ttsEnabled = draft.ttsChoice != OnboardingTtsChoice.DISABLED,
            ttsProvider = draft.ttsChoice.provider ?: settings.ttsProvider,
        )
        if (draft.translationMethod == OnboardingTranslationMethod.CLOUD_LLM) {
            next = if (draft.cloudProvider.protocol == CloudApiProtocol.ANTHROPIC) {
                next.copy(
                    anthropicBaseUrl = draft.cloudBaseUrl.trim(),
                    anthropicApiKey = draft.cloudApiKey.trim(),
                    anthropicModel = draft.cloudModel.trim(),
                )
            } else {
                next.copy(
                    baseUrl = ensureTrailingSlash(draft.cloudBaseUrl.trim()),
                    apiKey = draft.cloudApiKey.trim(),
                    model = draft.cloudModel.trim(),
                )
            }
        }
        if (draft.usage == OnboardingUsage.MANGA) {
            val mergeNonJapaneseText = !isJapaneseSourceLanguage(draft.sourceLang)
            val output = when (draft.mangaDirection) {
                OnboardingMangaDirection.FOLLOW_RECOGNITION -> Triple(
                    true,
                    TranslationOutputLayout.FOLLOW_RECOGNITION,
                    TranslationOutputDirection.FOLLOW_RECOGNITION,
                )
                OnboardingMangaDirection.HORIZONTAL_LEFT_TO_RIGHT -> Triple(
                    false,
                    TranslationOutputLayout.HORIZONTAL,
                    TranslationOutputDirection.LEFT_TO_RIGHT,
                )
                OnboardingMangaDirection.VERTICAL_RIGHT_TO_LEFT -> Triple(
                    false,
                    TranslationOutputLayout.VERTICAL,
                    TranslationOutputDirection.RIGHT_TO_LEFT,
                )
            }
            next = next.copy(
                renderMode = RenderMode.BLOCKS,
                overlayStyleMode = OverlayStyleMode.ADAPTIVE,
                overlayPlacement = OverlayPlacement.OVERLAP,
                mergeAdjacentBlocks = mergeNonJapaneseText,
                mergeStrength = if (mergeNonJapaneseText) {
                    MergeStrength.STANDARD
                } else {
                    next.mergeStrength
                },
                translationOutputFollowRecognition = output.first,
                translationOutputLayout = output.second,
                translationOutputDirection = output.third,
            )
        } else {
            next = next.copy(
                mergeAdjacentBlocks = false,
                translationOutputFollowRecognition = true,
                translationOutputLayout = TranslationOutputLayout.FOLLOW_RECOGNITION,
                translationOutputDirection = TranslationOutputDirection.FOLLOW_RECOGNITION,
            )
        }
        return next
    }

    private fun normalizedBaseUrl(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null
        ) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("https", "http")) return null
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) ||
            (scheme == "http" && it == 80) }?.let { ":$it" }.orEmpty()
        // Host names are case-insensitive; API paths are not.
        return "$scheme://${uri.host.lowercase(Locale.ROOT)}$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }

    private fun ensureTrailingSlash(value: String): String =
        if (value.endsWith('/')) value else "$value/"
}

enum class CloudConfigError {
    BASE_URL_REQUIRED,
    BASE_URL_INVALID,
    API_KEY_REQUIRED,
    MODEL_REQUIRED,
}
