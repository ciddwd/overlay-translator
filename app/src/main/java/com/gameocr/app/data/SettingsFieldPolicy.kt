package com.gameocr.app.data

import androidx.annotation.StringRes
import com.gameocr.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

enum class SettingsPersistence {
    DATA_STORE,
    RUNTIME_ONLY,
}

enum class SettingsPortability {
    PORTABLE,
    DEVICE_LOCAL,
    CREDENTIAL,
    PRIVATE_CONNECTION,
    RUNTIME_ONLY,
}

enum class SettingsDiagnostic {
    VALUE,
    SUMMARY,
    MASKED,
    OMITTED,
}

data class SettingsFieldRule(
    val name: String,
    val persistence: SettingsPersistence = SettingsPersistence.DATA_STORE,
    val portability: SettingsPortability = SettingsPortability.PORTABLE,
    val diagnostic: SettingsDiagnostic = SettingsDiagnostic.VALUE,
    val searchEntryId: String? = null,
)

data class SettingsFieldDecodeResult(
    val settings: Settings,
    val skippedFields: List<String>,
)

internal fun settingsSearchEntryId(@StringRes itemLabelRes: Int): String = "res:$itemLabelRes"

/**
 * Single classification table for every [Settings] constructor property.
 *
 * Export, import preservation, crash diagnostics, and search coverage consume this table so a new
 * setting cannot silently fall through a blacklist or one of several unrelated audit lists.
 */
object SettingsFieldPolicy {
    private fun portable(
        name: String,
        @StringRes searchLabel: Int? = null,
        diagnostic: SettingsDiagnostic = SettingsDiagnostic.VALUE,
    ) = SettingsFieldRule(
        name = name,
        diagnostic = diagnostic,
        searchEntryId = searchLabel?.let(::settingsSearchEntryId),
    )

    private fun deviceLocal(
        name: String,
        @StringRes searchLabel: Int? = null,
        diagnostic: SettingsDiagnostic = SettingsDiagnostic.VALUE,
    ) = SettingsFieldRule(
        name = name,
        portability = SettingsPortability.DEVICE_LOCAL,
        diagnostic = diagnostic,
        searchEntryId = searchLabel?.let(::settingsSearchEntryId),
    )

    private fun credential(name: String, @StringRes searchLabel: Int? = null) = SettingsFieldRule(
        name = name,
        portability = SettingsPortability.CREDENTIAL,
        diagnostic = SettingsDiagnostic.MASKED,
        searchEntryId = searchLabel?.let(::settingsSearchEntryId),
    )

    private fun privateConnection(name: String, @StringRes searchLabel: Int? = null) = SettingsFieldRule(
        name = name,
        portability = SettingsPortability.PRIVATE_CONNECTION,
        diagnostic = SettingsDiagnostic.SUMMARY,
        searchEntryId = searchLabel?.let(::settingsSearchEntryId),
    )

    val rules: List<SettingsFieldRule> = listOf(
        privateConnection("baseUrl", R.string.settings_search_item_base_url),
        credential("apiKey", R.string.settings_search_item_api_key),
        portable("model", R.string.settings_search_item_model_name),
        portable("sourceLang", R.string.settings_search_item_source_lang),
        portable("targetLang", R.string.settings_search_item_target_lang),
        portable("promptTemplate", R.string.settings_search_item_prompt, SettingsDiagnostic.SUMMARY),
        portable("ocrEngine", R.string.settings_search_item_ocr_switch),
        portable("captureLoopIntervalMs", R.string.settings_search_item_loop_interval),
        portable("loopTriggerMode", R.string.settings_search_item_loop_trigger_mode),
        portable("loopTextStableDurationMs", R.string.settings_search_item_loop_trigger_mode),
        portable("loopSkipSimilarFrames", R.string.settings_search_item_loop_similarity),
        portable("loopFrameSimilarityThreshold", R.string.settings_search_item_loop_similarity),
        portable("loopTextRegionMode", R.string.settings_search_item_loop_region),
        portable("loopTranslateRegionOnly", R.string.settings_search_item_loop_region),
        portable("developerOptionsEnabled", R.string.settings_search_item_developer_ocr),
        portable("ocrScreenshotSavingEnabled", R.string.settings_search_item_developer_ocr),
        portable("disableTranslationCache", R.string.settings_search_item_developer_ocr),
        portable("disableCrossLineContextTranslation", R.string.settings_search_item_cross_line_context),
        portable("ocrRedBoxModeEnabled", R.string.settings_search_item_developer_ocr),
        portable("ocrRedBoxShowSourceText", R.string.settings_search_item_developer_ocr),
        portable("ocrRedBoxShowTranslation", R.string.settings_search_item_developer_ocr),
        deviceLocal("captureRegion", diagnostic = SettingsDiagnostic.SUMMARY),
        deviceLocal("captureRegionSavedScreenW"),
        deviceLocal("captureRegionSavedScreenH"),
        portable("overlayStyleMode", R.string.settings_search_item_overlay_theme),
        portable("overlayTextSizeSp", R.string.settings_search_item_text_size),
        portable("overlayTextStyle", R.string.settings_search_item_text_style),
        portable("overlayAlpha", R.string.settings_search_item_alpha),
        portable("overlayFontFileName", R.string.settings_search_item_overlay_font, SettingsDiagnostic.SUMMARY),
        portable("overlayFontDisplayName", R.string.settings_search_item_overlay_font, SettingsDiagnostic.SUMMARY),
        portable("overlayFonts", R.string.settings_search_item_overlay_font, SettingsDiagnostic.SUMMARY),
        portable("streamingTranslate", R.string.settings_search_item_streaming),
        portable("retryEmptyTranslation", R.string.settings_search_item_empty_translation_retry),
        portable("ttsEnabled", R.string.settings_search_item_tts_enable),
        portable("ttsProvider", R.string.settings_search_item_tts_provider),
        portable("ttsVoice", R.string.settings_search_item_tts_provider),
        portable("ttsEmotion", R.string.settings_search_item_tts_provider),
        portable("ttsSpeed", R.string.settings_search_item_tts_provider),
        portable("ttsPitch", R.string.settings_search_item_tts_provider),
        portable("ttsGainDb", R.string.settings_search_item_tts_provider),
        privateConnection("ttsHttpBaseUrl", R.string.settings_search_item_tts_http),
        credential("ttsHttpBearerToken", R.string.settings_search_item_tts_http),
        portable("ttsHttpResponseMode", R.string.settings_search_item_tts_http),
        portable("ttsVolcengineResource", R.string.settings_search_item_tts_provider),
        privateConnection("ttsVolcengineBaseUrl", R.string.settings_search_item_tts_provider),
        credential("ttsVolcengineApiKey", R.string.settings_search_item_tts_provider),
        portable("ttsVolcengineSpeaker", R.string.settings_search_item_tts_provider),
        portable("ttsVolcengineModel", R.string.settings_search_item_tts_provider),
        portable(
            "ttsVolcengineContext",
            R.string.settings_search_item_tts_provider,
            SettingsDiagnostic.SUMMARY,
        ),
        portable("ttsVolcenginePitch", R.string.settings_search_item_tts_provider),
        portable("ttsVolcengineToneFidelity", R.string.settings_search_item_tts_provider),
        portable("ttsMiniMaxModel", R.string.settings_search_item_tts_provider),
        privateConnection("ttsMiniMaxBaseUrl", R.string.settings_search_item_tts_provider),
        credential("ttsMiniMaxApiKey", R.string.settings_search_item_tts_provider),
        portable("ttsMiniMaxVoice", R.string.settings_search_item_tts_provider),
        portable("ttsMiniMaxEmotion", R.string.settings_search_item_tts_provider),
        portable("ttsMiniMaxSpeed", R.string.settings_search_item_tts_provider),
        portable("ttsMiniMaxPitch", R.string.settings_search_item_tts_provider),
        portable("ttsMimoModel", R.string.settings_search_item_tts_provider),
        privateConnection("ttsMimoBaseUrl", R.string.settings_search_item_tts_provider),
        credential("ttsMimoApiKey", R.string.settings_search_item_tts_provider),
        portable("ttsMimoVoice", R.string.settings_search_item_tts_provider),
        portable(
            "ttsMimoInstruction",
            R.string.settings_search_item_tts_provider,
            SettingsDiagnostic.SUMMARY,
        ),
        portable(
            "ttsMimoVoiceDesignPrompt",
            R.string.settings_search_item_tts_provider,
            SettingsDiagnostic.SUMMARY,
        ),
        portable(
            "ttsMimoVoiceCloneInstruction",
            R.string.settings_search_item_tts_provider,
            SettingsDiagnostic.SUMMARY,
        ),
        deviceLocal(
            "ttsMimoVoiceSampleUri",
            R.string.settings_search_item_tts_provider,
            SettingsDiagnostic.OMITTED,
        ),
        portable("renderMode", R.string.settings_search_item_render_mode),
        portable("translationBlockInteractionMode", R.string.settings_search_item_translation_block_interaction),
        portable("overlayPlacement", R.string.settings_search_item_placement),
        portable("overlayTheme", R.string.settings_search_item_overlay_theme),
        portable("customBgColor", R.string.settings_search_item_custom_theme),
        portable("customFgColor", R.string.settings_search_item_custom_theme),
        portable("customBorderColor", R.string.settings_search_item_custom_theme),
        portable("customBorderWidth", R.string.settings_search_item_custom_theme),
        portable("overlayOffsetX", R.string.settings_search_item_offset),
        portable("overlayOffsetY", R.string.settings_search_item_offset),
        portable("preprocess", R.string.settings_search_item_upscale),
        portable("textOrientationAutoDetect", R.string.settings_orient_auto_detect_title),
        portable("manualTextOrientation", R.string.settings_search_item_manual_orientation),
        portable("translationOutputFollowRecognition", R.string.settings_translation_output_follow_title),
        portable("translationOutputLayout", R.string.settings_translation_output_layout_label),
        portable("translationOutputDirection", R.string.settings_translation_output_layout_label),
        credential("baiduOcrApiKey", R.string.settings_search_item_baidu_api_key),
        credential("baiduOcrSecretKey", R.string.settings_search_item_baidu_api_key),
        portable("baiduOcrEndpoint", R.string.settings_search_item_baidu_endpoint),
        portable("baiduOcrLanguage", R.string.settings_search_item_baidu_lang),
        privateConnection("umiOcrBaseUrl", R.string.settings_search_item_umi_ocr),
        privateConnection("lunaOcrBaseUrl", R.string.settings_search_item_luna_ocr),
        credential("paddleAiStudioToken", R.string.settings_search_item_paddle_ai_studio),
        credential("tencentSecretId", R.string.settings_search_item_tencent_secret),
        credential("tencentSecretKey", R.string.settings_search_item_tencent_secret),
        portable("tencentRegion", R.string.settings_search_item_tencent_region),
        portable("tencentOcrEndpoint", R.string.settings_search_item_tencent_endpoint),
        portable("tencentOcrLanguage", R.string.settings_search_item_tencent_lang),
        portable("paddleModelVersion", R.string.settings_search_item_paddle_download),
        portable("paddleDetectionProfile", R.string.settings_search_item_dbnet_advanced),
        privateConnection("paddleModelMirrorUrl", R.string.settings_search_item_paddle_download),
        privateConnection("mangaOcrModelMirrorUrl", R.string.settings_search_item_manga_ocr_download),
        privateConnection("orientationModelMirrorUrl", R.string.settings_search_item_orientation_model),
        deviceLocal("preferShizukuCapture"),
        portable("a11yVolumeTrigger", R.string.settings_search_item_a11y_volume),
        portable("translatorEngine", R.string.settings_search_item_translator_engine),
        portable("translationGlossaryEnabled", R.string.settings_glossary_enabled),
        portable("foregroundAppDetectionMode", R.string.settings_foreground_app_detection),
        portable("sendAppNameToTranslator", R.string.settings_send_app_name),
        credential("deeplApiKey", R.string.settings_search_item_deepl_api_key),
        portable("deeplPro", R.string.settings_search_item_deepl_pro),
        portable("deeplProtocol", R.string.settings_search_item_deepl_advanced),
        privateConnection("deeplBaseUrl", R.string.settings_search_item_deepl_advanced),
        portable("deeplBearerAuth", R.string.settings_search_item_deepl_advanced),
        credential("deeplCustomToken", R.string.settings_search_item_deepl_advanced),
        credential("youdaoAppKey", R.string.settings_search_item_youdao_pictrans),
        credential("youdaoAppSecret", R.string.settings_search_item_youdao_pictrans),
        credential("volcAccessKeyId", R.string.settings_search_item_volc),
        credential("volcSecretAccessKey", R.string.settings_search_item_volc),
        portable("volcRegion", R.string.settings_search_item_volc),
        credential("baiduFanyiAppId", R.string.settings_search_item_baidu_fanyi),
        credential("baiduFanyiSecretKey", R.string.settings_search_item_baidu_fanyi),
        portable("floatingButtonSizeDp", R.string.settings_search_item_floating_size),
        deviceLocal("floatingButtonX"),
        deviceLocal("floatingButtonY"),
        portable("floatingButtonSnapToEdge", R.string.settings_search_item_floating_snap),
        portable("floatingButtonAutoDock", R.string.settings_search_item_floating_auto_dock),
        portable("floatingButtonDockInsetDp", R.string.settings_search_item_floating_dock_inset),
        deviceLocal("floatingWindowX", R.string.settings_search_item_floating_window_reset),
        deviceLocal("floatingWindowY", R.string.settings_search_item_floating_window_reset),
        deviceLocal("floatingWindowWidthDp", R.string.settings_search_item_floating_window_reset),
        deviceLocal("floatingWindowHeightDp", R.string.settings_search_item_floating_window_reset),
        portable("floatingWindowContentMode", R.string.settings_search_item_floating_window_content),
        portable("floatingWindowLocked", R.string.settings_search_item_floating_window_locked),
        portable("customBorderStyle", R.string.settings_search_item_border_style),
        portable("overlayAllowWrap", R.string.settings_search_item_allow_wrap),
        portable("overlayAvoidCollision", R.string.settings_search_item_avoid_collision),
        portable("apiTimeoutSeconds", R.string.settings_search_item_api_timeout),
        portable("mergeAdjacentBlocks", R.string.settings_search_item_merge_adjacent),
        portable("mergeStrength", R.string.settings_search_item_merge_strength),
        portable("pinnedLanguages", R.string.settings_search_item_source_lang, SettingsDiagnostic.SUMMARY),
        deviceLocal(
            "mlKitRecentSourceLanguages",
            R.string.settings_search_item_source_lang,
            SettingsDiagnostic.SUMMARY,
        ),
        privateConnection("cleartextAllowedHosts", R.string.settings_search_item_cleartext_hosts),
        portable("floatingMenuItemOrder", R.string.settings_search_item_arc_menu_order, SettingsDiagnostic.SUMMARY),
        portable("arcMenuPageSize", R.string.settings_search_item_arc_menu_order),
        portable("floatingButtonSkill", R.string.settings_search_item_arc_menu_order),
        portable("dictionaryPrompt", R.string.settings_search_item_dictionary_prompt, SettingsDiagnostic.SUMMARY),
        portable("localLlmTemperature", R.string.settings_search_item_local_llm_model),
        portable("localLlmTopP", R.string.settings_search_item_local_llm_model),
        portable("localLlmTopK", R.string.settings_search_item_local_llm_model),
        portable("localLlmRepetitionPenalty", R.string.settings_search_item_local_llm_model),
        portable("localLlmContextSize", R.string.settings_search_item_local_llm_model),
        portable("localLlmMaxNewTokens", R.string.settings_search_item_local_llm_model),
        portable("dbnetProbThresh", R.string.settings_search_item_dbnet_advanced),
        portable("dbnetBoxScoreThresh", R.string.settings_search_item_dbnet_advanced),
        portable("dbnetUnclipRatio", R.string.settings_search_item_dbnet_advanced),
        portable("mangaOcrDbnetUnclipRatio", R.string.settings_search_item_dbnet_advanced),
        portable("bubbleClusterGap"),
        portable("mangaOcrCropPaddingPx"),
        portable("localLlmMirror", R.string.settings_search_item_llm_mirror),
        privateConnection("localLlmMirrorUrl", R.string.settings_search_item_llm_mirror),
        portable("translationPresets", R.string.settings_section_translation_presets, SettingsDiagnostic.SUMMARY),
        portable("activeTranslationPresetId", R.string.settings_section_translation_presets, SettingsDiagnostic.SUMMARY),
        SettingsFieldRule(
            name = "runtimeTranslationContext",
            persistence = SettingsPersistence.RUNTIME_ONLY,
            portability = SettingsPortability.RUNTIME_ONLY,
            diagnostic = SettingsDiagnostic.OMITTED,
        ),
    )

    init {
        require(rules.map(SettingsFieldRule::name).distinct().size == rules.size) {
            "Settings field policy contains duplicate names."
        }
    }

    val portableFieldNames: Set<String> = rules
        .filter { it.portability == SettingsPortability.PORTABLE }
        .mapTo(linkedSetOf(), SettingsFieldRule::name)

    val protectedFieldNames: Set<String> = rules
        .filterNot { it.portability == SettingsPortability.PORTABLE }
        .mapTo(linkedSetOf(), SettingsFieldRule::name)

    val searchEntryIds: Set<String> = rules.mapNotNullTo(linkedSetOf(), SettingsFieldRule::searchEntryId)

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encodePortable(settings: Settings): JsonObject {
        val encoded = json.encodeToJsonElement(
            MangaOcrAdvancedSettingsPolicy.normalize(settings)
        ).asObject()
        return JsonObject(encoded.filterKeys(portableFieldNames::contains))
    }

    fun decodePortable(values: JsonObject): SettingsFieldDecodeResult {
        val defaults = json.encodeToJsonElement(Settings()).asObject().toMutableMap()
        val skipped = mutableListOf<String>()
        values.forEach { (name, value) ->
            if (name !in portableFieldNames) return@forEach
            val candidate = JsonObject(defaults + (name to value))
            if (runCatching { json.decodeFromJsonElement<Settings>(candidate) }.isSuccess) {
                defaults[name] = value
            } else {
                skipped += name
            }
        }
        return SettingsFieldDecodeResult(
            settings = MangaOcrAdvancedSettingsPolicy.normalize(
                json.decodeFromJsonElement<Settings>(JsonObject(defaults))
            ),
            skippedFields = skipped.sorted(),
        )
    }

    fun applyPortable(current: Settings, imported: Settings): Settings {
        val currentValues = json.encodeToJsonElement(current).asObject()
        val importedValues = json.encodeToJsonElement(imported).asObject()
        val merged = currentValues.toMutableMap()
        portableFieldNames.forEach { name -> importedValues[name]?.let { merged[name] = it } }
        return MangaOcrAdvancedSettingsPolicy.normalize(
            json.decodeFromJsonElement<Settings>(JsonObject(merged)).copy(
                runtimeTranslationContext = current.runtimeTranslationContext,
            )
        )
    }

    fun formatDiagnostics(settings: Settings): String {
        val encoded = json.encodeToJsonElement(
            MangaOcrAdvancedSettingsPolicy.normalize(settings)
        ).asObject()
        return buildString {
            rules.forEach { rule ->
                append("  ").append(rule.name).append(": ")
                append(diagnosticValue(rule, encoded[rule.name]))
                append('\n')
            }
        }
    }

    private fun diagnosticValue(rule: SettingsFieldRule, value: JsonElement?): String = when (rule.diagnostic) {
        SettingsDiagnostic.OMITTED -> "<omitted>"
        SettingsDiagnostic.MASKED -> if (value.isBlankValue()) "<empty>" else "<set>"
        SettingsDiagnostic.SUMMARY -> when (value) {
            null, JsonNull -> "<empty>"
            is JsonArray -> "${value.size} item(s)"
            is JsonObject -> if (value.isEmpty()) "<empty>" else "<set>"
            is JsonPrimitive -> when {
                value.isString -> if (value.content.isBlank()) "<empty>" else "<configured; ${value.content.length} chars>"
                value.booleanOrNull != null -> value.content
                else -> value.content
            }
        }
        SettingsDiagnostic.VALUE -> when (value) {
            null, JsonNull -> "<none>"
            is JsonArray -> "${value.size} item(s)"
            is JsonObject -> if (value.isEmpty()) "<empty>" else value.toString().take(200)
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
        }
    }

    private fun JsonElement?.isBlankValue(): Boolean = when (this) {
        null, JsonNull -> true
        is JsonPrimitive -> isString && content.isBlank()
        is JsonArray -> isEmpty()
        is JsonObject -> isEmpty()
    }

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject
        ?: error("Serialized settings must be a JSON object.")
}
