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
        val RegionSavedW = intPreferencesKey("capture_region_saved_screen_w")
        val RegionSavedH = intPreferencesKey("capture_region_saved_screen_h")
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
        val FloatingWindowX = intPreferencesKey("floating_window_x")
        val FloatingWindowY = intPreferencesKey("floating_window_y")
        val FloatingWindowW = intPreferencesKey("floating_window_width_dp")
        val FloatingWindowH = intPreferencesKey("floating_window_height_dp")
        val FloatingWindowContentMode = stringPreferencesKey("floating_window_content_mode")
        val FloatingWindowLocked = booleanPreferencesKey("floating_window_locked")
        val CustomBorderStyle = stringPreferencesKey("custom_border_style")
        /** 0.3.x 旧 key，silent migrate 到 CustomBorderStyle。 */
        val LegacyFloatingWindowBorderStyle = stringPreferencesKey("floating_window_border_style")
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
        val VolcAccessKeyId = stringPreferencesKey("volc_access_key_id")
        val VolcSecretAccessKey = stringPreferencesKey("volc_secret_access_key")
        val VolcRegion = stringPreferencesKey("volc_region")
        val BaiduFanyiAppId = stringPreferencesKey("baidu_fanyi_app_id")
        val BaiduFanyiSecretKey = stringPreferencesKey("baidu_fanyi_secret_key")
        // 明文 HTTP 白名单 host，以 \n 分隔保存（hostname 不含 \n，分隔安全）
        val CleartextHosts = stringPreferencesKey("cleartext_allowed_hosts")
        // 弧菜单按钮顺序：逗号分隔 MenuItemId.name 列表。MenuItemId.name 不含逗号，分隔安全。
        val FloatingMenuOrder = stringPreferencesKey("floating_menu_item_order")
        // 主球当前技能（FULL_SCREEN / WORD_SELECT）
        val FloatingSkillKey = stringPreferencesKey("floating_button_skill")
        // 划词翻译词典 prompt
        val DictionaryPrompt = stringPreferencesKey("dictionary_prompt")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun get(): Settings = settings.first()

    /**
     * 任何用到 [Settings.captureRegion] 的入口都应该先调一次：把上次保存时屏幕尺寸跟当前
     * 屏幕尺寸比较，不同就按比例 rescale region 写回，更新 saved 字段。这样旋转屏幕时
     * 无论 Service 在没在跑、Activity 重建多少次，region 都保持「相对位置」语义一致。
     *
     * - 历史数据（saved=0）：当前作为新的 savedScreen 写回，region 不动（用户原来在哪屏幕方向
     *   保存的不知道，保守当成「当前方向就是原方向」）。
     * - savedScreen == currentScreen：不动。
     * - 不同：按 currentW/savedW、currentH/savedH 线性 rescale。
     *
     * 返回 rescale 后的 region（已经写回 DataStore）。
     */
    suspend fun rescaleCaptureRegionIfNeeded(currentW: Int, currentH: Int) {
        if (currentW <= 0 || currentH <= 0) return
        val s = get()
        val region = s.captureRegion ?: return
        val savedW = s.captureRegionSavedScreenW
        val savedH = s.captureRegionSavedScreenH
        if (savedW <= 0 || savedH <= 0) {
            update { it.copy(
                captureRegionSavedScreenW = currentW,
                captureRegionSavedScreenH = currentH
            ) }
            return
        }
        if (savedW == currentW && savedH == currentH) return
        val scaleX = currentW.toFloat() / savedW
        val scaleY = currentH.toFloat() / savedH
        val newRegion = CaptureRegion(
            left = (region.left * scaleX).toInt().coerceIn(0, currentW),
            top = (region.top * scaleY).toInt().coerceIn(0, currentH),
            right = (region.right * scaleX).toInt().coerceIn(0, currentW),
            bottom = (region.bottom * scaleY).toInt().coerceIn(0, currentH)
        )
        update { it.copy(
            captureRegion = newRegion,
            captureRegionSavedScreenW = currentW,
            captureRegionSavedScreenH = currentH
        ) }
    }

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
            prefs[Keys.RegionSavedW] = next.captureRegionSavedScreenW
            prefs[Keys.RegionSavedH] = next.captureRegionSavedScreenH
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
            prefs[Keys.FloatingWindowX] = next.floatingWindowX
            prefs[Keys.FloatingWindowY] = next.floatingWindowY
            prefs[Keys.FloatingWindowW] = next.floatingWindowWidthDp
            prefs[Keys.FloatingWindowH] = next.floatingWindowHeightDp
            prefs[Keys.FloatingWindowContentMode] = next.floatingWindowContentMode.name
            prefs[Keys.FloatingWindowLocked] = next.floatingWindowLocked
            prefs[Keys.CustomBorderStyle] = next.customBorderStyle.name
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
            prefs[Keys.VolcAccessKeyId] = next.volcAccessKeyId
            prefs[Keys.VolcSecretAccessKey] = next.volcSecretAccessKey
            prefs[Keys.VolcRegion] = next.volcRegion
            prefs[Keys.BaiduFanyiAppId] = next.baiduFanyiAppId
            prefs[Keys.BaiduFanyiSecretKey] = next.baiduFanyiSecretKey
            prefs[Keys.CleartextHosts] = next.cleartextAllowedHosts.joinToString("\n")
            prefs[Keys.FloatingMenuOrder] = next.floatingMenuItemOrder.joinToString(",") { it.name }
            prefs[Keys.FloatingSkillKey] = next.floatingButtonSkill.name
            prefs[Keys.DictionaryPrompt] = next.dictionaryPrompt
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
            captureRegionSavedScreenW = this[Keys.RegionSavedW] ?: default.captureRegionSavedScreenW,
            captureRegionSavedScreenH = this[Keys.RegionSavedH] ?: default.captureRegionSavedScreenH,
            streamingTranslate = this[Keys.Streaming] ?: default.streamingTranslate,
            // 0.3.x 之前 RenderMode 叫 BANNER，0.4 改名为 FLOATING_WINDOW。silent migrate 老值。
            renderMode = (this[Keys.RenderModeKey] ?: "").let { raw ->
                runCatching { RenderMode.valueOf(raw) }.getOrElse {
                    if (raw == "BANNER") RenderMode.FLOATING_WINDOW else default.renderMode
                }
            },
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
            floatingWindowX = this[Keys.FloatingWindowX] ?: default.floatingWindowX,
            floatingWindowY = this[Keys.FloatingWindowY] ?: default.floatingWindowY,
            floatingWindowWidthDp = this[Keys.FloatingWindowW] ?: default.floatingWindowWidthDp,
            floatingWindowHeightDp = this[Keys.FloatingWindowH] ?: default.floatingWindowHeightDp,
            floatingWindowContentMode = runCatching {
                FloatingWindowContentMode.valueOf(this[Keys.FloatingWindowContentMode] ?: "")
            }.getOrDefault(default.floatingWindowContentMode),
            floatingWindowLocked = this[Keys.FloatingWindowLocked] ?: default.floatingWindowLocked,
            customBorderStyle = runCatching {
                BorderStyle.valueOf(this[Keys.CustomBorderStyle] ?: this[Keys.LegacyFloatingWindowBorderStyle] ?: "")
            }.getOrDefault(default.customBorderStyle),
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
            volcAccessKeyId = this[Keys.VolcAccessKeyId] ?: default.volcAccessKeyId,
            volcSecretAccessKey = this[Keys.VolcSecretAccessKey] ?: default.volcSecretAccessKey,
            volcRegion = this[Keys.VolcRegion] ?: default.volcRegion,
            baiduFanyiAppId = this[Keys.BaiduFanyiAppId] ?: default.baiduFanyiAppId,
            baiduFanyiSecretKey = this[Keys.BaiduFanyiSecretKey] ?: default.baiduFanyiSecretKey,
            cleartextAllowedHosts = this[Keys.CleartextHosts]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: default.cleartextAllowedHosts,
            // 弧菜单按钮顺序：脏数据 / 未知 id silently 丢弃；丢失的已知 id 自动补齐到末尾，
            // 保证 ALL_ORDER 里所有 id 都出现一次。这样后续新版本加新菜单项，老用户也能看到。
            floatingMenuItemOrder = run {
                val raw = this[Keys.FloatingMenuOrder]
                if (raw.isNullOrBlank()) return@run default.floatingMenuItemOrder
                val parsed = raw.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { tok -> runCatching { MenuItemId.valueOf(tok) }.getOrNull() }
                    .distinct()
                if (parsed.isEmpty()) return@run default.floatingMenuItemOrder
                // 补齐缺失的已知 id
                val missing = FloatingMenu.ALL_ORDER.filter { it !in parsed }
                parsed + missing
            },
            floatingButtonSkill = runCatching { FloatingSkill.valueOf(this[Keys.FloatingSkillKey] ?: "") }
                .getOrDefault(default.floatingButtonSkill),
            dictionaryPrompt = this[Keys.DictionaryPrompt] ?: context.getString(R.string.default_dictionary_prompt)
        )
    }
}
