package com.gameocr.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
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

private fun normalizeLoopFrameSimilarity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.50f, 0.99f) else 0.95f

private fun normalizeLoopTextStableDuration(value: Long): Long = value.coerceIn(200L, 2000L)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    secretCipher: SettingsSecretCipher
) {
    private object Keys {
        val BaseUrl = stringPreferencesKey("base_url")
        val ApiKey = stringPreferencesKey("api_key")
        val Model = stringPreferencesKey("model")
        val AnthropicBaseUrl = stringPreferencesKey("anthropic_base_url")
        val AnthropicApiKey = stringPreferencesKey("anthropic_api_key")
        val AnthropicModel = stringPreferencesKey("anthropic_model")
        val SourceLang = stringPreferencesKey("source_lang")
        val TargetLang = stringPreferencesKey("target_lang")
        val Prompt = stringPreferencesKey("prompt")
        val OcrEngine = stringPreferencesKey("ocr_engine")
        val LoopInterval = longPreferencesKey("loop_interval_ms")
        val LoopTriggerMode = stringPreferencesKey("loop_trigger_mode")
        val LoopTextStableDuration = longPreferencesKey("loop_text_stable_duration_ms")
        val LoopSkipSimilarFrames = booleanPreferencesKey("loop_skip_similar_frames")
        val LoopFrameSimilarityThreshold = floatPreferencesKey("loop_frame_similarity_threshold")
        val LoopTextRegionMode = stringPreferencesKey("loop_text_region_mode")
        val LoopTranslateRegionOnly = booleanPreferencesKey("loop_translate_region_only")
        val DeveloperOptionsEnabled = booleanPreferencesKey("developer_options_enabled")
        val OcrScreenshotSavingEnabled = booleanPreferencesKey("ocr_screenshot_saving_enabled")
        val DisableTranslationCache = booleanPreferencesKey("disable_translation_cache")
        val BatchCumulativeCompletionTimeEnabled =
            booleanPreferencesKey("batch_cumulative_completion_time_enabled")
        val DisableCrossLineContextTranslation =
            booleanPreferencesKey("disable_cross_line_context_translation")
        val OcrRedBoxModeEnabled = booleanPreferencesKey("ocr_red_box_mode_enabled")
        val OcrRedBoxShowSourceText = booleanPreferencesKey("ocr_red_box_show_source_text")
        val OcrRedBoxShowTranslation = booleanPreferencesKey("ocr_red_box_show_translation")
        val OverlayStyleMode = stringPreferencesKey("overlay_style_mode")
        val TextSize = intPreferencesKey("overlay_text_size")
        val OverlayTextStyle = stringPreferencesKey("overlay_text_style_json")
        val Alpha = floatPreferencesKey("overlay_alpha")
        val OverlayFontFileName = stringPreferencesKey("overlay_font_file_name")
        val OverlayFontDisplayName = stringPreferencesKey("overlay_font_display_name")
        val OverlayFonts = stringPreferencesKey("overlay_fonts_json")
        val Region = stringPreferencesKey("capture_region_json")
        val RegionSavedW = intPreferencesKey("capture_region_saved_screen_w")
        val RegionSavedH = intPreferencesKey("capture_region_saved_screen_h")
        val Streaming = booleanPreferencesKey("streaming_translate")
        val RetryEmptyTranslation = booleanPreferencesKey("retry_empty_translation")
        val TtsEnabled = booleanPreferencesKey("tts_enabled")
        val TtsProvider = stringPreferencesKey("tts_provider")
        val TtsVoice = stringPreferencesKey("tts_voice")
        val TtsEmotion = stringPreferencesKey("tts_emotion")
        val TtsSpeed = floatPreferencesKey("tts_speed")
        val TtsPitch = floatPreferencesKey("tts_pitch")
        val TtsGainDb = intPreferencesKey("tts_gain_db")
        val TtsHttpBaseUrl = stringPreferencesKey("tts_http_base_url")
        val TtsHttpBearerToken = stringPreferencesKey("tts_http_bearer_token")
        val TtsHttpResponseMode = stringPreferencesKey("tts_http_response_mode")
        val TtsVolcengineResource = stringPreferencesKey("tts_volcengine_resource")
        val TtsVolcengineBaseUrl = stringPreferencesKey("tts_volcengine_base_url")
        val TtsVolcengineApiKey = stringPreferencesKey("tts_volcengine_api_key")
        val TtsVolcengineSpeaker = stringPreferencesKey("tts_volcengine_speaker")
        val TtsVolcengineModel = stringPreferencesKey("tts_volcengine_model")
        val TtsVolcengineContext = stringPreferencesKey("tts_volcengine_context")
        val TtsVolcenginePitch = intPreferencesKey("tts_volcengine_pitch")
        val TtsVolcengineToneFidelity = booleanPreferencesKey("tts_volcengine_tone_fidelity")
        val TtsMiniMaxModel = stringPreferencesKey("tts_minimax_model")
        val TtsMiniMaxBaseUrl = stringPreferencesKey("tts_minimax_base_url")
        val TtsMiniMaxApiKey = stringPreferencesKey("tts_minimax_api_key")
        val TtsMiniMaxVoice = stringPreferencesKey("tts_minimax_voice")
        val TtsMiniMaxEmotion = stringPreferencesKey("tts_minimax_emotion")
        val TtsMiniMaxSpeed = floatPreferencesKey("tts_minimax_speed")
        val TtsMiniMaxPitch = intPreferencesKey("tts_minimax_pitch")
        val TtsMimoModel = stringPreferencesKey("tts_mimo_model")
        val TtsMimoBaseUrl = stringPreferencesKey("tts_mimo_base_url")
        val TtsMimoApiKey = stringPreferencesKey("tts_mimo_api_key")
        val TtsMimoVoice = stringPreferencesKey("tts_mimo_voice")
        // Legacy single field, read only for migration.
        val TtsMimoInstruction = stringPreferencesKey("tts_mimo_instruction")
        val TtsMimoPresetInstruction = stringPreferencesKey("tts_mimo_preset_instruction")
        val TtsMimoVoiceDesignPrompt = stringPreferencesKey("tts_mimo_voice_design_prompt")
        val TtsMimoVoiceCloneInstruction = stringPreferencesKey("tts_mimo_voice_clone_instruction")
        val TtsMimoVoiceSampleUri = stringPreferencesKey("tts_mimo_voice_sample_uri")
        val RenderModeKey = stringPreferencesKey("render_mode")
        val TranslationBlockInteractionMode = stringPreferencesKey("translation_block_interaction_mode")
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
        val PaddleVersion = stringPreferencesKey("paddle_model_version")
        val PaddleDetectionProfile = stringPreferencesKey("paddle_detection_profile")
        val PaddleMirror = stringPreferencesKey("paddle_mirror_url")
        val MangaOcrMirror = stringPreferencesKey("manga_ocr_mirror_url")
        val OrientationModelMirror = stringPreferencesKey("orientation_model_mirror_url")
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
        val MlKitRecentSourceLanguages = stringPreferencesKey("mlkit_recent_source_languages")
        val OverlayWrap = booleanPreferencesKey("overlay_allow_wrap")
        val OverlayCollision = booleanPreferencesKey("overlay_avoid_collision")
        val BaiduEndpoint = stringPreferencesKey("baidu_ocr_endpoint")
        val BaiduLanguage = stringPreferencesKey("baidu_ocr_language")
        val UmiOcrBaseUrl = stringPreferencesKey("umi_ocr_base_url")
        val LunaOcrBaseUrl = stringPreferencesKey("luna_ocr_base_url")
        val PaddleAiStudioToken = stringPreferencesKey("paddle_ai_studio_token")
        val TencentEndpoint = stringPreferencesKey("tencent_ocr_endpoint")
        val TencentLanguage = stringPreferencesKey("tencent_ocr_language")
        val ApiTimeoutSec = intPreferencesKey("api_timeout_seconds")
        val MergeAdjacent = booleanPreferencesKey("ocr_merge_adjacent")
        val MergeStrengthKey = stringPreferencesKey("ocr_merge_strength")
        val TextOrientAutoDetect = booleanPreferencesKey("text_orient_auto_detect")
        val TextOrientAutoDefaultOnMigrated = booleanPreferencesKey("text_orient_auto_default_on_migrated")
        val ManualTextOrient = stringPreferencesKey("manual_text_orient")
        val TranslationOutputFollowRecognition =
            booleanPreferencesKey("translation_output_follow_recognition")
        val TranslationOutputLayout = stringPreferencesKey("translation_output_layout")
        val TranslationOutputDirection = stringPreferencesKey("translation_output_direction")
        val TranslationGlossaryEnabled = booleanPreferencesKey("translation_glossary_enabled")
        val ForegroundAppDetectionMode = stringPreferencesKey("foreground_app_detection_mode")
        val SendAppNameToTranslator = booleanPreferencesKey("send_app_name_to_translator")
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
        val ArcMenuPageSize = intPreferencesKey("arc_menu_page_size")
        val TranslationPresets = stringPreferencesKey("translation_presets_json")
        val ActiveTranslationPresetId = stringPreferencesKey("active_translation_preset_id")
        // 主球当前技能（FULL_SCREEN / WORD_SELECT）
        val FloatingSkillKey = stringPreferencesKey("floating_button_skill")
        // 划词翻译行为开关
        val WordSelectPreciseAdjust = booleanPreferencesKey("word_select_precise_adjust")
        val WordSelectCardMode = booleanPreferencesKey("word_select_card_mode")
        val WordSelectRememberRegion = booleanPreferencesKey("word_select_remember_region")
        val WordSelectLastRegion = stringPreferencesKey("word_select_last_region_json")
        val WordSelectLastRegionSavedW = intPreferencesKey("word_select_last_region_saved_screen_w")
        val WordSelectLastRegionSavedH = intPreferencesKey("word_select_last_region_saved_screen_h")
        // 划词翻译词典 prompt
        val DictionaryPrompt = stringPreferencesKey("dictionary_prompt")
        // 端侧 LLM 推理参数
        val LocalLlmCtxSize = intPreferencesKey("local_llm_ctx_size")
        val LocalLlmMaxNewTokens = intPreferencesKey("local_llm_max_new_tokens")
        val LocalLlmMirrorChoice = stringPreferencesKey("local_llm_mirror_choice")
        val LocalLlmMirror = stringPreferencesKey("local_llm_mirror_url")
        // DBNet 检测阈值：prob/score/gap 共用；unclip 按 Paddle/MangaOCR 分开。
        val DbnetProbThresh = floatPreferencesKey("dbnet_prob_thresh")
        val DbnetBoxScoreThresh = floatPreferencesKey("dbnet_box_score_thresh")
        val DbnetUnclipRatio = floatPreferencesKey("dbnet_unclip_ratio")
        val MangaOcrDbnetUnclipRatio = floatPreferencesKey("manga_ocr_dbnet_unclip_ratio")
        val BubbleClusterGap = intPreferencesKey("bubble_cluster_gap")
        val MangaOcrCropPaddingPx = intPreferencesKey("manga_ocr_crop_padding_px")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val secretCodec = SettingsSecretCodec(secretCipher)
    private var defaultPromptProvider: () -> String = { context.getString(R.string.default_prompt) }
    private var defaultDictionaryPromptProvider: () -> String = {
        context.getString(R.string.default_dictionary_prompt)
    }
    private val secureStringKeys = listOf(
        Keys.BaseUrl,
        Keys.ApiKey,
        Keys.Prompt,
        Keys.BaiduKey,
        Keys.BaiduSecret,
        Keys.TencentId,
        Keys.TencentKey,
        Keys.PaddleMirror,
        Keys.MangaOcrMirror,
        Keys.OrientationModelMirror,
        Keys.DeeplKey,
        Keys.DeeplBaseUrl,
        Keys.DeeplCustomToken,
        Keys.YoudaoAppKey,
        Keys.YoudaoAppSecret,
        Keys.PaddleAiStudioToken,
        Keys.VolcAccessKeyId,
        Keys.VolcSecretAccessKey,
        Keys.BaiduFanyiAppId,
        Keys.BaiduFanyiSecretKey,
        Keys.UmiOcrBaseUrl,
        Keys.LunaOcrBaseUrl,
        Keys.TtsHttpBaseUrl,
        Keys.TtsHttpBearerToken,
        Keys.TtsVolcengineBaseUrl,
        Keys.TtsVolcengineApiKey,
        Keys.TtsMiniMaxBaseUrl,
        Keys.TtsMiniMaxApiKey,
        Keys.TtsMimoBaseUrl,
        Keys.TtsMimoApiKey,
        Keys.CleartextHosts,
        Keys.DictionaryPrompt,
        Keys.LocalLlmMirror,
        Keys.TranslationPresets
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun get(): Settings = settings.first()

    internal fun setDefaultPromptProvidersForTest(
        prompt: () -> String,
        dictionaryPrompt: () -> String,
    ) {
        defaultPromptProvider = prompt
        defaultDictionaryPromptProvider = dictionaryPrompt
    }

    suspend fun migratePlaintextSecretsIfNeeded(): Int {
        var migrated = 0
        context.dataStore.edit { prefs ->
            secureStringKeys.forEach { key ->
                val raw = prefs[key]
                if (secretCodec.needsMigration(raw)) {
                    prefs[key] = secretCodec.encryptPlainText(raw.orEmpty())
                    migrated++
                }
            }
        }
        return migrated
    }

    suspend fun migrateTextOrientationAutoDetectDefaultOnIfNeeded(): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            if (prefs[Keys.TextOrientAutoDefaultOnMigrated] == true) return@edit
            if (prefs[Keys.TextOrientAutoDetect] == false) {
                prefs[Keys.TextOrientAutoDetect] = true
                changed = true
            }
            prefs[Keys.TextOrientAutoDefaultOnMigrated] = true
        }
        return changed
    }

    suspend fun migrateRetiredMangaOcrAdvancedSettingsIfNeeded(): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            if (prefs[Keys.BubbleClusterGap]?.let {
                    MangaOcrAdvancedSettingsPolicy.effectiveBubbleClusterGap(it)
                } != prefs[Keys.BubbleClusterGap]
            ) {
                prefs[Keys.BubbleClusterGap] = MangaOcrAdvancedSettingsPolicy.BUBBLE_CLUSTER_GAP
                changed = true
            }
            if (prefs[Keys.MangaOcrCropPaddingPx]?.let {
                    MangaOcrAdvancedSettingsPolicy.effectiveCropPaddingPx(it)
                } != prefs[Keys.MangaOcrCropPaddingPx]
            ) {
                prefs[Keys.MangaOcrCropPaddingPx] = MangaOcrAdvancedSettingsPolicy.CROP_PADDING_PX
                changed = true
            }

            val storedPresets = prefs[Keys.TranslationPresets]
                ?.let(secretCodec::decodeStored)
                ?.takeIf(String::isNotBlank)
                ?.let { raw ->
                    runCatching { json.decodeFromString<List<TranslationPreset>>(raw) }
                        .getOrNull()
                }
            val normalizedPresets = storedPresets?.map(MangaOcrAdvancedSettingsPolicy::normalize)
            if (storedPresets != null && normalizedPresets != storedPresets) {
                prefs.putSecure(Keys.TranslationPresets, json.encodeToString(normalizedPresets))
                changed = true
            }
        }
        return changed
    }

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

    suspend fun rescaleWordSelectLastRegionIfNeeded(currentW: Int, currentH: Int) {
        if (currentW <= 0 || currentH <= 0) return
        val s = get()
        val region = s.wordSelectLastRegion ?: return
        val savedW = s.wordSelectLastRegionSavedScreenW
        val savedH = s.wordSelectLastRegionSavedScreenH
        if (savedW <= 0 || savedH <= 0) {
            update { it.copy(wordSelectLastRegionSavedScreenW = currentW, wordSelectLastRegionSavedScreenH = currentH) }
            return
        }
        if (savedW == currentW && savedH == currentH) return
        val scaleX = currentW.toFloat() / savedW
        val scaleY = currentH.toFloat() / savedH
        update { it.copy(
            wordSelectLastRegion = CaptureRegion(
                left = (region.left * scaleX).toInt().coerceIn(0, currentW),
                top = (region.top * scaleY).toInt().coerceIn(0, currentH),
                right = (region.right * scaleX).toInt().coerceIn(0, currentW),
                bottom = (region.bottom * scaleY).toInt().coerceIn(0, currentH),
            ),
            wordSelectLastRegionSavedScreenW = currentW,
            wordSelectLastRegionSavedScreenH = currentH,
        ) }
    }

    private fun MutablePreferences.putSecure(key: Preferences.Key<String>, value: String) {
        this[key] = secretCodec.encryptPlainText(value)
    }

    private fun Preferences.secureString(
        key: Preferences.Key<String>,
        defaultValue: String
    ): String = this[key]?.let { secretCodec.decodeStored(it) } ?: defaultValue

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = MangaOcrAdvancedSettingsPolicy.normalize(transform(current))
            prefs.putSecure(Keys.BaseUrl, next.baseUrl)
            prefs.putSecure(Keys.ApiKey, next.apiKey)
            prefs[Keys.Model] = next.model
            prefs.putSecure(Keys.AnthropicBaseUrl, next.anthropicBaseUrl)
            prefs.putSecure(Keys.AnthropicApiKey, next.anthropicApiKey)
            prefs[Keys.AnthropicModel] = next.anthropicModel
            prefs[Keys.SourceLang] = next.sourceLang
            prefs[Keys.TargetLang] = next.targetLang
            prefs.putSecure(Keys.Prompt, next.promptTemplate)
            prefs[Keys.OcrEngine] = next.ocrEngine.name
            prefs[Keys.LoopInterval] = next.captureLoopIntervalMs
            prefs[Keys.LoopTriggerMode] = next.loopTriggerMode.name
            prefs[Keys.LoopTextStableDuration] =
                normalizeLoopTextStableDuration(next.loopTextStableDurationMs)
            prefs[Keys.LoopSkipSimilarFrames] = next.loopSkipSimilarFrames
            prefs[Keys.LoopFrameSimilarityThreshold] =
                normalizeLoopFrameSimilarity(next.loopFrameSimilarityThreshold)
            prefs[Keys.LoopTextRegionMode] = next.loopTextRegionMode.name
            prefs[Keys.LoopTranslateRegionOnly] = next.loopTranslateRegionOnly
            prefs[Keys.DeveloperOptionsEnabled] = next.developerOptionsEnabled
            prefs[Keys.OcrScreenshotSavingEnabled] = next.ocrScreenshotSavingEnabled
            prefs[Keys.DisableTranslationCache] = next.disableTranslationCache
            prefs[Keys.BatchCumulativeCompletionTimeEnabled] =
                next.batchCumulativeCompletionTimeEnabled
            prefs[Keys.DisableCrossLineContextTranslation] = next.disableCrossLineContextTranslation
            prefs[Keys.OcrRedBoxModeEnabled] = next.ocrRedBoxModeEnabled
            prefs[Keys.OcrRedBoxShowSourceText] = next.ocrRedBoxShowSourceText
            prefs[Keys.OcrRedBoxShowTranslation] = next.ocrRedBoxShowTranslation
            prefs[Keys.OverlayStyleMode] = next.overlayStyleMode.name
            prefs[Keys.TextSize] = next.overlayTextSizeSp
            prefs[Keys.OverlayTextStyle] = json.encodeToString(next.overlayTextStyle.normalized())
            prefs[Keys.Alpha] = next.overlayAlpha
            prefs[Keys.OverlayFontFileName] = next.overlayFontFileName
            prefs[Keys.OverlayFontDisplayName] = next.overlayFontDisplayName
            prefs[Keys.OverlayFonts] = json.encodeToString(
                OverlayFontPolicy.normalizeImportedFonts(next.overlayFonts)
            )
            prefs[Keys.Region] = next.captureRegion?.let { json.encodeToString(it) } ?: ""
            prefs[Keys.RegionSavedW] = next.captureRegionSavedScreenW
            prefs[Keys.RegionSavedH] = next.captureRegionSavedScreenH
            prefs[Keys.Streaming] = next.streamingTranslate
            prefs[Keys.RetryEmptyTranslation] = next.retryEmptyTranslation
            prefs[Keys.TtsEnabled] = next.ttsEnabled
            prefs[Keys.TtsProvider] = next.ttsProvider.name
            prefs[Keys.TtsVoice] = next.ttsVoice
            prefs[Keys.TtsEmotion] = next.ttsEmotion
            prefs[Keys.TtsSpeed] = next.ttsSpeed.coerceIn(0.25f, 4.0f)
            prefs[Keys.TtsPitch] = next.ttsPitch.coerceIn(0.25f, 4.0f)
            prefs[Keys.TtsGainDb] = next.ttsGainDb.coerceIn(
                MIN_TTS_PLAYBACK_GAIN_DB,
                MAX_TTS_PLAYBACK_GAIN_DB,
            )
            prefs.putSecure(Keys.TtsHttpBaseUrl, next.ttsHttpBaseUrl)
            prefs.putSecure(Keys.TtsHttpBearerToken, next.ttsHttpBearerToken)
            prefs[Keys.TtsHttpResponseMode] = next.ttsHttpResponseMode.name
            prefs[Keys.TtsVolcengineResource] = next.ttsVolcengineResource.name
            prefs.putSecure(Keys.TtsVolcengineBaseUrl, next.ttsVolcengineBaseUrl)
            prefs.putSecure(Keys.TtsVolcengineApiKey, next.ttsVolcengineApiKey)
            prefs[Keys.TtsVolcengineSpeaker] = next.ttsVolcengineSpeaker
            prefs[Keys.TtsVolcengineModel] = next.ttsVolcengineModel
            prefs[Keys.TtsVolcengineContext] = next.ttsVolcengineContext
            prefs[Keys.TtsVolcenginePitch] = next.ttsVolcenginePitch.coerceIn(-12, 12)
            prefs[Keys.TtsVolcengineToneFidelity] = next.ttsVolcengineToneFidelity
            prefs[Keys.TtsMiniMaxModel] = next.ttsMiniMaxModel.name
            prefs.putSecure(Keys.TtsMiniMaxBaseUrl, next.ttsMiniMaxBaseUrl)
            prefs.putSecure(Keys.TtsMiniMaxApiKey, next.ttsMiniMaxApiKey)
            prefs[Keys.TtsMiniMaxVoice] = next.ttsMiniMaxVoice
            prefs[Keys.TtsMiniMaxEmotion] = next.ttsMiniMaxEmotion
            prefs[Keys.TtsMiniMaxSpeed] = next.ttsMiniMaxSpeed.coerceIn(0.5f, 2.0f)
            prefs[Keys.TtsMiniMaxPitch] = next.ttsMiniMaxPitch.coerceIn(-12, 12)
            prefs[Keys.TtsMimoModel] = next.ttsMimoModel.name
            prefs.putSecure(Keys.TtsMimoBaseUrl, next.ttsMimoBaseUrl)
            prefs.putSecure(Keys.TtsMimoApiKey, next.ttsMimoApiKey)
            prefs[Keys.TtsMimoVoice] = next.ttsMimoVoice
            prefs[Keys.TtsMimoPresetInstruction] = next.ttsMimoInstruction
            prefs[Keys.TtsMimoVoiceDesignPrompt] = next.ttsMimoVoiceDesignPrompt
            prefs[Keys.TtsMimoVoiceCloneInstruction] = next.ttsMimoVoiceCloneInstruction
            prefs.remove(Keys.TtsMimoInstruction)
            prefs[Keys.TtsMimoVoiceSampleUri] = next.ttsMimoVoiceSampleUri
            prefs[Keys.RenderModeKey] = next.renderMode.name
            prefs[Keys.TranslationBlockInteractionMode] = next.translationBlockInteractionMode.name
            prefs[Keys.Upscale] = next.preprocess.upscale2x
            prefs[Keys.Invert] = next.preprocess.invert
            prefs[Keys.Binarize] = next.preprocess.binarize
            prefs.putSecure(Keys.BaiduKey, next.baiduOcrApiKey)
            prefs.putSecure(Keys.BaiduSecret, next.baiduOcrSecretKey)
            prefs[Keys.A11yVolume] = next.a11yVolumeTrigger
            prefs.putSecure(Keys.TencentId, next.tencentSecretId)
            prefs.putSecure(Keys.TencentKey, next.tencentSecretKey)
            prefs[Keys.TencentRegion] = next.tencentRegion
            prefs[Keys.PreferShizuku] = next.preferShizukuCapture
            prefs[Keys.Placement] = next.overlayPlacement.name
            prefs[Keys.PaddleVersion] = next.paddleModelVersion.name
            prefs[Keys.PaddleDetectionProfile] = next.paddleDetectionProfile.name
            prefs.putSecure(Keys.PaddleMirror, next.paddleModelMirrorUrl)
            prefs.putSecure(Keys.MangaOcrMirror, next.mangaOcrModelMirrorUrl)
            prefs.putSecure(Keys.OrientationModelMirror, next.orientationModelMirrorUrl)
            prefs[Keys.OffsetX] = next.overlayOffsetX
            prefs[Keys.OffsetY] = next.overlayOffsetY
            prefs[Keys.ThemeKey] = next.overlayTheme.name
            prefs[Keys.CustomBg] = next.customBgColor
            prefs[Keys.CustomFg] = next.customFgColor
            prefs[Keys.CustomBorder] = next.customBorderColor
            prefs[Keys.CustomBorderW] = next.customBorderWidth
            prefs[Keys.TranslatorEng] = next.translatorEngine.name
            prefs[Keys.TranslationGlossaryEnabled] = next.translationGlossaryEnabled
            prefs[Keys.ForegroundAppDetectionMode] = next.foregroundAppDetectionMode.name
            prefs[Keys.SendAppNameToTranslator] = next.sendAppNameToTranslator
            prefs.putSecure(Keys.DeeplKey, next.deeplApiKey)
            prefs[Keys.DeeplPro] = next.deeplPro
            prefs[Keys.DeeplProtocol] = next.deeplProtocol.name
            prefs.putSecure(Keys.DeeplBaseUrl, next.deeplBaseUrl)
            prefs[Keys.DeeplBearerAuth] = next.deeplBearerAuth
            prefs.putSecure(Keys.DeeplCustomToken, next.deeplCustomToken)
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
            prefs[Keys.MlKitRecentSourceLanguages] = next.mlKitRecentSourceLanguages.joinToString(",")
            prefs[Keys.OverlayWrap] = next.overlayAllowWrap
            prefs[Keys.OverlayCollision] = next.overlayAvoidCollision
            prefs[Keys.BaiduEndpoint] = next.baiduOcrEndpoint.name
            prefs[Keys.BaiduLanguage] = next.baiduOcrLanguage.name
            prefs.putSecure(Keys.UmiOcrBaseUrl, next.umiOcrBaseUrl)
            prefs.putSecure(Keys.LunaOcrBaseUrl, next.lunaOcrBaseUrl)
            prefs.putSecure(Keys.PaddleAiStudioToken, next.paddleAiStudioToken)
            prefs[Keys.TencentEndpoint] = next.tencentOcrEndpoint.name
            prefs[Keys.TencentLanguage] = next.tencentOcrLanguage.name
            prefs[Keys.ApiTimeoutSec] = next.apiTimeoutSeconds
            prefs[Keys.MergeAdjacent] = next.mergeAdjacentBlocks
            prefs[Keys.MergeStrengthKey] = next.mergeStrength.name
            prefs[Keys.TextOrientAutoDetect] = next.textOrientationAutoDetect
            // null 用 remove 而非写空串：toSettings 用 runCatching valueOf 解析，空串会 fallback 到 null
            // 但显式 remove 让 DataStore 文件更干净，未来 grep 无歧义
            next.manualTextOrientation?.let { prefs[Keys.ManualTextOrient] = it.name }
                ?: prefs.remove(Keys.ManualTextOrient)
            val translationOutput = resolveTranslationOutputSettings(
                next.translationOutputFollowRecognition,
                next.translationOutputLayout,
                next.translationOutputDirection,
            )
            prefs[Keys.TranslationOutputFollowRecognition] = translationOutput.followRecognition
            prefs[Keys.TranslationOutputLayout] = translationOutput.layout.name
            prefs[Keys.TranslationOutputDirection] = translationOutput.direction.name
            prefs.putSecure(Keys.YoudaoAppKey, next.youdaoAppKey)
            prefs.putSecure(Keys.YoudaoAppSecret, next.youdaoAppSecret)
            prefs.putSecure(Keys.VolcAccessKeyId, next.volcAccessKeyId)
            prefs.putSecure(Keys.VolcSecretAccessKey, next.volcSecretAccessKey)
            prefs[Keys.VolcRegion] = next.volcRegion
            prefs.putSecure(Keys.BaiduFanyiAppId, next.baiduFanyiAppId)
            prefs.putSecure(Keys.BaiduFanyiSecretKey, next.baiduFanyiSecretKey)
            prefs.putSecure(Keys.CleartextHosts, next.cleartextAllowedHosts.joinToString("\n"))
            prefs[Keys.FloatingMenuOrder] = next.floatingMenuItemOrder.joinToString(",") { it.name }
            prefs[Keys.ArcMenuPageSize] = FloatingMenu.coercePageSize(next.arcMenuPageSize)
            prefs.putSecure(
                Keys.TranslationPresets,
                json.encodeToString(
                    next.translationPresets
                        .map(MangaOcrAdvancedSettingsPolicy::normalize)
                        .filterNot { TranslationPresetCatalog.isBuiltIn(it.id) }
                )
            )
            prefs[Keys.ActiveTranslationPresetId] = next.activeTranslationPresetId
            prefs[Keys.FloatingSkillKey] = next.floatingButtonSkill.name
            prefs[Keys.WordSelectPreciseAdjust] = next.wordSelectPreciseAdjust
            prefs[Keys.WordSelectCardMode] = next.wordSelectCardMode
            prefs[Keys.WordSelectRememberRegion] = next.wordSelectRememberRegion
            prefs[Keys.WordSelectLastRegion] = next.wordSelectLastRegion?.let { json.encodeToString(it) } ?: ""
            prefs[Keys.WordSelectLastRegionSavedW] = next.wordSelectLastRegionSavedScreenW
            prefs[Keys.WordSelectLastRegionSavedH] = next.wordSelectLastRegionSavedScreenH
            prefs.putSecure(Keys.DictionaryPrompt, next.dictionaryPrompt)
            prefs[Keys.LocalLlmCtxSize] = next.localLlmContextSize
            prefs[Keys.LocalLlmMaxNewTokens] = next.localLlmMaxNewTokens
            prefs[Keys.LocalLlmMirrorChoice] = next.localLlmMirror.name
            prefs.putSecure(Keys.LocalLlmMirror, next.localLlmMirrorUrl)
            prefs[Keys.DbnetProbThresh] = next.dbnetProbThresh
            prefs[Keys.DbnetBoxScoreThresh] = next.dbnetBoxScoreThresh
            prefs[Keys.DbnetUnclipRatio] = next.dbnetUnclipRatio
            prefs[Keys.MangaOcrDbnetUnclipRatio] = next.mangaOcrDbnetUnclipRatio
            prefs[Keys.BubbleClusterGap] = MangaOcrAdvancedSettingsPolicy.BUBBLE_CLUSTER_GAP
            prefs[Keys.MangaOcrCropPaddingPx] = MangaOcrAdvancedSettingsPolicy.CROP_PADDING_PX
        }
    }

    private fun Preferences.toSettings(): Settings {
        val default = Settings()
        val storedTranslationOutputLayout = runCatching {
            TranslationOutputLayout.valueOf(this[Keys.TranslationOutputLayout] ?: "")
        }.getOrDefault(default.translationOutputLayout)
        val storedTranslationOutputDirection = runCatching {
            TranslationOutputDirection.valueOf(this[Keys.TranslationOutputDirection] ?: "")
        }.getOrDefault(default.translationOutputDirection)
        val translationOutput = resolveTranslationOutputSettings(
            storedFollowRecognition = this[Keys.TranslationOutputFollowRecognition],
            layout = storedTranslationOutputLayout,
            direction = storedTranslationOutputDirection,
        )
        val storedMimoModel = runCatching {
            MimoTtsModel.valueOf(this[Keys.TtsMimoModel] ?: "")
        }.getOrDefault(default.ttsMimoModel)
        val mimoInstructions = resolveMimoInstructionValues(
            model = storedMimoModel,
            legacy = this[Keys.TtsMimoInstruction],
            preset = this[Keys.TtsMimoPresetInstruction],
            voiceDesign = this[Keys.TtsMimoVoiceDesignPrompt],
            voiceClone = this[Keys.TtsMimoVoiceCloneInstruction],
        )
        return MangaOcrAdvancedSettingsPolicy.normalize(Settings(
            baseUrl = secureString(Keys.BaseUrl, default.baseUrl),
            apiKey = secureString(Keys.ApiKey, default.apiKey),
            model = this[Keys.Model] ?: default.model,
            anthropicBaseUrl = secureString(Keys.AnthropicBaseUrl, default.anthropicBaseUrl),
            anthropicApiKey = secureString(Keys.AnthropicApiKey, default.anthropicApiKey),
            anthropicModel = this[Keys.AnthropicModel] ?: default.anthropicModel,
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
            promptTemplate = secureString(Keys.Prompt, defaultPromptProvider()),
            ocrEngine = runCatching { OcrEngineKind.valueOf(this[Keys.OcrEngine] ?: "") }
                .getOrDefault(default.ocrEngine),
            captureLoopIntervalMs = this[Keys.LoopInterval] ?: default.captureLoopIntervalMs,
            loopTriggerMode = this[Keys.LoopTriggerMode]
                ?.let { runCatching { LoopTriggerMode.valueOf(it) }.getOrNull() }
                ?: default.loopTriggerMode,
            loopTextStableDurationMs = normalizeLoopTextStableDuration(
                this[Keys.LoopTextStableDuration] ?: default.loopTextStableDurationMs
            ),
            loopSkipSimilarFrames = this[Keys.LoopSkipSimilarFrames] ?: default.loopSkipSimilarFrames,
            loopFrameSimilarityThreshold = normalizeLoopFrameSimilarity(
                this[Keys.LoopFrameSimilarityThreshold] ?: default.loopFrameSimilarityThreshold
            ),
            loopTextRegionMode = this[Keys.LoopTextRegionMode]
                ?.let { runCatching { LoopTextRegionMode.valueOf(it) }.getOrNull() }
                ?: default.loopTextRegionMode,
            loopTranslateRegionOnly = this[Keys.LoopTranslateRegionOnly]
                ?: default.loopTranslateRegionOnly,
            developerOptionsEnabled = this[Keys.DeveloperOptionsEnabled]
                ?: default.developerOptionsEnabled,
            ocrScreenshotSavingEnabled = this[Keys.OcrScreenshotSavingEnabled]
                ?: default.ocrScreenshotSavingEnabled,
            disableTranslationCache = this[Keys.DisableTranslationCache]
                ?: default.disableTranslationCache,
            batchCumulativeCompletionTimeEnabled =
                this[Keys.BatchCumulativeCompletionTimeEnabled]
                    ?: default.batchCumulativeCompletionTimeEnabled,
            disableCrossLineContextTranslation = this[Keys.DisableCrossLineContextTranslation]
                ?: default.disableCrossLineContextTranslation,
            ocrRedBoxModeEnabled = this[Keys.OcrRedBoxModeEnabled]
                ?: default.ocrRedBoxModeEnabled,
            ocrRedBoxShowSourceText = this[Keys.OcrRedBoxShowSourceText]
                ?: default.ocrRedBoxShowSourceText,
            ocrRedBoxShowTranslation = this[Keys.OcrRedBoxShowTranslation]
                ?: default.ocrRedBoxShowTranslation,
            overlayStyleMode = runCatching {
                OverlayStyleMode.valueOf(this[Keys.OverlayStyleMode] ?: "")
            }.getOrDefault(default.overlayStyleMode),
            overlayTextSizeSp = this[Keys.TextSize] ?: default.overlayTextSizeSp,
            overlayTextStyle = this[Keys.OverlayTextStyle]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { json.decodeFromString<OverlayTextStyle>(raw).normalized() }.getOrNull()
                }
                ?: default.overlayTextStyle,
            overlayAlpha = this[Keys.Alpha] ?: default.overlayAlpha,
            overlayFontFileName = this[Keys.OverlayFontFileName] ?: default.overlayFontFileName,
            overlayFontDisplayName = this[Keys.OverlayFontDisplayName] ?: default.overlayFontDisplayName,
            overlayFonts = this[Keys.OverlayFonts]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching {
                        OverlayFontPolicy.normalizeImportedFonts(
                            json.decodeFromString<List<OverlayFontEntry>>(raw)
                        )
                    }.getOrNull()
                }
                ?: default.overlayFonts,
            captureRegion = this[Keys.Region]?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<CaptureRegion>(it) }.getOrNull()
            },
            captureRegionSavedScreenW = this[Keys.RegionSavedW] ?: default.captureRegionSavedScreenW,
            captureRegionSavedScreenH = this[Keys.RegionSavedH] ?: default.captureRegionSavedScreenH,
            streamingTranslate = this[Keys.Streaming] ?: default.streamingTranslate,
            retryEmptyTranslation = this[Keys.RetryEmptyTranslation] ?: default.retryEmptyTranslation,
            ttsEnabled = this[Keys.TtsEnabled] ?: default.ttsEnabled,
            ttsProvider = parseTtsProvider(this[Keys.TtsProvider].orEmpty(), default.ttsProvider),
            ttsVoice = this[Keys.TtsVoice] ?: default.ttsVoice,
            ttsEmotion = this[Keys.TtsEmotion] ?: default.ttsEmotion,
            ttsSpeed = (this[Keys.TtsSpeed] ?: default.ttsSpeed).coerceIn(0.25f, 4.0f),
            ttsPitch = (this[Keys.TtsPitch] ?: default.ttsPitch).coerceIn(0.25f, 4.0f),
            ttsGainDb = (this[Keys.TtsGainDb] ?: default.ttsGainDb).coerceIn(
                MIN_TTS_PLAYBACK_GAIN_DB,
                MAX_TTS_PLAYBACK_GAIN_DB,
            ),
            ttsHttpBaseUrl = secureString(Keys.TtsHttpBaseUrl, default.ttsHttpBaseUrl),
            ttsHttpBearerToken = secureString(Keys.TtsHttpBearerToken, default.ttsHttpBearerToken),
            ttsHttpResponseMode = runCatching {
                TtsHttpResponseMode.valueOf(this[Keys.TtsHttpResponseMode] ?: "")
            }.getOrDefault(default.ttsHttpResponseMode),
            ttsVolcengineResource = runCatching {
                VolcengineTtsResource.valueOf(this[Keys.TtsVolcengineResource] ?: "")
            }.getOrDefault(default.ttsVolcengineResource),
            ttsVolcengineBaseUrl = secureString(
                Keys.TtsVolcengineBaseUrl,
                default.ttsVolcengineBaseUrl,
            ),
            ttsVolcengineApiKey = secureString(
                Keys.TtsVolcengineApiKey,
                default.ttsVolcengineApiKey,
            ),
            ttsVolcengineSpeaker = this[Keys.TtsVolcengineSpeaker]
                ?: default.ttsVolcengineSpeaker,
            ttsVolcengineModel = this[Keys.TtsVolcengineModel] ?: default.ttsVolcengineModel,
            ttsVolcengineContext = this[Keys.TtsVolcengineContext]
                ?: default.ttsVolcengineContext,
            ttsVolcenginePitch = (this[Keys.TtsVolcenginePitch]
                ?: default.ttsVolcenginePitch).coerceIn(-12, 12),
            ttsVolcengineToneFidelity = this[Keys.TtsVolcengineToneFidelity]
                ?: default.ttsVolcengineToneFidelity,
            ttsMiniMaxModel = runCatching {
                MiniMaxTtsModel.valueOf(this[Keys.TtsMiniMaxModel] ?: "")
            }.getOrDefault(default.ttsMiniMaxModel),
            ttsMiniMaxBaseUrl = secureString(
                Keys.TtsMiniMaxBaseUrl,
                default.ttsMiniMaxBaseUrl,
            ),
            ttsMiniMaxApiKey = secureString(Keys.TtsMiniMaxApiKey, default.ttsMiniMaxApiKey),
            ttsMiniMaxVoice = this[Keys.TtsMiniMaxVoice] ?: default.ttsMiniMaxVoice,
            ttsMiniMaxEmotion = this[Keys.TtsMiniMaxEmotion] ?: default.ttsMiniMaxEmotion,
            ttsMiniMaxSpeed = (this[Keys.TtsMiniMaxSpeed] ?: default.ttsMiniMaxSpeed)
                .coerceIn(0.5f, 2.0f),
            ttsMiniMaxPitch = (this[Keys.TtsMiniMaxPitch] ?: default.ttsMiniMaxPitch)
                .coerceIn(-12, 12),
            ttsMimoModel = storedMimoModel,
            ttsMimoBaseUrl = secureString(Keys.TtsMimoBaseUrl, default.ttsMimoBaseUrl),
            ttsMimoApiKey = secureString(Keys.TtsMimoApiKey, default.ttsMimoApiKey),
            ttsMimoVoice = this[Keys.TtsMimoVoice] ?: default.ttsMimoVoice,
            ttsMimoInstruction = mimoInstructions.preset,
            ttsMimoVoiceDesignPrompt = mimoInstructions.voiceDesign,
            ttsMimoVoiceCloneInstruction = mimoInstructions.voiceClone,
            ttsMimoVoiceSampleUri = this[Keys.TtsMimoVoiceSampleUri]
                ?: default.ttsMimoVoiceSampleUri,
            // 0.3.x 之前 RenderMode 叫 BANNER，0.4 改名为 FLOATING_WINDOW。silent migrate 老值。
            renderMode = (this[Keys.RenderModeKey] ?: "").let { raw ->
                runCatching { RenderMode.valueOf(raw) }.getOrElse {
                    if (raw == "BANNER") RenderMode.FLOATING_WINDOW else default.renderMode
                }
            },
            translationBlockInteractionMode = runCatching {
                TranslationBlockInteractionMode.valueOf(this[Keys.TranslationBlockInteractionMode] ?: "")
            }.getOrDefault(default.translationBlockInteractionMode),
            preprocess = PreprocessOptions(
                upscale2x = this[Keys.Upscale] ?: default.preprocess.upscale2x,
                invert = this[Keys.Invert] ?: default.preprocess.invert,
                binarize = this[Keys.Binarize] ?: default.preprocess.binarize
            ),
            baiduOcrApiKey = secureString(Keys.BaiduKey, default.baiduOcrApiKey),
            baiduOcrSecretKey = secureString(Keys.BaiduSecret, default.baiduOcrSecretKey),
            a11yVolumeTrigger = this[Keys.A11yVolume] ?: default.a11yVolumeTrigger,
            tencentSecretId = secureString(Keys.TencentId, default.tencentSecretId),
            tencentSecretKey = secureString(Keys.TencentKey, default.tencentSecretKey),
            tencentRegion = this[Keys.TencentRegion] ?: default.tencentRegion,
            preferShizukuCapture = this[Keys.PreferShizuku] ?: default.preferShizukuCapture,
            overlayPlacement = runCatching { OverlayPlacement.valueOf(this[Keys.Placement] ?: "") }
                .getOrDefault(default.overlayPlacement),
            paddleModelVersion = runCatching { PaddleModelVersion.valueOf(this[Keys.PaddleVersion] ?: "") }
                .getOrDefault(default.paddleModelVersion),
            paddleDetectionProfile = runCatching {
                PaddleDetectionProfile.valueOf(this[Keys.PaddleDetectionProfile] ?: "")
            }.getOrDefault(default.paddleDetectionProfile),
            paddleModelMirrorUrl = secureString(Keys.PaddleMirror, default.paddleModelMirrorUrl),
            mangaOcrModelMirrorUrl = secureString(Keys.MangaOcrMirror, default.mangaOcrModelMirrorUrl),
            orientationModelMirrorUrl = secureString(
                Keys.OrientationModelMirror,
                default.orientationModelMirrorUrl
            ),
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
            translationGlossaryEnabled = this[Keys.TranslationGlossaryEnabled]
                ?: default.translationGlossaryEnabled,
            foregroundAppDetectionMode = runCatching {
                ForegroundAppDetectionMode.valueOf(this[Keys.ForegroundAppDetectionMode] ?: "")
            }.getOrDefault(default.foregroundAppDetectionMode),
            sendAppNameToTranslator = this[Keys.SendAppNameToTranslator]
                ?: default.sendAppNameToTranslator,
            deeplApiKey = secureString(Keys.DeeplKey, default.deeplApiKey),
            deeplPro = this[Keys.DeeplPro] ?: default.deeplPro,
            deeplProtocol = runCatching { DeeplProtocol.valueOf(this[Keys.DeeplProtocol] ?: "") }
                .getOrDefault(default.deeplProtocol),
            deeplBaseUrl = secureString(Keys.DeeplBaseUrl, default.deeplBaseUrl),
            deeplBearerAuth = this[Keys.DeeplBearerAuth] ?: default.deeplBearerAuth,
            deeplCustomToken = secureString(Keys.DeeplCustomToken, default.deeplCustomToken),
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
            mlKitRecentSourceLanguages = this[Keys.MlKitRecentSourceLanguages]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: default.mlKitRecentSourceLanguages,
            overlayAllowWrap = this[Keys.OverlayWrap] ?: default.overlayAllowWrap,
            overlayAvoidCollision = this[Keys.OverlayCollision] ?: default.overlayAvoidCollision,
            baiduOcrEndpoint = runCatching { BaiduOcrEndpoint.valueOf(this[Keys.BaiduEndpoint] ?: "") }
                .getOrDefault(default.baiduOcrEndpoint),
            baiduOcrLanguage = runCatching { BaiduOcrLanguage.valueOf(this[Keys.BaiduLanguage] ?: "") }
                .getOrDefault(default.baiduOcrLanguage),
            umiOcrBaseUrl = secureString(Keys.UmiOcrBaseUrl, default.umiOcrBaseUrl),
            lunaOcrBaseUrl = secureString(Keys.LunaOcrBaseUrl, default.lunaOcrBaseUrl),
            paddleAiStudioToken = secureString(Keys.PaddleAiStudioToken, default.paddleAiStudioToken),
            tencentOcrEndpoint = runCatching { TencentOcrEndpoint.valueOf(this[Keys.TencentEndpoint] ?: "") }
                .getOrDefault(default.tencentOcrEndpoint),
            tencentOcrLanguage = runCatching { TencentOcrLanguage.valueOf(this[Keys.TencentLanguage] ?: "") }
                .getOrDefault(default.tencentOcrLanguage),
            apiTimeoutSeconds = this[Keys.ApiTimeoutSec] ?: default.apiTimeoutSeconds,
            mergeAdjacentBlocks = this[Keys.MergeAdjacent] ?: default.mergeAdjacentBlocks,
            mergeStrength = runCatching { MergeStrength.valueOf(this[Keys.MergeStrengthKey] ?: "") }
                .getOrDefault(default.mergeStrength),
            textOrientationAutoDetect = this[Keys.TextOrientAutoDetect] ?: default.textOrientationAutoDetect,
            manualTextOrientation = this[Keys.ManualTextOrient]
                ?.let { raw ->
                    runCatching { com.gameocr.app.ocr.TextOrientation.valueOf(raw) }.getOrNull()
                },
            translationOutputFollowRecognition = translationOutput.followRecognition,
            translationOutputLayout = translationOutput.layout,
            translationOutputDirection = translationOutput.direction,
            youdaoAppKey = secureString(Keys.YoudaoAppKey, default.youdaoAppKey),
            youdaoAppSecret = secureString(Keys.YoudaoAppSecret, default.youdaoAppSecret),
            volcAccessKeyId = secureString(Keys.VolcAccessKeyId, default.volcAccessKeyId),
            volcSecretAccessKey = secureString(Keys.VolcSecretAccessKey, default.volcSecretAccessKey),
            volcRegion = this[Keys.VolcRegion] ?: default.volcRegion,
            baiduFanyiAppId = secureString(Keys.BaiduFanyiAppId, default.baiduFanyiAppId),
            baiduFanyiSecretKey = secureString(Keys.BaiduFanyiSecretKey, default.baiduFanyiSecretKey),
            cleartextAllowedHosts = secureString(
                Keys.CleartextHosts,
                default.cleartextAllowedHosts.joinToString("\n")
            )
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
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
                val normalized = parsed + missing
                if (
                    normalized == FloatingMenu.LEGACY_DEFAULT_ORDER_BEFORE_SKILL_SWAP ||
                    normalized == FloatingMenu.LEGACY_DEFAULT_ORDER_BEFORE_PRESET_SKILL_SWAP ||
                    normalized == FloatingMenu.LEGACY_DEFAULT_ORDER_BEFORE_PRESET_LANGUAGE_SWAP
                ) {
                    default.floatingMenuItemOrder
                } else {
                    normalized
                }
            },
            arcMenuPageSize = FloatingMenu.coercePageSize(
                this[Keys.ArcMenuPageSize] ?: default.arcMenuPageSize
            ),
            translationPresets = secureString(Keys.TranslationPresets, "")
                .takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { json.decodeFromString<List<TranslationPreset>>(raw) }
                        .getOrDefault(emptyList())
                        .filterNot { TranslationPresetCatalog.isBuiltIn(it.id) }
                }
                ?: default.translationPresets,
            activeTranslationPresetId = this[Keys.ActiveTranslationPresetId]
                ?: default.activeTranslationPresetId,
            floatingButtonSkill = runCatching { FloatingSkill.valueOf(this[Keys.FloatingSkillKey] ?: "") }
                .getOrDefault(default.floatingButtonSkill),
            wordSelectPreciseAdjust = this[Keys.WordSelectPreciseAdjust] ?: default.wordSelectPreciseAdjust,
            wordSelectCardMode = this[Keys.WordSelectCardMode] ?: default.wordSelectCardMode,
            wordSelectRememberRegion = this[Keys.WordSelectRememberRegion] ?: default.wordSelectRememberRegion,
            wordSelectLastRegion = this[Keys.WordSelectLastRegion]?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<CaptureRegion>(it) }.getOrNull()
            },
            wordSelectLastRegionSavedScreenW = this[Keys.WordSelectLastRegionSavedW] ?: default.wordSelectLastRegionSavedScreenW,
            wordSelectLastRegionSavedScreenH = this[Keys.WordSelectLastRegionSavedH] ?: default.wordSelectLastRegionSavedScreenH,
            dictionaryPrompt = secureString(
                Keys.DictionaryPrompt,
                defaultDictionaryPromptProvider()
            ),
            localLlmContextSize = this[Keys.LocalLlmCtxSize] ?: default.localLlmContextSize,
            localLlmMaxNewTokens = this[Keys.LocalLlmMaxNewTokens] ?: default.localLlmMaxNewTokens,
            localLlmMirror = runCatching { LlmMirrorChoice.valueOf(this[Keys.LocalLlmMirrorChoice] ?: "") }
                .getOrDefault(default.localLlmMirror),
            localLlmMirrorUrl = secureString(Keys.LocalLlmMirror, default.localLlmMirrorUrl),
            dbnetProbThresh = this[Keys.DbnetProbThresh] ?: default.dbnetProbThresh,
            dbnetBoxScoreThresh = this[Keys.DbnetBoxScoreThresh] ?: default.dbnetBoxScoreThresh,
            dbnetUnclipRatio = this[Keys.DbnetUnclipRatio] ?: default.dbnetUnclipRatio,
            mangaOcrDbnetUnclipRatio = this[Keys.MangaOcrDbnetUnclipRatio]
                ?: default.mangaOcrDbnetUnclipRatio,
            bubbleClusterGap = MangaOcrAdvancedSettingsPolicy.BUBBLE_CLUSTER_GAP,
            mangaOcrCropPaddingPx = MangaOcrAdvancedSettingsPolicy.CROP_PADDING_PX
            // runtimeTranslationContext is request-scoped and deliberately never persisted.
        ))
    }
}

internal data class MimoInstructionValues(
    val preset: String,
    val voiceDesign: String,
    val voiceClone: String,
)

internal fun resolveMimoInstructionValues(
    model: MimoTtsModel,
    legacy: String?,
    preset: String?,
    voiceDesign: String?,
    voiceClone: String?,
): MimoInstructionValues {
    if (preset != null || voiceDesign != null || voiceClone != null) {
        return MimoInstructionValues(
            preset = preset.orEmpty(),
            voiceDesign = voiceDesign.orEmpty(),
            voiceClone = voiceClone.orEmpty(),
        )
    }
    val oldValue = legacy.orEmpty()
    return MimoInstructionValues(
        preset = oldValue.takeIf { model == MimoTtsModel.PRESET }.orEmpty(),
        voiceDesign = oldValue.takeIf { model == MimoTtsModel.VOICE_DESIGN }.orEmpty(),
        voiceClone = oldValue.takeIf { model == MimoTtsModel.VOICE_CLONE }.orEmpty(),
    )
}
