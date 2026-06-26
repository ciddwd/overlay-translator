package com.gameocr.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.ocr.PaddleModelInstaller
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
    private val routingTranslator: RoutingTranslator
) : ViewModel() {

    suspend fun load(): Settings = repo.get()

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
        tencentId: String,
        tencentKey: String,
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
        paddleMirror: String,
        youdaoAppKey: String,
        youdaoAppSecret: String
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
                tencentSecretId = tencentId.trim(),
                tencentSecretKey = tencentKey.trim(),
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
                cleartextAllowedHosts = cleartextAllowedHosts.map { it.trim() }.filter { it.isNotEmpty() },
                translatorEngine = translatorEngine,
                deeplApiKey = deeplKey.trim(),
                deeplPro = deeplPro,
                deeplProtocol = deeplProtocol,
                deeplBaseUrl = deeplBaseUrl.trim(),
                deeplBearerAuth = deeplBearerAuth,
                deeplCustomToken = deeplCustomToken.trim(),
                paddleModelMirrorUrl = paddleMirror.trim(),
                youdaoAppKey = youdaoAppKey.trim(),
                youdaoAppSecret = youdaoAppSecret.trim()
            )
        }
    }

    suspend fun savePaddleMirror(url: String) {
        repo.update { it.copy(paddleModelMirrorUrl = url.trim()) }
    }

    /**
     * 单独保存「悬浮球吸附边缘」开关。切换时立即落盘 + 立即触发 CaptureService 响应，
     * 不走 [save] 的 dirty/save 流程——用户切了开关期望立即生效，不需要再点保存。
     */
    suspend fun saveFloatingSnapEdge(enabled: Boolean) {
        repo.update { it.copy(floatingButtonSnapToEdge = enabled) }
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

    fun paddleModelStatus(): String {
        val files = paddleInstaller.checkInstalled()
        return if (files != null) {
            val total = (files.det.length() + files.rec.length() + files.keys.length()) / 1024
            appContext.getString(R.string.settings_paddle_status_ready_format, total.toInt())
        } else {
            appContext.getString(R.string.settings_paddle_status_missing_hint)
        }
    }

    suspend fun downloadPaddleModels(onProgress: (String) -> Unit) {
        paddleInstaller.downloadAll().collect { p ->
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
        paddleInstaller.importFromLocal(uris)

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
        apiTimeoutSeconds: Int
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
            apiTimeoutSeconds = apiTimeoutSeconds.coerceIn(5, 300)
        )
        return routingTranslator.testConnection(temp)
    }
}
