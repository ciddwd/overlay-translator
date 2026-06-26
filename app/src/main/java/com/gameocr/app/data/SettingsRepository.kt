package com.gameocr.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("game_ocr_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val ApiKey = stringPreferencesKey("api_key")
        val Model = stringPreferencesKey("model")
        val SourceLang = stringPreferencesKey("source_lang")
        val TargetLang = stringPreferencesKey("target_lang")
        val Prompt = stringPreferencesKey("prompt")
        val OcrEngine = stringPreferencesKey("ocr_engine")
        val LoopInterval = longPreferencesKey("loop_interval_ms")
        val TextSize = intPreferencesKey("overlay_text_size")
        val Alpha = floatPreferencesKey("overlay_alpha")
        val Region = stringPreferencesKey("capture_region_json")
        val Streaming = booleanPreferencesKey("streaming_translate")
        val RenderModeKey = stringPreferencesKey("render_mode")
        val Upscale = booleanPreferencesKey("pre_upscale")
        val Invert = booleanPreferencesKey("pre_invert")
        val Binarize = booleanPreferencesKey("pre_binarize")
        val BaiduKey = stringPreferencesKey("baidu_api_key")
        val BaiduSecret = stringPreferencesKey("baidu_secret_key")
        val A11yVolume = booleanPreferencesKey("a11y_volume_trigger")
        val TencentId = stringPreferencesKey("tencent_secret_id")
        val TencentKey = stringPreferencesKey("tencent_secret_key")
        val TencentRegion = stringPreferencesKey("tencent_region")
        val PreferShizuku = booleanPreferencesKey("prefer_shizuku")
        val Placement = stringPreferencesKey("overlay_placement")
        val PaddleMirror = stringPreferencesKey("paddle_mirror_url")
        val OffsetX = intPreferencesKey("overlay_offset_x")
        val OffsetY = intPreferencesKey("overlay_offset_y")
        val ThemeKey = stringPreferencesKey("overlay_theme")
        val CustomBg = intPreferencesKey("overlay_custom_bg")
        val CustomFg = intPreferencesKey("overlay_custom_fg")
        val CustomBorder = intPreferencesKey("overlay_custom_border")
        val CustomBorderW = intPreferencesKey("overlay_custom_border_w")
        val TranslatorEng = stringPreferencesKey("translator_engine")
        val DeeplKey = stringPreferencesKey("deepl_key")
        val DeeplPro = booleanPreferencesKey("deepl_pro")
        val DeeplProtocol = stringPreferencesKey("deepl_protocol")
        val DeeplBaseUrl = stringPreferencesKey("deepl_base_url")
        val DeeplBearerAuth = booleanPreferencesKey("deepl_bearer_auth")
        val DeeplCustomToken = stringPreferencesKey("deepl_custom_token")
        val FloatingSize = intPreferencesKey("floating_button_size_dp")
        val FloatingX = intPreferencesKey("floating_button_x")
        val FloatingY = intPreferencesKey("floating_button_y")
        val FloatingSnapEdge = booleanPreferencesKey("floating_button_snap_edge")
        val FloatingAutoDock = booleanPreferencesKey("floating_button_auto_dock")
        val FloatingDockInset = intPreferencesKey("floating_button_dock_inset_dp")
        // 收藏的语言代码列表，逗号分隔（"ja,zh-CN,en"）。逗号不可能出现在 BCP-47 tag 里，分隔安全。
        val PinnedLangs = stringPreferencesKey("pinned_languages")
        val OverlayWrap = booleanPreferencesKey("overlay_allow_wrap")
        val OverlayCollision = booleanPreferencesKey("overlay_avoid_collision")
        val BaiduEndpoint = stringPreferencesKey("baidu_ocr_endpoint")
        val BaiduLanguage = stringPreferencesKey("baidu_ocr_language")
        val TencentEndpoint = stringPreferencesKey("tencent_ocr_endpoint")
        val TencentLanguage = stringPreferencesKey("tencent_ocr_language")
        val ApiTimeoutSec = intPreferencesKey("api_timeout_seconds")
        val MergeAdjacent = booleanPreferencesKey("ocr_merge_adjacent")
        val MergeStrengthKey = stringPreferencesKey("ocr_merge_strength")
        val YoudaoAppKey = stringPreferencesKey("youdao_app_key")
        val YoudaoAppSecret = stringPreferencesKey("youdao_app_secret")
        // 明文 HTTP 白名单 host，以 \n 分隔保存（hostname 不含 \n，分隔安全）
        val CleartextHosts = stringPreferencesKey("cleartext_allowed_hosts")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun get(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = transform(current)
            prefs[Keys.BaseUrl] = next.baseUrl
            prefs[Keys.ApiKey] = next.apiKey
            prefs[Keys.Model] = next.model
            prefs[Keys.SourceLang] = next.sourceLang
            prefs[Keys.TargetLang] = next.targetLang
            prefs[Keys.Prompt] = next.promptTemplate
            prefs[Keys.OcrEngine] = next.ocrEngine.name
            prefs[Keys.LoopInterval] = next.captureLoopIntervalMs
            prefs[Keys.TextSize] = next.overlayTextSizeSp
            prefs[Keys.Alpha] = next.overlayAlpha
            prefs[Keys.Region] = next.captureRegion?.let { json.encodeToString(it) } ?: ""
            prefs[Keys.Streaming] = next.streamingTranslate
            prefs[Keys.RenderModeKey] = next.renderMode.name
            prefs[Keys.Upscale] = next.preprocess.upscale2x
            prefs[Keys.Invert] = next.preprocess.invert
            prefs[Keys.Binarize] = next.preprocess.binarize
            prefs[Keys.BaiduKey] = next.baiduOcrApiKey
            prefs[Keys.BaiduSecret] = next.baiduOcrSecretKey
            prefs[Keys.A11yVolume] = next.a11yVolumeTrigger
            prefs[Keys.TencentId] = next.tencentSecretId
            prefs[Keys.TencentKey] = next.tencentSecretKey
            prefs[Keys.TencentRegion] = next.tencentRegion
            prefs[Keys.PreferShizuku] = next.preferShizukuCapture
            prefs[Keys.Placement] = next.overlayPlacement.name
            prefs[Keys.PaddleMirror] = next.paddleModelMirrorUrl
            prefs[Keys.OffsetX] = next.overlayOffsetX
            prefs[Keys.OffsetY] = next.overlayOffsetY
            prefs[Keys.ThemeKey] = next.overlayTheme.name
            prefs[Keys.CustomBg] = next.customBgColor
            prefs[Keys.CustomFg] = next.customFgColor
            prefs[Keys.CustomBorder] = next.customBorderColor
            prefs[Keys.CustomBorderW] = next.customBorderWidth
            prefs[Keys.TranslatorEng] = next.translatorEngine.name
            prefs[Keys.DeeplKey] = next.deeplApiKey
            prefs[Keys.DeeplPro] = next.deeplPro
            prefs[Keys.DeeplProtocol] = next.deeplProtocol.name
            prefs[Keys.DeeplBaseUrl] = next.deeplBaseUrl
            prefs[Keys.DeeplBearerAuth] = next.deeplBearerAuth
            prefs[Keys.DeeplCustomToken] = next.deeplCustomToken
            prefs[Keys.FloatingSize] = next.floatingButtonSizeDp
            prefs[Keys.FloatingX] = next.floatingButtonX
            prefs[Keys.FloatingY] = next.floatingButtonY
            prefs[Keys.FloatingSnapEdge] = next.floatingButtonSnapToEdge
            prefs[Keys.FloatingAutoDock] = next.floatingButtonAutoDock
            prefs[Keys.FloatingDockInset] = next.floatingButtonDockInsetDp
            prefs[Keys.PinnedLangs] = next.pinnedLanguages.joinToString(",")
            prefs[Keys.OverlayWrap] = next.overlayAllowWrap
            prefs[Keys.OverlayCollision] = next.overlayAvoidCollision
            prefs[Keys.BaiduEndpoint] = next.baiduOcrEndpoint.name
            prefs[Keys.BaiduLanguage] = next.baiduOcrLanguage.name
            prefs[Keys.TencentEndpoint] = next.tencentOcrEndpoint.name
            prefs[Keys.TencentLanguage] = next.tencentOcrLanguage.name
            prefs[Keys.ApiTimeoutSec] = next.apiTimeoutSeconds
            prefs[Keys.MergeAdjacent] = next.mergeAdjacentBlocks
            prefs[Keys.MergeStrengthKey] = next.mergeStrength.name
            prefs[Keys.YoudaoAppKey] = next.youdaoAppKey
            prefs[Keys.YoudaoAppSecret] = next.youdaoAppSecret
            prefs[Keys.CleartextHosts] = next.cleartextAllowedHosts.joinToString("\n")
        }
    }

    private fun Preferences.toSettings(): Settings {
        val default = Settings()
        return Settings(
            baseUrl = this[Keys.BaseUrl] ?: default.baseUrl,
            apiKey = this[Keys.ApiKey] ?: default.apiKey,
            model = this[Keys.Model] ?: default.model,
            // 兼容 0.1.x 旧用户：那时 sourceLang 用 enum.name（"AUTO"/"JA"/...）保存。
            // 新版改为 BCP-47 tag（"auto"/"ja"/...）。读出时若是旧大写值，按 mapping 转回。
            sourceLang = (this[Keys.SourceLang] ?: default.sourceLang).let { raw ->
                when (raw) {
                    "AUTO" -> "auto"; "JA" -> "ja"; "ZH" -> "zh-CN"
                    "EN" -> "en"; "KO" -> "ko"
                    else -> raw
                }
            },
            targetLang = this[Keys.TargetLang] ?: default.targetLang,
            // 首次启动（Keys.Prompt 不存在）使用资源里的本地化默认 prompt（中文系统给中文，英文给英文）。
            // 用户保存过自己的 prompt 后这里读到自己的，不会被覆盖。
            promptTemplate = this[Keys.Prompt] ?: context.getString(R.string.default_prompt),
            ocrEngine = runCatching { OcrEngineKind.valueOf(this[Keys.OcrEngine] ?: "") }
                .getOrDefault(default.ocrEngine),
            captureLoopIntervalMs = this[Keys.LoopInterval] ?: default.captureLoopIntervalMs,
            overlayTextSizeSp = this[Keys.TextSize] ?: default.overlayTextSizeSp,
            overlayAlpha = this[Keys.Alpha] ?: default.overlayAlpha,
            captureRegion = this[Keys.Region]?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<CaptureRegion>(it) }.getOrNull()
            },
            streamingTranslate = this[Keys.Streaming] ?: default.streamingTranslate,
            renderMode = runCatching { RenderMode.valueOf(this[Keys.RenderModeKey] ?: "") }
                .getOrDefault(default.renderMode),
            preprocess = PreprocessOptions(
                upscale2x = this[Keys.Upscale] ?: default.preprocess.upscale2x,
                invert = this[Keys.Invert] ?: default.preprocess.invert,
                binarize = this[Keys.Binarize] ?: default.preprocess.binarize
            ),
            baiduOcrApiKey = this[Keys.BaiduKey] ?: default.baiduOcrApiKey,
            baiduOcrSecretKey = this[Keys.BaiduSecret] ?: default.baiduOcrSecretKey,
            a11yVolumeTrigger = this[Keys.A11yVolume] ?: default.a11yVolumeTrigger,
            tencentSecretId = this[Keys.TencentId] ?: default.tencentSecretId,
            tencentSecretKey = this[Keys.TencentKey] ?: default.tencentSecretKey,
            tencentRegion = this[Keys.TencentRegion] ?: default.tencentRegion,
            preferShizukuCapture = this[Keys.PreferShizuku] ?: default.preferShizukuCapture,
            overlayPlacement = runCatching { OverlayPlacement.valueOf(this[Keys.Placement] ?: "") }
                .getOrDefault(default.overlayPlacement),
            paddleModelMirrorUrl = this[Keys.PaddleMirror] ?: default.paddleModelMirrorUrl,
            overlayOffsetX = this[Keys.OffsetX] ?: default.overlayOffsetX,
            overlayOffsetY = this[Keys.OffsetY] ?: default.overlayOffsetY,
            overlayTheme = runCatching { OverlayTheme.valueOf(this[Keys.ThemeKey] ?: "") }
                .getOrDefault(default.overlayTheme),
            customBgColor = this[Keys.CustomBg] ?: default.customBgColor,
            customFgColor = this[Keys.CustomFg] ?: default.customFgColor,
            customBorderColor = this[Keys.CustomBorder] ?: default.customBorderColor,
            customBorderWidth = this[Keys.CustomBorderW] ?: default.customBorderWidth,
            translatorEngine = runCatching { TranslatorEngine.valueOf(this[Keys.TranslatorEng] ?: "") }
                .getOrDefault(default.translatorEngine),
            deeplApiKey = this[Keys.DeeplKey] ?: default.deeplApiKey,
            deeplPro = this[Keys.DeeplPro] ?: default.deeplPro,
            deeplProtocol = runCatching { DeeplProtocol.valueOf(this[Keys.DeeplProtocol] ?: "") }
                .getOrDefault(default.deeplProtocol),
            deeplBaseUrl = this[Keys.DeeplBaseUrl] ?: default.deeplBaseUrl,
            deeplBearerAuth = this[Keys.DeeplBearerAuth] ?: default.deeplBearerAuth,
            deeplCustomToken = this[Keys.DeeplCustomToken] ?: default.deeplCustomToken,
            floatingButtonSizeDp = this[Keys.FloatingSize] ?: default.floatingButtonSizeDp,
            floatingButtonX = this[Keys.FloatingX] ?: default.floatingButtonX,
            floatingButtonY = this[Keys.FloatingY] ?: default.floatingButtonY,
            floatingButtonSnapToEdge = this[Keys.FloatingSnapEdge] ?: default.floatingButtonSnapToEdge,
            floatingButtonAutoDock = this[Keys.FloatingAutoDock] ?: default.floatingButtonAutoDock,
            floatingButtonDockInsetDp = this[Keys.FloatingDockInset] ?: default.floatingButtonDockInsetDp,
            pinnedLanguages = this[Keys.PinnedLangs]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: default.pinnedLanguages,
            overlayAllowWrap = this[Keys.OverlayWrap] ?: default.overlayAllowWrap,
            overlayAvoidCollision = this[Keys.OverlayCollision] ?: default.overlayAvoidCollision,
            baiduOcrEndpoint = runCatching { BaiduOcrEndpoint.valueOf(this[Keys.BaiduEndpoint] ?: "") }
                .getOrDefault(default.baiduOcrEndpoint),
            baiduOcrLanguage = runCatching { BaiduOcrLanguage.valueOf(this[Keys.BaiduLanguage] ?: "") }
                .getOrDefault(default.baiduOcrLanguage),
            tencentOcrEndpoint = runCatching { TencentOcrEndpoint.valueOf(this[Keys.TencentEndpoint] ?: "") }
                .getOrDefault(default.tencentOcrEndpoint),
            tencentOcrLanguage = runCatching { TencentOcrLanguage.valueOf(this[Keys.TencentLanguage] ?: "") }
                .getOrDefault(default.tencentOcrLanguage),
            apiTimeoutSeconds = this[Keys.ApiTimeoutSec] ?: default.apiTimeoutSeconds,
            mergeAdjacentBlocks = this[Keys.MergeAdjacent] ?: default.mergeAdjacentBlocks,
            mergeStrength = runCatching { MergeStrength.valueOf(this[Keys.MergeStrengthKey] ?: "") }
                .getOrDefault(default.mergeStrength),
            youdaoAppKey = this[Keys.YoudaoAppKey] ?: default.youdaoAppKey,
            youdaoAppSecret = this[Keys.YoudaoAppSecret] ?: default.youdaoAppSecret,
            cleartextAllowedHosts = this[Keys.CleartextHosts]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: default.cleartextAllowedHosts
        )
    }
}
