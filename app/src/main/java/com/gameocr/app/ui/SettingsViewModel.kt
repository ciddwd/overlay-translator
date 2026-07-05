package com.gameocr.app.ui

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.gameocr.app.R
import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayFontEntry
import com.gameocr.app.data.OverlayFontImportResult
import com.gameocr.app.data.OverlayFontManager
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlmModelInstaller
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.ocr.OrientationModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import com.gameocr.app.ocr.lunaOcrHttpHostOrNull
import com.gameocr.app.ocr.umiOcrHttpHostOrNull
import com.gameocr.app.translate.RoutingTranslator
import com.gameocr.app.translate.TestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.collect

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: SettingsRepository,
    private val paddleInstaller: PaddleModelInstaller,
    private val mangaOcrInstaller: com.gameocr.app.ocr.MangaOcrModelInstaller,
    private val orientationModelInstaller: OrientationModelInstaller,
    private val routingTranslator: RoutingTranslator,
    private val llmInstaller: LlmModelInstaller,
    private val llamaEngineHolder: LlamaEngineHolder,
    private val overlayFontManager: OverlayFontManager
) : ViewModel() {

    suspend fun load(): Settings = repo.get()

    suspend fun importOverlayFont(uri: Uri): OverlayFontImportResult =
        overlayFontManager.importFont(uri)

    suspend fun resetOverlayFont() {
        overlayFontManager.resetFont()
    }

    suspend fun selectOverlayFont(fileName: String, displayName: String): Boolean =
        overlayFontManager.selectFont(fileName, displayName)

    suspend fun deleteOverlayFont(fileName: String): Boolean =
        overlayFontManager.deleteFont(fileName)

    fun overlayTypefaceFor(fileName: String): Typeface? =
        overlayFontManager.typefaceFor(fileName)

    @Suppress("LongParameterList")
    suspend fun save(
        baseUrl: String,
        apiKey: String,
        model: String,
        targetLang: String,
        sourceLang: String,
        prompt: String,
        textSize: Int,
        alpha: Float,
        loopMs: Long,
        streaming: Boolean,
        renderMode: RenderMode,
        placement: OverlayPlacement,
        overlayTheme: OverlayTheme,
        customBg: Int,
        customFg: Int,
        customBorder: Int,
        customBorderW: Int,
        offsetX: Int,
        offsetY: Int,
        ocrEngine: OcrEngineKind,
        baiduKey: String,
        baiduSecret: String,
        baiduEndpoint: com.gameocr.app.data.BaiduOcrEndpoint,
        baiduLanguage: com.gameocr.app.data.BaiduOcrLanguage,
        umiOcrBaseUrl: String,
        lunaOcrBaseUrl: String,
        tencentId: String,
        tencentKey: String,
        tencentRegion: String,
        tencentEndpoint: com.gameocr.app.data.TencentOcrEndpoint,
        tencentLanguage: com.gameocr.app.data.TencentOcrLanguage,
        preprocess: PreprocessOptions,
        a11yVolume: Boolean,
        floatingButtonSizeDp: Int,
        floatingButtonSnapToEdge: Boolean,
        floatingButtonAutoDock: Boolean,
        floatingButtonDockInsetDp: Int,
        allowWrap: Boolean,
        avoidCollision: Boolean,
        apiTimeoutSeconds: Int,
        mergeAdjacentBlocks: Boolean,
        mergeStrength: com.gameocr.app.data.MergeStrength,
        cleartextAllowedHosts: List<String>,
        translatorEngine: TranslatorEngine,
        deeplKey: String,
        deeplPro: Boolean,
        deeplProtocol: com.gameocr.app.data.DeeplProtocol,
        deeplBaseUrl: String,
        deeplBearerAuth: Boolean,
        deeplCustomToken: String,
        youdaoAppKey: String,
        youdaoAppSecret: String,
        volcAccessKeyId: String,
        volcSecretAccessKey: String,
        volcRegion: String,
        baiduFanyiAppId: String,
        baiduFanyiSecretKey: String,
        overlayFonts: List<OverlayFontEntry>,
        activeTranslationPresetId: String
    ) {
        repo.update {
            it.copy(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
                targetLang = targetLang.trim(),
                sourceLang = sourceLang.trim(),
                promptTemplate = prompt,
                overlayTextSizeSp = textSize.coerceIn(10, 28),
                overlayAlpha = alpha.coerceIn(0.3f, 1f),
                captureLoopIntervalMs = loopMs.coerceAtLeast(200),
                streamingTranslate = streaming,
                renderMode = renderMode,
                overlayPlacement = placement,
                overlayTheme = overlayTheme,
                customBgColor = customBg,
                customFgColor = customFg,
                customBorderColor = customBorder,
                customBorderWidth = customBorderW,
                overlayOffsetX = offsetX,
                overlayOffsetY = offsetY,
                ocrEngine = ocrEngine,
                baiduOcrApiKey = baiduKey.trim(),
                baiduOcrSecretKey = baiduSecret.trim(),
                baiduOcrEndpoint = baiduEndpoint,
                baiduOcrLanguage = baiduLanguage,
                umiOcrBaseUrl = umiOcrBaseUrl.trim(),
                lunaOcrBaseUrl = lunaOcrBaseUrl.trim(),
                tencentSecretId = tencentId.trim(),
                tencentSecretKey = tencentKey.trim(),
                tencentRegion = tencentRegion.trim().ifBlank { "ap-guangzhou" },
                tencentOcrEndpoint = tencentEndpoint,
                tencentOcrLanguage = tencentLanguage,
                preprocess = preprocess,
                a11yVolumeTrigger = a11yVolume,
                floatingButtonSizeDp = floatingButtonSizeDp.coerceIn(32, 96),
                floatingButtonSnapToEdge = floatingButtonSnapToEdge,
                floatingButtonAutoDock = floatingButtonAutoDock,
                floatingButtonDockInsetDp = floatingButtonDockInsetDp.coerceIn(0, 40),
                overlayAllowWrap = allowWrap,
                overlayAvoidCollision = avoidCollision,
                apiTimeoutSeconds = apiTimeoutSeconds.coerceIn(5, 300),
                mergeAdjacentBlocks = mergeAdjacentBlocks,
                mergeStrength = mergeStrength,
                cleartextAllowedHosts = cleartextHostsWithLocalOcrUrls(
                    cleartextAllowedHosts,
                    umiOcrBaseUrl,
                    lunaOcrBaseUrl
                ),
                translatorEngine = translatorEngine,
                deeplApiKey = deeplKey.trim(),
                deeplPro = deeplPro,
                deeplProtocol = deeplProtocol,
                deeplBaseUrl = deeplBaseUrl.trim(),
                deeplBearerAuth = deeplBearerAuth,
                deeplCustomToken = deeplCustomToken.trim(),
                youdaoAppKey = youdaoAppKey.trim(),
                youdaoAppSecret = youdaoAppSecret.trim(),
                volcAccessKeyId = volcAccessKeyId.trim(),
                volcSecretAccessKey = volcSecretAccessKey.trim(),
                volcRegion = volcRegion.trim().ifBlank { "cn-north-1" },
                baiduFanyiAppId = baiduFanyiAppId.trim(),
                baiduFanyiSecretKey = baiduFanyiSecretKey.trim(),
                overlayFonts = overlayFonts,
                activeTranslationPresetId = activeTranslationPresetId
            )
        }
    }

    suspend fun setActiveTranslationPreset(id: String) {
        repo.update { it.copy(activeTranslationPresetId = id) }
    }

    suspend fun savePaddleModelVersion(version: com.gameocr.app.data.PaddleModelVersion) {
        repo.update { it.copy(paddleModelVersion = version) }
    }

    /**
     * 单独保存「悬浮球吸附边缘」开关。切换时立即落盘 + 立即触发 CaptureService 响应，
     * 不走 [save] 的 dirty/save 流程——用户切了开关期望立即生效，不需要再点保存。
     */
    suspend fun saveFloatingSnapEdge(enabled: Boolean) {
        repo.update { it.copy(floatingButtonSnapToEdge = enabled) }
    }

    /** 悬浮窗口内容形态（原文+译文 / 仅译文）。立即落盘 + 即时生效，不走 [save] 流程。 */
    suspend fun saveFloatingWindowContentMode(mode: com.gameocr.app.data.FloatingWindowContentMode) {
        repo.update { it.copy(floatingWindowContentMode = mode) }
    }

    /** 悬浮窗口锁定开关：锁定后禁用拖拽 / resize。 */
    suspend fun saveFloatingWindowLocked(locked: Boolean) {
        repo.update { it.copy(floatingWindowLocked = locked) }
    }

    /** CUSTOM 主题的边框样式（SOLID / DASHED / DOTTED / DOUBLE / GROOVE），立即生效。 */
    suspend fun saveCustomBorderStyle(style: com.gameocr.app.data.BorderStyle) {
        repo.update { it.copy(customBorderStyle = style) }
    }

    /** 弧菜单按钮顺序：拖拽完即时落盘 + 生效，不走主 [save] 流程的 dirty 判定。 */
    suspend fun saveArcMenuOrder(order: List<com.gameocr.app.data.MenuItemId>) {
        repo.update { it.copy(floatingMenuItemOrder = order) }
    }

    suspend fun saveArcMenuPageSize(size: Int) {
        repo.update { it.copy(arcMenuPageSize = FloatingMenu.coercePageSize(size)) }
    }

    suspend fun createTranslationPresetFromCurrent(
        name: String,
        shortName: String
    ): TranslationPreset {
        var saved: TranslationPreset? = null
        repo.update { current ->
            val preset = TranslationPresetCatalog.fromSettings(
                id = "custom_${System.currentTimeMillis()}",
                name = name.trim().ifBlank { "Custom preset" },
                shortName = shortName.trim().ifBlank { name.trim().take(8).ifBlank { "Custom" } },
                settings = current
            )
            saved = preset
            current.copy(
                translationPresets = TranslationPresetCatalog.upsertCustom(
                    current.translationPresets,
                    preset
                ),
                activeTranslationPresetId = preset.id
            )
        }
        return saved ?: repo.get().translationPresets.last()
    }

    suspend fun duplicateTranslationPreset(
        id: String,
        name: String,
        shortName: String
    ): TranslationPreset? {
        var saved: TranslationPreset? = null
        repo.update { current ->
            val source = TranslationPresetCatalog.find(current.translationPresets, id)
                ?: return@update current
            val preset = source.copy(
                id = "custom_${System.currentTimeMillis()}",
                name = name.trim().ifBlank { "${source.name} Copy" },
                shortName = shortName.trim().ifBlank { source.shortName }
            )
            saved = preset
            current.copy(
                translationPresets = TranslationPresetCatalog.upsertCustom(
                    current.translationPresets,
                    preset
                )
            )
        }
        return saved
    }

    suspend fun saveTranslationPreset(preset: TranslationPreset): TranslationPreset {
        repo.update { current ->
            current.copy(
                translationPresets = TranslationPresetCatalog.upsertCustom(
                    current.translationPresets,
                    preset
                ),
                activeTranslationPresetId = preset.id
            )
        }
        return preset
    }

    suspend fun deleteTranslationPreset(id: String) {
        if (TranslationPresetCatalog.isBuiltIn(id)) return
        repo.update { current ->
            current.copy(
                translationPresets = current.translationPresets.filterNot { it.id == id },
                activeTranslationPresetId = current.activeTranslationPresetId.takeIf { it != id }.orEmpty()
            )
        }
    }

    suspend fun applyTranslationPreset(id: String): Settings? {
        var applied: Settings? = null
        repo.update { current ->
            val preset = TranslationPresetCatalog.find(current.translationPresets, id)
                ?: return@update current
            val next = preset.applyTo(current).copy(activeTranslationPresetId = preset.id)
            applied = next
            next
        }
        return applied
    }

    /** 划词翻译词典 Prompt（仅 OpenAI 兼容引擎用），即时落盘。 */
    suspend fun saveDictionaryPrompt(prompt: String) {
        repo.update { it.copy(dictionaryPrompt = prompt) }
    }

    /** 文本方向自动判别开关。立即落盘 + 即时生效，不走 [save] 流程的 dirty 判定。 */
    suspend fun saveTextOrientationAutoDetect(enabled: Boolean) {
        repo.update { it.copy(textOrientationAutoDetect = enabled) }
    }

    /** 手动锁定文本方向（null = 解除锁定，走自动判别）。 */
    suspend fun saveManualTextOrientation(orient: com.gameocr.app.ocr.TextOrientation?) {
        repo.update { it.copy(manualTextOrientation = orient) }
    }

    /** 重置悬浮窗口位置 / 大小到默认（X=Y=-1 居中，W/H 回默认）。 */
    suspend fun resetFloatingWindowGeometry() {
        repo.update {
            it.copy(
                floatingWindowX = -1,
                floatingWindowY = -1,
                floatingWindowWidthDp = 320,
                floatingWindowHeightDp = 180
            )
        }
    }

    /**
     * 用户切换 UI 语言后，如果当前 promptTemplate 仍是"上一个 locale 的默认 prompt"
     * （即用户从没改过），把它迁移到当前 locale 的默认。这样英文用户不会看到中文 prompt
     * 又苦于不知道该点"恢复默认"。已自定义的 prompt 不动。
     *
     * 用 [activityContext] 而不是 application context 取 [R.string.default_prompt]：
     * Activity context 的 Configuration 由 framework 保证跟 LocaleManager 同步，最稳。
     *
     * 返回当前应展示的 prompt（迁移后或原值）。
     */
    suspend fun migrateDefaultPromptIfStale(activityContext: Context): String {
        val current = repo.get().promptTemplate
        val currentDefault = activityContext.getString(R.string.default_prompt)
        if (current == currentDefault) return current

        // 列出所有已知 locale 下的 default_prompt；当前 prompt 命中任一即视为"未定制"
        val supportedTags = listOf("zh-CN", "en")
        val knownDefaults = supportedTags.map { tag ->
            val cfg = android.content.res.Configuration(activityContext.resources.configuration)
                .apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            activityContext.createConfigurationContext(cfg).getString(R.string.default_prompt)
        }
        if (current !in knownDefaults) return current

        repo.update { it.copy(promptTemplate = currentDefault) }
        return currentDefault
    }

    /**
     * 切换语言星标。已收藏则移除；未收藏则追加到末尾。立即落盘，绕过 SettingsScreen
     * 的 dirty 检测——星标是用户的小操作，不应该等"保存"按钮。
     */
    suspend fun togglePinLanguage(code: String) {
        repo.update { current ->
            val list = current.pinnedLanguages
            val next = if (list.contains(code)) list - code else list + code
            current.copy(pinnedLanguages = next)
        }
    }

    data class DownloadableModelUiState(
        val status: String,
        val ready: Boolean,
    )

    suspend fun paddleModelStatus(): String = paddleModelStatus(repo.get().paddleModelVersion)

    suspend fun paddleModelUiState(): DownloadableModelUiState =
        paddleModelUiState(repo.get().paddleModelVersion)

    fun paddleModelStatus(version: com.gameocr.app.data.PaddleModelVersion): String {
        return paddleModelUiState(version).status
    }

    fun paddleModelUiState(version: com.gameocr.app.data.PaddleModelVersion): DownloadableModelUiState {
        val files = paddleInstaller.checkInstalled(version)
        return if (files != null) {
            val total = (files.det.length() + files.rec.length() + files.keys.length()) / 1024
            DownloadableModelUiState(
                status = appContext.getString(R.string.settings_paddle_status_ready_format, total.toInt()),
                ready = true,
            )
        } else {
            DownloadableModelUiState(
                status = appContext.getString(R.string.settings_paddle_status_missing_hint),
                ready = false,
            )
        }
    }

    suspend fun downloadPaddleModels(onProgress: (String) -> Unit) {
        downloadPaddleModels(repo.get().paddleModelVersion, onProgress)
    }

    suspend fun downloadPaddleModels(version: com.gameocr.app.data.PaddleModelVersion, onProgress: (String) -> Unit) {
        paddleInstaller.downloadAll(version).collect { p ->
            val mirrorTag = p.mirror.substringAfter("//").substringBefore("/").take(24)
            val msg = when {
                p.error != null -> appContext.getString(
                    R.string.settings_paddle_progress_failed_format,
                    mirrorTag, p.file, p.error
                )
                p.done -> appContext.getString(
                    R.string.settings_paddle_progress_done_format,
                    p.file, (p.downloaded / 1024).toInt(), mirrorTag
                )
                p.total > 0 -> {
                    val pct = (p.downloaded * 100 / p.total).toInt()
                    appContext.getString(
                        R.string.settings_paddle_progress_format,
                        mirrorTag, p.file, pct,
                        (p.downloaded / 1024).toInt(), (p.total / 1024).toInt()
                    )
                }
                else -> appContext.getString(
                    R.string.settings_paddle_progress_simple_format,
                    mirrorTag, p.file, (p.downloaded / 1024).toInt()
                )
            }
            onProgress(msg)
        }
    }

    fun deletePaddleModels() {
        paddleInstaller.deleteAll()
    }

    suspend fun importPaddleFromLocal(uris: List<android.net.Uri>): Int =
        importPaddleFromLocal(repo.get().paddleModelVersion, uris)

    suspend fun importPaddleFromLocal(
        version: com.gameocr.app.data.PaddleModelVersion,
        uris: List<android.net.Uri>,
    ): Int = paddleInstaller.importFromLocal(uris, version)

    /** PaddleOCR 模型是否已就位。manga-ocr 复用 Paddle DBNet 做检测，下完 manga 后用它判断要不要级联拉 Paddle。 */
    suspend fun isPaddleInstalled(): Boolean = isPaddleInstalled(repo.get().paddleModelVersion)

    fun isPaddleInstalled(version: com.gameocr.app.data.PaddleModelVersion): Boolean =
        paddleModelUiState(version).ready

    // —— manga-ocr 端侧（日漫 OCR）——
    // 与 paddle 5 个方法严格对称。模型 ~160MB 比 paddle 大一个数量级，状态里用 MB 而非 KB。

    fun mangaOcrModelStatus(): String {
        return mangaOcrModelUiState().status
    }

    fun mangaOcrModelUiState(): DownloadableModelUiState {
        val files = mangaOcrInstaller.checkInstalled()
        return if (files != null) {
            val totalMb = (files.encoder.length() + files.decoder.length() +
                files.vocab.length() + files.config.length() + files.generationConfig.length() +
                files.preprocessorConfig.length() + files.specialTokensMap.length()) / (1024 * 1024)
            DownloadableModelUiState(
                status = appContext.getString(R.string.settings_manga_ocr_status_ready_format, totalMb.toInt()),
                ready = true,
            )
        } else {
            DownloadableModelUiState(
                status = appContext.getString(R.string.settings_manga_ocr_status_missing_hint),
                ready = false,
            )
        }
    }

    fun mangaOcrModelReady(): Boolean = mangaOcrModelUiState().ready

    suspend fun downloadMangaOcrModels(onProgress: (String) -> Unit) {
        mangaOcrInstaller.downloadAll().collect { p ->
            val mirrorTag = p.mirror.substringAfter("//").substringBefore("/").take(24)
            val msg = when {
                p.error != null -> appContext.getString(
                    R.string.settings_manga_ocr_progress_failed_format,
                    mirrorTag, p.file, p.error
                )
                p.done -> appContext.getString(
                    R.string.settings_manga_ocr_progress_done_format,
                    p.file, (p.downloaded / 1024).toInt(), mirrorTag
                )
                p.total > 0 -> {
                    val pct = (p.downloaded * 100 / p.total).toInt()
                    appContext.getString(
                        R.string.settings_manga_ocr_progress_format,
                        mirrorTag, p.file, pct,
                        (p.downloaded / 1024).toInt(), (p.total / 1024).toInt()
                    )
                }
                else -> appContext.getString(
                    R.string.settings_manga_ocr_progress_simple_format,
                    mirrorTag, p.file, (p.downloaded / 1024).toInt()
                )
            }
            onProgress(msg)
        }
    }

    fun deleteMangaOcrModels() {
        mangaOcrInstaller.deleteAll()
    }

    suspend fun importMangaOcrFromLocal(uris: List<android.net.Uri>): Int =
        mangaOcrInstaller.importFromLocal(uris)

    // —— Paddle doc-orientation ONNX model ——

    fun orientationModelStatus(): String {
        return orientationModelUiState().status
    }

    fun orientationModelUiState(): DownloadableModelUiState {
        val files = orientationModelInstaller.checkFullyInstalled()
        return if (files != null) {
            DownloadableModelUiState(
                status = appContext.getString(
                    R.string.settings_orientation_model_status_ready_format,
                    (files.totalBytes / 1024).toInt()
                ),
                ready = true,
            )
        } else {
            DownloadableModelUiState(
                status = appContext.getString(R.string.settings_orientation_model_status_missing_hint),
                ready = false,
            )
        }
    }

    fun orientationModelReady(): Boolean = orientationModelUiState().ready

    suspend fun downloadOrientationModel(onProgress: (String) -> Unit) {
        orientationModelInstaller.downloadAll().collect { p ->
            val mirrorTag = p.mirror.substringAfter("//").substringBefore("/").take(24)
            val msg = when {
                p.error != null -> appContext.getString(
                    R.string.settings_orientation_model_progress_failed_format,
                    mirrorTag, p.file, p.error
                )
                p.done -> appContext.getString(
                    R.string.settings_orientation_model_progress_done_format,
                    p.file, (p.downloaded / 1024).toInt(), mirrorTag
                )
                p.total > 0 -> {
                    val pct = (p.downloaded * 100 / p.total).toInt()
                    appContext.getString(
                        R.string.settings_orientation_model_progress_format,
                        mirrorTag, p.file, pct,
                        (p.downloaded / 1024).toInt(), (p.total / 1024).toInt()
                    )
                }
                else -> appContext.getString(
                    R.string.settings_orientation_model_progress_simple_format,
                    mirrorTag, p.file, (p.downloaded / 1024).toInt()
                )
            }
            onProgress(msg)
        }
    }

    fun deleteOrientationModel() {
        orientationModelInstaller.deleteAll()
    }

    suspend fun importOrientationModelFromLocal(uris: List<android.net.Uri>): Int =
        orientationModelInstaller.importFromLocal(uris)

    // —— 端侧 LLM 翻译 ——

    fun llmDeviceCapable(): Boolean = llamaEngineHolder.isDeviceCapable()

    data class LlmModelUiState(
        val status: String,
        val ready: Boolean,
    )

    fun llmModelUiState(kind: LlmModelKind): LlmModelUiState {
        val file = llmInstaller.checkInstalled(kind)
        return if (file != null) {
            val mb = (file.length() / 1024 / 1024).toInt()
            LlmModelUiState(
                status = appContext.getString(R.string.llm_status_ready, "${kind.displayName} · $mb"),
                ready = true,
            )
        } else {
            LlmModelUiState(
                status = appContext.getString(R.string.llm_status_missing),
                ready = false,
            )
        }
    }

    fun llmStatus(kind: LlmModelKind): String = llmModelUiState(kind).status

    fun llmModelReady(kind: LlmModelKind): Boolean = llmModelUiState(kind).ready

    suspend fun downloadLlmModel(kind: LlmModelKind, onProgress: (String) -> Unit) {
        llmInstaller.download(kind).collect { p ->
            val mirrorTag = p.mirror.take(24)
            val msg = when {
                p.error != null -> "${kind.displayName} @ $mirrorTag: ${p.error}"
                p.done -> appContext.getString(R.string.llm_status_ready,
                    "${kind.displayName} · ${(p.downloaded / 1024 / 1024).toInt()}")
                else -> appContext.getString(
                    R.string.llm_status_downloading_format,
                    mirrorTag,
                    (p.downloaded / 1024 / 1024).toInt(),
                    if (p.total > 0) (p.total / 1024 / 1024).toInt() else kind.approxSizeMb
                )
            }
            onProgress(msg)
        }
    }

    fun deleteLlmModel(kind: LlmModelKind): Boolean = llmInstaller.delete(kind)

    suspend fun saveLlmMirror(choice: com.gameocr.app.data.LlmMirrorChoice, customUrl: String) {
        repo.update { it.copy(localLlmMirror = choice, localLlmMirrorUrl = customUrl.trim()) }
    }

    /** DBNet/聚类阈值即时落盘；prob/score/gap 共用，unclip 按 Paddle/MangaOCR 分开。 */
    suspend fun saveDbnetThresholds(
        ocrEngine: OcrEngineKind,
        prob: Float,
        score: Float,
        unclip: Float,
        gap: Int,
    ) {
        repo.update {
            val clampedUnclip = unclip.coerceIn(1.2f, 2.6f)
            it.copy(
                dbnetProbThresh = prob.coerceIn(0.05f, 0.5f),
                dbnetBoxScoreThresh = score.coerceIn(0.2f, 0.8f),
                dbnetUnclipRatio = if (ocrEngine == OcrEngineKind.MANGA_OCR_JA) {
                    it.dbnetUnclipRatio
                } else {
                    clampedUnclip
                },
                mangaOcrDbnetUnclipRatio = if (ocrEngine == OcrEngineKind.MANGA_OCR_JA) {
                    clampedUnclip
                } else {
                    it.mangaOcrDbnetUnclipRatio
                },
                bubbleClusterGap = gap.coerceIn(8, 80),
            )
        }
    }

    suspend fun importLlmFromLocal(
        uris: List<android.net.Uri>,
        defaultKind: LlmModelKind,
    ): Int = llmInstaller.importFromLocal(uris, defaultKind)

    /**
     * 测试当前 UI 上未保存的翻译引擎配置是否可用。基于已存档的 Settings，把用户在设置页
     * 改但未保存的几个字段（baseUrl/key/model/deeplKey/deeplPro/engine/timeout）覆盖进去，
     * 避免要求用户必须先点"保存"才能测。
     */
    suspend fun testTranslator(
        translatorEngine: TranslatorEngine,
        baseUrl: String,
        apiKey: String,
        model: String,
        deeplKey: String,
        deeplPro: Boolean,
        deeplProtocol: com.gameocr.app.data.DeeplProtocol,
        deeplBaseUrl: String,
        deeplBearerAuth: Boolean,
        deeplCustomToken: String,
        youdaoAppKey: String,
        youdaoAppSecret: String,
        apiTimeoutSeconds: Int,
        volcAccessKeyId: String = "",
        volcSecretAccessKey: String = "",
        volcRegion: String = "cn-north-1",
        baiduFanyiAppId: String = "",
        baiduFanyiSecretKey: String = "",
        tencentSecretId: String = "",
        tencentSecretKey: String = "",
        tencentRegion: String = ""
    ): TestResult {
        val base = repo.get()
        val temp = base.copy(
            translatorEngine = translatorEngine,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
            deeplApiKey = deeplKey.trim(),
            deeplPro = deeplPro,
            deeplProtocol = deeplProtocol,
            deeplBaseUrl = deeplBaseUrl.trim(),
            deeplBearerAuth = deeplBearerAuth,
            deeplCustomToken = deeplCustomToken.trim(),
            youdaoAppKey = youdaoAppKey.trim(),
            youdaoAppSecret = youdaoAppSecret.trim(),
            volcAccessKeyId = volcAccessKeyId.trim().ifBlank { base.volcAccessKeyId },
            volcSecretAccessKey = volcSecretAccessKey.trim().ifBlank { base.volcSecretAccessKey },
            volcRegion = volcRegion.trim().ifBlank { base.volcRegion },
            baiduFanyiAppId = baiduFanyiAppId.trim().ifBlank { base.baiduFanyiAppId },
            baiduFanyiSecretKey = baiduFanyiSecretKey.trim().ifBlank { base.baiduFanyiSecretKey },
            tencentSecretId = tencentSecretId.trim().ifBlank { base.tencentSecretId },
            tencentSecretKey = tencentSecretKey.trim().ifBlank { base.tencentSecretKey },
            tencentRegion = tencentRegion.trim().ifBlank { base.tencentRegion },
            apiTimeoutSeconds = apiTimeoutSeconds.coerceIn(5, 300)
        )
        return routingTranslator.testConnection(temp)
    }
}

internal fun cleartextHostsWithLocalOcrUrls(
    hosts: List<String>,
    umiOcrBaseUrl: String,
    lunaOcrBaseUrl: String,
): List<String> {
    val normalized = hosts.map { it.trim() }.filter { it.isNotEmpty() }
    val umiHost = umiOcrHttpHostOrNull(umiOcrBaseUrl)
    val lunaHost = lunaOcrHttpHostOrNull(lunaOcrBaseUrl)
    return (normalized + listOfNotNull(umiHost, lunaHost))
        .distinctBy { it.lowercase() }
}
