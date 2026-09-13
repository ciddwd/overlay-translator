package com.gameocr.app.data

import com.gameocr.app.ocr.OcrLanguageCapability
import kotlinx.serialization.Serializable
import java.util.Locale

/** Only routing choices, never credentials or a second copy of engine configuration. */
@Serializable
data class AutoOcrRoute(
    val engine: OcrEngineKind,
    val paddleVersion: PaddleModelVersion = PaddleModelVersion.V6_SMALL,
)

@Serializable
data class AutoOcrSettings(
    val routes: Map<String, AutoOcrRoute> = emptyMap(),
    /** Explicitly added rows survive saving even before an OCR override has been selected. */
    val additionalLanguages: List<String> = emptyList(),
) {
    fun normalized() = copy(
        routes = routes.entries
            .filter { (code, route) -> code.isNotBlank() && languageKey(code) != "auto" &&
                route.engine != OcrEngineKind.ML_KIT_AUTO }
            .associate { (code, route) -> languageKey(code) to route }
            .toSortedMap(),
        additionalLanguages = additionalLanguages.map(::languageKey)
            .filter { it.isNotBlank() && it != "auto" && it != "und" }
            .distinct().sorted(),
    )

    fun routeFor(language: String): AutoOcrRoute? =
        routes[languageKey(language)] ?: defaultRoute(language)

    companion object {
        fun languageKey(code: String): String = code.trim().replace('_', '-')
            .lowercase(Locale.ROOT).substringBefore('-').let {
                when (it) { "iw" -> "he"; "in" -> "id"; "nb" -> "no"; else -> it }
            }

        fun defaultRoute(language: String): AutoOcrRoute? {
            val code = languageKey(language)
            if (code.isBlank() || code == "auto" || code == "und") return null
            val engine = when (code) {
                "zh" -> OcrEngineKind.ML_KIT_CHINESE
                "ja" -> OcrEngineKind.ML_KIT_JAPANESE
                "ko" -> OcrEngineKind.ML_KIT_KOREAN
                else -> OcrEngineKind.ML_KIT_LATIN.takeIf {
                    OcrLanguageCapability.supports(it, code)
                }
            }
            if (engine != null) return AutoOcrRoute(engine)
            return AutoOcrRoute(OcrEngineKind.PADDLE_ONNX).takeIf {
                OcrLanguageCapability.supports(it.engine, code, paddleModelVersion = it.paddleVersion)
            }
        }
    }
}

/** The capability table is shared by the dialog, validation, and runtime dispatch. */
object AutoOcrRoutingPolicy {
    val candidates: List<AutoOcrRoute> = OcrEngineCatalog.automaticRoutes()

    fun settingsFor(settings: Settings, language: String, route: AutoOcrRoute): Settings {
        val code = AutoOcrSettings.languageKey(language)
        return settings.copy(
            sourceLang = if (route.engine == OcrEngineKind.TENCENT && code == "no") "nb" else code,
            ocrEngine = route.engine,
            paddleModelVersion = if (route.engine == OcrEngineKind.MANGA_OCR_JA) {
                PaddleModelVersion.V6_SMALL
            } else route.paddleVersion,
            baiduOcrLanguage = if (code == "zh") BaiduOcrLanguage.CHN_ENG else BaiduOcrLanguage.entries.firstOrNull {
                it.bcp47 == code && it.supportedOn(settings.baiduOcrEndpoint)
            } ?: settings.baiduOcrLanguage,
            tencentOcrLanguage = if (code == "zh" || code == "en") TencentOcrLanguage.AUTO else TencentOcrLanguage.entries.firstOrNull {
                it.bcp47?.let(AutoOcrSettings::languageKey) == code && it.supportedOn(settings.tencentOcrEndpoint)
            } ?: settings.tencentOcrLanguage,
        )
    }

    fun supports(settings: Settings, language: String, route: AutoOcrRoute): Boolean {
        if (route.engine == OcrEngineKind.ML_KIT_AUTO) return false
        val effective = settingsFor(settings, language, route)
        return OcrLanguageCapability.supports(effective, effective.sourceLang)
    }

    fun options(settings: Settings, language: String): List<AutoOcrRoute> =
        candidates.filter { supports(settings, language, it) }

    fun configured(settings: Settings, route: AutoOcrRoute): Boolean = when (route.engine) {
        OcrEngineKind.BAIDU -> settings.baiduOcrApiKey.isNotBlank() && settings.baiduOcrSecretKey.isNotBlank()
        OcrEngineKind.TENCENT -> settings.tencentSecretId.isNotBlank() && settings.tencentSecretKey.isNotBlank()
        OcrEngineKind.YOUDAO -> settings.youdaoAppKey.isNotBlank() && settings.youdaoAppSecret.isNotBlank()
        OcrEngineKind.PADDLE_AI_STUDIO -> settings.paddleAiStudioToken.isNotBlank()
        OcrEngineKind.UMI_OCR -> settings.umiOcrBaseUrl.isNotBlank()
        OcrEngineKind.LUNA_OCR -> settings.lunaOcrBaseUrl.isNotBlank()
        OcrEngineKind.ML_KIT_AUTO -> false
        else -> true
    }

    /** Never invent a cloud fallback. An unavailable override falls back to a built-in local route. */
    fun resolve(settings: Settings, language: String, ready: (AutoOcrRoute) -> Boolean): AutoOcrRoute? {
        val selected = settings.autoOcr.routeFor(language)
        if (selected != null && supports(settings, language, selected) &&
            configured(settings, selected) && ready(selected)) return selected
        return AutoOcrSettings.defaultRoute(language)?.takeIf { ready(it) }
    }
}
