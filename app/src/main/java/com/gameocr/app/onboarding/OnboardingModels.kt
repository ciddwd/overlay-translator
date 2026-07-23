package com.gameocr.app.onboarding

import com.gameocr.app.data.Languages
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
import com.gameocr.app.translate.MlKitLanguagePolicy
import java.net.URI

enum class OnboardingStep {
    SOURCE_LANGUAGE,
    TARGET_LANGUAGE,
    DISPLAY_MODE,
    USAGE,
    MANGA_DIRECTION,
    TRANSLATION_METHOD,
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

enum class CloudProvider(
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val protocol: CloudApiProtocol = CloudApiProtocol.OPENAI,
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1/",
        model = "deepseek-v4-flash",
    ),
    KIMI(
        displayName = "Kimi",
        baseUrl = "https://api.moonshot.cn/v1/",
        model = "kimi-k3",
    ),
    MINIMAX(
        displayName = "MiniMax",
        baseUrl = "https://api.minimaxi.com/v1/",
        model = "MiniMax-M3",
    ),
    GLM(
        displayName = "GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4/",
        model = "glm-5.2",
    ),
    MIMO(
        displayName = "MiMo",
        baseUrl = "https://api.xiaomimimo.com/v1/",
        model = "mimo-v2.5-pro",
    ),
    OPENAI(
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/",
        model = "gpt-4.1-mini",
    ),
    CLAUDE(
        displayName = "Claude",
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-4-5",
        protocol = CloudApiProtocol.ANTHROPIC,
    ),
    GEMINI(
        displayName = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        model = "gemini-3.6-flash",
    ),
    CUSTOM(
        displayName = "Custom",
        baseUrl = "",
        model = "",
    ),
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
    val cloudModel: String = CloudProvider.DEEPSEEK.model,
    val ttsChoice: OnboardingTtsChoice = OnboardingTtsChoice.DISABLED,
)

object OnboardingPolicy {
    fun stepsFor(draft: OnboardingDraft): List<OnboardingStep> = buildList {
        add(OnboardingStep.SOURCE_LANGUAGE)
        add(OnboardingStep.TARGET_LANGUAGE)
        add(OnboardingStep.DISPLAY_MODE)
        add(OnboardingStep.USAGE)
        if (draft.usage == OnboardingUsage.MANGA) {
            add(OnboardingStep.MANGA_DIRECTION)
        }
        add(OnboardingStep.TRANSLATION_METHOD)
        add(
            when (draft.translationMethod) {
                OnboardingTranslationMethod.OFFLINE ->
                    if (draft.usage == OnboardingUsage.MANGA) {
                        OnboardingStep.MANGA_OFFLINE_DOWNLOAD
                    } else {
                        OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD
                    }
                OnboardingTranslationMethod.CLOUD_LLM -> OnboardingStep.CLOUD_CONFIG
            }
        )
        add(OnboardingStep.TTS)
        add(OnboardingStep.SUMMARY)
    }

    fun isMlKitPairSupported(sourceLang: String, targetLang: String): Boolean =
        MlKitLanguagePolicy.isSupportedLanguageTag(sourceLang) &&
            MlKitLanguagePolicy.isSupportedLanguageTag(targetLang)

    fun isSakuraPairSupported(sourceLang: String, targetLang: String): Boolean =
        sourceLang == "ja" && targetLang == "zh-CN"

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
    ): OnboardingDraft = draft.copy(
        cloudProvider = provider,
        cloudBaseUrl = provider.baseUrl,
        cloudModel = provider.model,
        cloudApiKey = if (provider == draft.cloudProvider) draft.cloudApiKey else "",
    )

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
                normalizedBaseUrl(it.baseUrl) == normalizedBaseUrl(baseUrl)
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

    fun apply(settings: Settings, draft: OnboardingDraft): Settings {
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
            translatorEngine = when (draft.translationMethod) {
                OnboardingTranslationMethod.OFFLINE ->
                    if (draft.usage == OnboardingUsage.MANGA) {
                        TranslatorEngine.LOCAL_SAKURA
                    } else {
                        TranslatorEngine.GOOGLE_ML_KIT
                    }
                OnboardingTranslationMethod.CLOUD_LLM ->
                    if (draft.cloudProvider.protocol == CloudApiProtocol.ANTHROPIC) {
                        TranslatorEngine.ANTHROPIC
                    } else {
                        TranslatorEngine.OPENAI
                    }
            },
            ocrEngine = when {
                draft.usage == OnboardingUsage.MANGA &&
                    draft.translationMethod == OnboardingTranslationMethod.OFFLINE ->
                    OcrEngineKind.MANGA_OCR_JA
                draft.usage == OnboardingUsage.DAILY &&
                    settings.ocrEngine == OcrEngineKind.MANGA_OCR_JA ->
                    OcrEngineKind.ML_KIT_AUTO
                else -> settings.ocrEngine
            },
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
                mergeAdjacentBlocks = true,
                mergeStrength = MergeStrength.STANDARD,
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

    private fun normalizedBaseUrl(value: String): String = value.trim().trimEnd('/').lowercase()

    private fun ensureTrailingSlash(value: String): String =
        if (value.endsWith('/')) value else "$value/"
}

enum class CloudConfigError {
    BASE_URL_REQUIRED,
    BASE_URL_INVALID,
    API_KEY_REQUIRED,
    MODEL_REQUIRED,
}
