package com.gameocr.app.ui

import android.content.Intent
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import timber.log.Timber
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.HelpOutline
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import com.gameocr.app.overlay.EdgeInsetPreviewOverlay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gameocr.app.R
import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.Languages
import com.gameocr.app.data.MenuItemId
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayFontImportError
import com.gameocr.app.data.OverlayFontImportResult
import com.gameocr.app.data.OverlayFontPolicy
import com.gameocr.app.data.OverlayFontEntry
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.llm.LlmModelKind
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var targetLang by remember { mutableStateOf("zh-CN") }
    var sourceLang by remember { mutableStateOf("auto") }
    var translatorEngine by remember { mutableStateOf(TranslatorEngine.OPENAI) }
    // 端侧 LLM 翻译：状态文本（"已就绪 · XX MB" / "未下载" / "下载中 …"）。仅在 LOCAL_* 引擎时显示。
    var llmModelStatus by remember { mutableStateOf("") }
    var llmDownloading by remember { mutableStateOf(false) }
    var llmModelReady by remember { mutableStateOf(false) }
    // 镜像选择：radio（HF / hf-mirror / 自定义）；仅 CUSTOM 时 llmMirror 才作为 base URL。
    var llmMirrorChoice by remember { mutableStateOf(com.gameocr.app.data.LlmMirrorChoice.HF_MIRROR) }
    var llmMirror by remember { mutableStateOf("") }
    var deeplKey by remember { mutableStateOf("") }
    var deeplPro by remember { mutableStateOf(false) }
    var deeplBaseUrl by remember { mutableStateOf("") }
    var deeplBearerAuth by remember { mutableStateOf(false) }
    var deeplCustomToken by remember { mutableStateOf("") }
    var deeplProtocol by remember { mutableStateOf(com.gameocr.app.data.DeeplProtocol.OFFICIAL) }
    var deeplAdvancedExpanded by remember { mutableStateOf(false) }
    // 有道智云一套 key（OCR + 图片翻译共用）
    var youdaoAppKey by remember { mutableStateOf("") }
    var youdaoAppSecret by remember { mutableStateOf("") }
    // 火山引擎机器翻译 AK/SK + region（SignV4）
    var volcAk by remember { mutableStateOf("") }
    var volcSk by remember { mutableStateOf("") }
    var volcRegion by remember { mutableStateOf("cn-north-1") }
    // 百度翻译开放平台 APPID + 密钥（与百度智能云 OCR 完全不是一回事）
    var baiduFanyiAppId by remember { mutableStateOf("") }
    var baiduFanyiSecret by remember { mutableStateOf("") }
    // 翻译引擎"测试连接"按钮的瞬时状态：testing / 结果文字 / 成功色 / OpenAI 拉到的 model 列表。
    // 不进 Settings，纯 UI 状态；切换 engine 不清空（用户切回去还能看到上次的结果）。
    var testRunning by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelPickerExpanded by remember { mutableStateOf(false) }
    var textSize by remember { mutableStateOf(14f) }
    var alpha by remember { mutableStateOf(0.85f) }
    var overlayFontFileName by remember { mutableStateOf("") }
    var overlayFontDisplayName by remember { mutableStateOf("") }
    var overlayFontEntries by remember { mutableStateOf<List<OverlayFontEntry>>(emptyList()) }
    var overlayFontTypeface by remember { mutableStateOf<android.graphics.Typeface?>(null) }
    var overlayFontMessage by remember { mutableStateOf<String?>(null) }
    var overlayFontMessageIsError by remember { mutableStateOf(false) }
    var pendingOverlayFontDelete by remember { mutableStateOf<OverlayFontEntry?>(null) }
    var showOverlayFontDeleteTip by remember { mutableStateOf(false) }
    var overlayFontDeleteTipCountdown by remember { mutableStateOf(0) }
    var loopInterval by remember { mutableStateOf("1000") }
    var streaming by remember { mutableStateOf(true) }
    var renderMode by remember { mutableStateOf(RenderMode.BLOCKS) }
    var floatingWindowContentMode by remember {
        mutableStateOf(com.gameocr.app.data.FloatingWindowContentMode.SRC_AND_DST)
    }
    var floatingWindowLocked by remember { mutableStateOf(false) }
    var customBorderStyle by remember {
        mutableStateOf(com.gameocr.app.data.BorderStyle.SOLID)
    }
    var placement by remember { mutableStateOf(OverlayPlacement.BELOW) }
    var overlayTheme by remember { mutableStateOf(OverlayTheme.CLASSIC_DARK) }
    var customBg by remember { mutableStateOf(0xE6000000.toInt()) }
    var customFg by remember { mutableStateOf(0xFFFFFFFF.toInt()) }
    var customBorder by remember { mutableStateOf(0) }
    var customBorderW by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var ocrEngine by remember { mutableStateOf(OcrEngineKind.ML_KIT_AUTO) }
    var baiduKey by remember { mutableStateOf("") }
    var baiduSecret by remember { mutableStateOf("") }
    var baiduEndpoint by remember { mutableStateOf(com.gameocr.app.data.BaiduOcrEndpoint.GENERAL) }
    var baiduLanguage by remember { mutableStateOf(com.gameocr.app.data.BaiduOcrLanguage.CHN_ENG) }
    var umiOcrBaseUrl by remember { mutableStateOf("") }
    var lunaOcrBaseUrl by remember { mutableStateOf("") }
    var paddleAiStudioToken by remember { mutableStateOf("") }
    var tencentId by remember { mutableStateOf("") }
    var tencentKey by remember { mutableStateOf("") }
    // Region 同时被 OCR (ocr.tencentcloudapi.com) 和 TMT (tmt.tencentcloudapi.com) 使用；
    // OCR 端点不分 region（X-TC-Region 仅占位），TMT 端点对 region 敏感（默认 ap-guangzhou 通用）。
    var tencentRegion by remember { mutableStateOf("ap-guangzhou") }
    var tencentEndpoint by remember { mutableStateOf(com.gameocr.app.data.TencentOcrEndpoint.GENERAL_BASIC) }
    var tencentLanguage by remember { mutableStateOf(com.gameocr.app.data.TencentOcrLanguage.AUTO) }
    var paddleModelVersion by remember { mutableStateOf(com.gameocr.app.data.PaddleModelVersion.V6_SMALL) }
    var paddleStatus by remember { mutableStateOf("") }
    var paddleDownloading by remember { mutableStateOf(false) }
    var paddleModelReady by remember { mutableStateOf(false) }
    var mangaOcrStatus by remember { mutableStateOf("") }
    var mangaOcrDownloading by remember { mutableStateOf(false) }
    var mangaOcrModelReady by remember { mutableStateOf(false) }
    var orientationModelStatus by remember { mutableStateOf("") }
    var orientationModelDownloading by remember { mutableStateOf(false) }
    var orientationModelReady by remember { mutableStateOf(false) }
    var preUpscale by remember { mutableStateOf(false) }
    var preInvert by remember { mutableStateOf(false) }
    var preBinarize by remember { mutableStateOf(false) }
    var a11yVolume by remember { mutableStateOf(false) }
    var floatingSize by remember { mutableStateOf(56f) }
    var floatingSnapEdge by remember { mutableStateOf(true) }
    var floatingAutoDock by remember { mutableStateOf(false) }
    var floatingDockInset by remember { mutableStateOf(0f) }
    // 弧菜单按钮顺序 + 划词词典 prompt：拖动 / 编辑后即时通过 vm 的 saveArcMenuOrder / saveDictionaryPrompt
    // 单字段落盘，**不**走主 save 的 dirty 流程（用户期望立刻生效，无需点保存）。
    var menuOrder by remember { mutableStateOf<List<MenuItemId>>(emptyList()) }
    var arcMenuPageSize by remember { mutableStateOf(FloatingMenu.DEFAULT_PAGE_SIZE.toFloat()) }
    // 当前主球技能。技能槽（FULL_SCREEN_SKILL）那一行的文案要跟着它动态显示「切到对方」：
    // 当前 FULL_SCREEN → 显示「— 划词翻译」；当前 WORD_SELECT → 显示「— 全屏翻译」
    var currentSkill by remember { mutableStateOf(com.gameocr.app.data.FloatingSkill.FULL_SCREEN) }
    var dictionaryPrompt by remember { mutableStateOf("") }
    // 悬浮按钮"贴边距离" slider 的实时预览：屏幕两侧画 inset 宽度的半透粉条。
    // 默认 false——进设置就显示条带太突兀；用户在 slider 旁手动开启「预览」后才覆盖到屏幕上。
    var insetPreviewActive by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val insetPreview = remember { EdgeInsetPreviewOverlay(context) }
    LaunchedEffect(insetPreviewActive, floatingDockInset, floatingSnapEdge) {
        if (insetPreviewActive && floatingSnapEdge) {
            val px = with(density) { floatingDockInset.dp.roundToPx() }
            insetPreview.update(px)
        } else {
            insetPreview.hide()
        }
    }
    DisposableEffect(Unit) {
        onDispose { insetPreview.hide() }
    }
    var allowWrap by remember { mutableStateOf(true) }
    var avoidCollision by remember { mutableStateOf(true) }
    var apiTimeoutSec by remember { mutableStateOf(30f) }
    var mergeAdjacent by remember { mutableStateOf(true) }
    var mergeStrength by remember { mutableStateOf(com.gameocr.app.data.MergeStrength.STANDARD) }
    // 文本方向自动判别：默认关；改动后即时落盘（走 viewModel.saveTextOrientationAutoDetect），不进 buildSnapshot
    var textOrientAutoDetect by remember { mutableStateOf(false) }
    // DBNet / 气泡聚类阈值。Slider 切换即时落盘（saveDbnetThresholds），下次截屏立即生效。
    var dbnetProb by remember { mutableStateOf(0.25f) }
    var dbnetScore by remember { mutableStateOf(0.5f) }
    var dbnetUnclip by remember { mutableStateOf(1.55f) }
    var mangaOcrDbnetUnclip by remember { mutableStateOf(1.65f) }
    var dbnetGap by remember { mutableStateOf(32) }
    var dbnetAdvancedExpanded by remember { mutableStateOf(false) }
    var showDbnetResetConfirm by remember { mutableStateOf(false) }
    var manualTextOrient by remember { mutableStateOf<com.gameocr.app.ocr.TextOrientation?>(null) }
    var translationPresets by remember { mutableStateOf<List<TranslationPreset>>(emptyList()) }
    var activeTranslationPresetId by remember { mutableStateOf("") }
    var presetMessage by remember { mutableStateOf<String?>(null) }
    var presetLlmModelReady by remember { mutableStateOf<Map<LlmModelKind, Boolean>>(emptyMap()) }
    var presetPaddleModelReady by remember { mutableStateOf<Map<PaddleModelVersion, Boolean>>(emptyMap()) }
    var presetMangaOcrModelReady by remember { mutableStateOf(false) }
    var presetOrientationModelReady by remember { mutableStateOf(false) }
    var downloadingPresetId by remember { mutableStateOf<String?>(null) }
    // 明文 HTTP 白名单：用户每行一个 host，UI 上用 String，保存时 split("\n")
    var cleartextHostsText by remember { mutableStateOf("") }
    // 星标语言：本地镜像。togglePinLanguage 立即落盘，下次 ON_RESUME / load() 拉回最新；
    // 这里也乐观更新一份本地状态，UI 立刻反映。
    var pinnedLanguages by remember { mutableStateOf<List<String>>(emptyList()) }

    // dirty 检测：load 时 capture 一份初始 Settings，之后跟 buildSnapshot() 比 equals。
    // 旧版手写两份 List<Any?>，每加 Settings 字段都要在两个 list 同步加，反复犯"忘改一边"的 bug。
    // 现在用 data class equals 自动覆盖所有字段——加字段只改 buildSnapshot() 一处。
    var initialSettings by remember { mutableStateOf<Settings?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSakuraFallbackDialog by remember { mutableStateOf(false) }
    var pendingModelDownload by remember { mutableStateOf<PendingModelDownload?>(null) }

    fun requestModelDownload(modelLabel: String, onConfirmed: () -> Unit) {
        val warning = modelDownloadNetworkWarningFor(currentModelDownloadNetworkKind(context))
        if (warning == null) {
            onConfirmed()
        } else {
            pendingModelDownload = PendingModelDownload(modelLabel, warning, onConfirmed)
        }
    }

    val localLlmDeviceCapable = remember { viewModel.llmDeviceCapable() }
    suspend fun refreshLlmModelState(kind: LlmModelKind) {
        val state = withContext(Dispatchers.IO) { viewModel.llmModelUiState(kind) }
        llmModelStatus = state.status
        llmModelReady = state.ready
        presetLlmModelReady = presetLlmModelReady + (kind to state.ready)
    }
    suspend fun refreshPaddleModelState(version: com.gameocr.app.data.PaddleModelVersion) {
        val state = withContext(Dispatchers.IO) { viewModel.paddleModelUiState(version) }
        paddleStatus = state.status
        paddleModelReady = state.ready
        presetPaddleModelReady = presetPaddleModelReady + (version to state.ready)
    }
    suspend fun refreshMangaOcrModelState() {
        val state = withContext(Dispatchers.IO) { viewModel.mangaOcrModelUiState() }
        mangaOcrStatus = state.status
        mangaOcrModelReady = state.ready
        presetMangaOcrModelReady = state.ready
    }
    suspend fun refreshOrientationModelState() {
        val state = withContext(Dispatchers.IO) { viewModel.orientationModelUiState() }
        orientationModelStatus = state.status
        orientationModelReady = state.ready
        presetOrientationModelReady = state.ready
    }
    suspend fun refreshPresetModelReadiness(customPresets: List<TranslationPreset> = translationPresets) {
        val presets = TranslationPresetCatalog.all(customPresets)
        val llmKinds = presets.mapNotNull { localLlmModelKindFor(it.translatorEngine) }.toSet()
        val paddleVersions = presets
            .filter { it.ocrEngine == OcrEngineKind.PADDLE_ONNX || it.ocrEngine == OcrEngineKind.MANGA_OCR_JA }
            .map { it.paddleModelVersion }
            .toSet()
        val needsMangaOcr = presets.any { it.ocrEngine == OcrEngineKind.MANGA_OCR_JA }
        val needsOrientation = presets.any { it.textOrientationAutoDetect }
        val nextLlmReady = withContext(Dispatchers.IO) {
            llmKinds.associateWith { kind -> viewModel.llmModelReady(kind) }
        }
        val nextPaddleReady = withContext(Dispatchers.IO) {
            paddleVersions.associateWith { version -> viewModel.isPaddleInstalled(version) }
        }
        val nextMangaReady = if (needsMangaOcr) {
            withContext(Dispatchers.IO) { viewModel.mangaOcrModelReady() }
        } else {
            false
        }
        val nextOrientationReady = if (needsOrientation) {
            withContext(Dispatchers.IO) { viewModel.orientationModelReady() }
        } else {
            false
        }
        presetLlmModelReady = nextLlmReady
        presetPaddleModelReady = nextPaddleReady
        presetMangaOcrModelReady = nextMangaReady
        presetOrientationModelReady = nextOrientationReady
    }
    fun effectiveTranslatorEngine(): TranslatorEngine =
        if (isLocalLlmEngine(translatorEngine) && !localLlmDeviceCapable) {
            TranslatorEngine.OPENAI
        } else {
            translatorEngine
    }
    fun selectTranslatorEngine(engine: TranslatorEngine) {
        translatorEngine = engine
        if (engine == TranslatorEngine.LOCAL_SAKURA && !supportsSakuraLanguagePair(sourceLang, targetLang)) {
            showSakuraFallbackDialog = true
        }
    }

    fun applyPresetSettingsToUi(s: Settings) {
        baseUrl = s.baseUrl
        model = s.model
        prompt = s.promptTemplate
        sourceLang = s.sourceLang
        targetLang = s.targetLang
        dictionaryPrompt = s.dictionaryPrompt
        translatorEngine = s.translatorEngine
        ocrEngine = s.ocrEngine
        preUpscale = s.preprocess.upscale2x
        preInvert = s.preprocess.invert
        preBinarize = s.preprocess.binarize
        textSize = s.overlayTextSizeSp.toFloat()
        alpha = s.overlayAlpha
        overlayFontFileName = s.overlayFontFileName
        overlayFontDisplayName = s.overlayFontDisplayName
        streaming = s.streamingTranslate
        renderMode = s.renderMode
        placement = s.overlayPlacement
        overlayTheme = s.overlayTheme
        customBg = s.customBgColor
        customFg = s.customFgColor
        customBorder = s.customBorderColor
        customBorderW = s.customBorderWidth.toFloat()
        customBorderStyle = s.customBorderStyle
        offsetX = s.overlayOffsetX.toFloat()
        offsetY = s.overlayOffsetY.toFloat()
        allowWrap = s.overlayAllowWrap
        avoidCollision = s.overlayAvoidCollision
        deeplPro = s.deeplPro
        deeplProtocol = s.deeplProtocol
        deeplBaseUrl = s.deeplBaseUrl
        deeplBearerAuth = s.deeplBearerAuth
        baiduEndpoint = s.baiduOcrEndpoint
        baiduLanguage = s.baiduOcrLanguage
        umiOcrBaseUrl = s.umiOcrBaseUrl
        lunaOcrBaseUrl = s.lunaOcrBaseUrl
        tencentRegion = s.tencentRegion
        tencentEndpoint = s.tencentOcrEndpoint
        tencentLanguage = s.tencentOcrLanguage
        paddleModelVersion = s.paddleModelVersion
        apiTimeoutSec = s.apiTimeoutSeconds.toFloat()
        mergeAdjacent = s.mergeAdjacentBlocks
        mergeStrength = s.mergeStrength
        textOrientAutoDetect = s.textOrientationAutoDetect
        manualTextOrient = s.manualTextOrientation
        dbnetProb = s.dbnetProbThresh
        dbnetScore = s.dbnetBoxScoreThresh
        dbnetUnclip = s.dbnetUnclipRatio
        mangaOcrDbnetUnclip = s.mangaOcrDbnetUnclipRatio
        dbnetGap = s.bubbleClusterGap
        activeTranslationPresetId = s.activeTranslationPresetId
    }
    fun presetDisplayNameForMessage(preset: TranslationPreset): String = when (preset.id) {
        TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH ->
            context.getString(R.string.settings_translation_preset_builtin_manga)
        else -> preset.name
    }

    // —— 搜索：顶部输入 → 下拉匹配项 → 点击 animateScrollTo 到对应 section 顶部 ——
    val scrollState = rememberScrollState()
    val overlayFontImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val wasSystemDefaultOnly = shouldShowOverlayFontDeleteTipBeforeImport(
                currentFileName = overlayFontFileName,
                fonts = overlayFontEntries
            )
            when (val result = withContext(Dispatchers.IO) { viewModel.importOverlayFont(uri) }) {
                is OverlayFontImportResult.Success -> {
                    overlayFontFileName = result.fileName
                    overlayFontDisplayName = result.displayName
                    overlayFontEntries = OverlayFontPolicy.upsertImportedFont(
                        overlayFontEntries,
                        result.fileName,
                        result.displayName
                    )
                    overlayFontTypeface = withContext(Dispatchers.IO) {
                        viewModel.overlayTypefaceFor(result.fileName)
                    }
                    overlayFontMessage = context.getString(R.string.settings_overlay_font_import_success)
                    overlayFontMessageIsError = false
                    if (wasSystemDefaultOnly) {
                        showOverlayFontDeleteTip = true
                    }
                }
                is OverlayFontImportResult.Failure -> {
                    overlayFontMessage = overlayFontImportErrorMessage(context, result.error)
                    overlayFontMessageIsError = true
                }
            }
        }
    }

    LaunchedEffect(showOverlayFontDeleteTip) {
        if (showOverlayFontDeleteTip) {
            for (remaining in 3 downTo 1) {
                overlayFontDeleteTipCountdown = remaining
                delay(1000L)
            }
            overlayFontDeleteTipCountdown = 0
        }
    }

    LaunchedEffect(overlayFontFileName) {
        overlayFontTypeface = withContext(Dispatchers.IO) {
            viewModel.overlayTypefaceFor(overlayFontFileName)
        }
    }

    val anchors = remember { mutableStateMapOf<String, Int>() }
    val onAnchor: (String, Int) -> Unit = { key, y -> anchors[key] = y }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    // 从当前所有 state 构造一份 Settings 实例。`Settings()` 默认值起手，`.copy(...)` 覆盖设置页
    // 能改的字段；不在设置页改的字段（captureRegion / preferShizukuCapture / tencentRegion /
    // pinnedLanguages）保留 Settings 默认占位——initial 和 current 都用同一默认值，equals 时这些
    // 字段始终相等，dirty 只反映用户在本页的实际改动。
    //
    // 类型转换跟 doSave 保持一致（textSize.toInt() / loopInterval.toLongOrNull() 等）。
    fun buildSnapshot(): Settings = Settings().copy(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        sourceLang = sourceLang,
        targetLang = targetLang,
        promptTemplate = prompt,
        ocrEngine = ocrEngine,
        captureLoopIntervalMs = loopInterval.toLongOrNull() ?: 2000L,
        overlayTextSizeSp = textSize.toInt(),
        overlayAlpha = alpha,
        overlayFonts = overlayFontEntries,
        streamingTranslate = streaming,
        renderMode = renderMode,
        overlayPlacement = placement,
        overlayTheme = overlayTheme,
        customBgColor = customBg,
        customFgColor = customFg,
        customBorderColor = customBorder,
        customBorderWidth = customBorderW.toInt(),
        overlayOffsetX = offsetX.toInt(),
        overlayOffsetY = offsetY.toInt(),
        preprocess = PreprocessOptions(preUpscale, preInvert, preBinarize),
        baiduOcrApiKey = baiduKey,
        baiduOcrSecretKey = baiduSecret,
        baiduOcrEndpoint = baiduEndpoint,
        baiduOcrLanguage = baiduLanguage,
        umiOcrBaseUrl = umiOcrBaseUrl,
        lunaOcrBaseUrl = lunaOcrBaseUrl,
        paddleAiStudioToken = paddleAiStudioToken,
        tencentSecretId = tencentId,
        tencentSecretKey = tencentKey,
        tencentRegion = tencentRegion,
        tencentOcrEndpoint = tencentEndpoint,
        tencentOcrLanguage = tencentLanguage,
        a11yVolumeTrigger = a11yVolume,
        translatorEngine = effectiveTranslatorEngine(),
        deeplApiKey = deeplKey,
        deeplPro = deeplPro,
        deeplProtocol = deeplProtocol,
        deeplBaseUrl = deeplBaseUrl,
        deeplBearerAuth = deeplBearerAuth,
        deeplCustomToken = deeplCustomToken,
        youdaoAppKey = youdaoAppKey,
        youdaoAppSecret = youdaoAppSecret,
        volcAccessKeyId = volcAk,
        volcSecretAccessKey = volcSk,
        volcRegion = volcRegion,
        baiduFanyiAppId = baiduFanyiAppId,
        baiduFanyiSecretKey = baiduFanyiSecret,
        floatingButtonSizeDp = floatingSize.toInt(),
        floatingButtonSnapToEdge = floatingSnapEdge,
        floatingButtonAutoDock = floatingAutoDock,
        floatingButtonDockInsetDp = floatingDockInset.toInt(),
        overlayAllowWrap = allowWrap,
        overlayAvoidCollision = avoidCollision,
        apiTimeoutSeconds = apiTimeoutSec.toInt(),
        mergeAdjacentBlocks = mergeAdjacent,
        mergeStrength = mergeStrength,
        cleartextAllowedHosts = cleartextHostsWithLocalOcrUrls(
            parseCleartextHosts(cleartextHostsText),
            umiOcrBaseUrl,
            lunaOcrBaseUrl
        )
    )

    fun buildTranslationPresetSnapshot(): Settings = buildSnapshot().copy(
        customBorderStyle = customBorderStyle,
        overlayFontFileName = overlayFontFileName,
        overlayFontDisplayName = overlayFontDisplayName,
        dictionaryPrompt = dictionaryPrompt,
        paddleModelVersion = paddleModelVersion,
        textOrientationAutoDetect = textOrientAutoDetect,
        manualTextOrientation = manualTextOrient,
        dbnetProbThresh = dbnetProb,
        dbnetBoxScoreThresh = dbnetScore,
        dbnetUnclipRatio = dbnetUnclip,
        mangaOcrDbnetUnclipRatio = mangaOcrDbnetUnclip,
        bubbleClusterGap = dbnetGap
    )

    fun currentTranslationPresetHash(): String =
        TranslationPresetCatalog.hashForSettings(buildTranslationPresetSnapshot())

    fun currentMatchingTranslationPresetId(settingsHash: String = currentTranslationPresetHash()): String {
        val presets = TranslationPresetCatalog.all(translationPresets)
        val activeMatch = presets.firstOrNull {
            it.id == activeTranslationPresetId && TranslationPresetCatalog.matchesHash(it, settingsHash)
        }
        return activeMatch?.id
            ?: presets.firstOrNull { TranslationPresetCatalog.matchesHash(it, settingsHash) }?.id
            ?: ""
    }

    suspend fun downloadModelsForPreset(
        preset: TranslationPreset,
        issues: List<TranslationPresetModelIssue>
    ) {
        val llmKinds = issues.mapNotNull { it.llmModelKind }.distinct()
        val paddleVersions = issues.mapNotNull { it.paddleModelVersion }.distinct()

        for (kind in llmKinds) {
            presetMessage = context.getString(
                R.string.settings_translation_preset_downloading_model_format,
                kind.displayName
            )
            llmDownloading = true
            try {
                viewModel.saveLlmMirror(llmMirrorChoice, llmMirror)
                viewModel.downloadLlmModel(kind) { msg ->
                    if (localLlmModelKindFor(translatorEngine) == kind) {
                        llmModelStatus = msg
                    }
                    presetMessage = msg
                }
                refreshLlmModelState(kind)
            } finally {
                llmDownloading = false
            }
        }

        for (version in paddleVersions) {
            val versionLabel = context.getString(version.displayNameRes)
            presetMessage = context.getString(
                R.string.settings_translation_preset_downloading_model_format,
                versionLabel
            )
            paddleDownloading = true
            try {
                viewModel.downloadPaddleModels(version) { msg ->
                    if (paddleModelVersion == version) {
                        paddleStatus = msg
                    }
                    presetMessage = msg
                }
                refreshPaddleModelState(version)
            } finally {
                paddleDownloading = false
            }
        }

        if (issues.any { it.kind == TranslationPresetModelIssueKind.MANGA_OCR_MISSING }) {
            presetMessage = context.getString(
                R.string.settings_translation_preset_downloading_model_format,
                context.getString(R.string.settings_manga_ocr_model_name)
            )
            mangaOcrDownloading = true
            try {
                viewModel.downloadMangaOcrModels { msg ->
                    mangaOcrStatus = msg
                    presetMessage = msg
                }
                refreshMangaOcrModelState()
            } finally {
                mangaOcrDownloading = false
            }
        }

        if (issues.any { it.kind == TranslationPresetModelIssueKind.ORIENTATION_MISSING }) {
            presetMessage = context.getString(
                R.string.settings_translation_preset_downloading_model_format,
                context.getString(R.string.settings_orientation_model_name)
            )
            orientationModelDownloading = true
            try {
                viewModel.downloadOrientationModel { msg ->
                    orientationModelStatus = msg
                    presetMessage = msg
                }
                refreshOrientationModelState()
            } finally {
                orientationModelDownloading = false
            }
        }

        refreshPresetModelReadiness()
        presetMessage = context.getString(
            R.string.settings_translation_preset_download_models_done_format,
            presetDisplayNameForMessage(preset)
        )
    }

    // derivedStateOf 让 lambda 在依赖 state 变化时才重新计算 equals
    val dirty by remember {
        derivedStateOf {
            val initial = initialSettings ?: return@derivedStateOf false
            initial != buildSnapshot()
        }
    }

    val doSave: suspend () -> Unit = {
        viewModel.save(
            baseUrl = baseUrl, apiKey = apiKey, model = model,
            targetLang = targetLang, sourceLang = sourceLang, prompt = prompt,
            textSize = textSize.toInt(), alpha = alpha,
            loopMs = loopInterval.toLongOrNull() ?: 2000L,
            streaming = streaming, renderMode = renderMode, placement = placement,
            overlayTheme = overlayTheme,
            customBg = customBg, customFg = customFg,
            customBorder = customBorder, customBorderW = customBorderW.toInt(),
            offsetX = offsetX.toInt(), offsetY = offsetY.toInt(),
            ocrEngine = ocrEngine,
            baiduKey = baiduKey, baiduSecret = baiduSecret, baiduEndpoint = baiduEndpoint,
            baiduLanguage = baiduLanguage,
            umiOcrBaseUrl = umiOcrBaseUrl,
            lunaOcrBaseUrl = lunaOcrBaseUrl,
            paddleAiStudioToken = paddleAiStudioToken,
            tencentId = tencentId, tencentKey = tencentKey, tencentRegion = tencentRegion,
            tencentEndpoint = tencentEndpoint,
            tencentLanguage = tencentLanguage,
            preprocess = PreprocessOptions(preUpscale, preInvert, preBinarize),
            a11yVolume = a11yVolume,
            floatingButtonSizeDp = floatingSize.toInt(),
            floatingButtonSnapToEdge = floatingSnapEdge,
            floatingButtonAutoDock = floatingAutoDock,
            floatingButtonDockInsetDp = floatingDockInset.toInt(),
            allowWrap = allowWrap,
            avoidCollision = avoidCollision,
            apiTimeoutSeconds = apiTimeoutSec.toInt(),
            mergeAdjacentBlocks = mergeAdjacent,
            mergeStrength = mergeStrength,
            cleartextAllowedHosts = parseCleartextHosts(cleartextHostsText),
            translatorEngine = effectiveTranslatorEngine(),
            deeplKey = deeplKey,
            deeplPro = deeplPro,
            deeplProtocol = deeplProtocol,
            deeplBaseUrl = deeplBaseUrl,
            deeplBearerAuth = deeplBearerAuth,
            deeplCustomToken = deeplCustomToken,
            youdaoAppKey = youdaoAppKey,
            youdaoAppSecret = youdaoAppSecret,
            volcAccessKeyId = volcAk,
            volcSecretAccessKey = volcSk,
            volcRegion = volcRegion,
            baiduFanyiAppId = baiduFanyiAppId,
            baiduFanyiSecretKey = baiduFanyiSecret,
            overlayFonts = overlayFontEntries,
            activeTranslationPresetId = currentMatchingTranslationPresetId()
        )
    }

    val translationPresetSection: @Composable () -> Unit = {
        SectionCard(
            title = stringResource(R.string.settings_section_translation_presets),
            anchorKey = SectionKeys.PRESETS,
            onAnchor = onAnchor,
            helpText = stringResource(R.string.settings_translation_preset_desc)
        ) {
            val presetSnapshot = buildTranslationPresetSnapshot()
            val presetHash = TranslationPresetCatalog.hashForSettings(presetSnapshot)
            val matchingPresetId = currentMatchingTranslationPresetId(presetHash)
            val unsavedPresetName = stringResource(R.string.settings_translation_preset_unsaved_name)
            val unsavedPreset = if (initialSettings != null && matchingPresetId.isBlank()) {
                TranslationPresetCatalog.fromSettings(
                    id = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                    name = unsavedPresetName,
                    shortName = unsavedPresetName.take(8),
                    settings = presetSnapshot
                )
            } else {
                null
            }
            val modelDownloadBusy = downloadingPresetId != null ||
                llmDownloading || paddleDownloading || mangaOcrDownloading || orientationModelDownloading
            TranslationPresetSection(
                customPresets = translationPresets,
                activeId = matchingPresetId,
                unsavedPreset = unsavedPreset,
                message = presetMessage,
                localLlmDeviceCapable = localLlmDeviceCapable,
                llmModelReady = { kind -> presetLlmModelReady[kind] == true },
                paddleModelReady = { version -> presetPaddleModelReady[version] == true },
                mangaOcrModelReady = presetMangaOcrModelReady,
                orientationModelReady = presetOrientationModelReady,
                modelDownloading = modelDownloadBusy,
                downloadingPresetId = downloadingPresetId,
                onSaveUnsaved = { preset ->
                    scope.launch {
                        val saved = viewModel.saveTranslationPreset(preset)
                        translationPresets = TranslationPresetCatalog.upsertCustom(translationPresets, saved)
                        activeTranslationPresetId = saved.id
                        presetMessage = context.getString(
                            R.string.settings_translation_preset_saved_format,
                            saved.name
                        )
                    }
                },
                onApply = { preset ->
                    scope.launch {
                        val applied = viewModel.applyTranslationPreset(preset.id) ?: return@launch
                        applyPresetSettingsToUi(applied)
                        activeTranslationPresetId = preset.id
                        initialSettings = buildSnapshot()
                        presetMessage = context.getString(
                            R.string.settings_translation_preset_applied_format,
                            presetDisplayNameForMessage(preset)
                        )
                    }
                },
                onCopy = { preset ->
                    scope.launch {
                        val copiedName = "${presetDisplayNameForMessage(preset)} Copy"
                        val copied = viewModel.duplicateTranslationPreset(
                            preset.id,
                            copiedName,
                            copiedName.take(8)
                        ) ?: return@launch
                        translationPresets = TranslationPresetCatalog.upsertCustom(translationPresets, copied)
                        presetMessage = context.getString(
                            R.string.settings_translation_preset_copied_format,
                            copied.name
                        )
                    }
                },
                onDownloadModels = { preset, issues ->
                    if (modelDownloadBusy) {
                        presetMessage = context.getString(R.string.settings_translation_preset_other_download_busy)
                    } else {
                        val downloadModelLabel = translationPresetDownloadModelLabel(context, issues)
                        requestModelDownload(downloadModelLabel) {
                            scope.launch {
                                if (downloadingPresetId != null ||
                                    llmDownloading || paddleDownloading ||
                                    mangaOcrDownloading || orientationModelDownloading
                                ) {
                                    presetMessage = context.getString(
                                        R.string.settings_translation_preset_other_download_busy
                                    )
                                    return@launch
                                }
                                downloadingPresetId = preset.id
                                try {
                                    downloadModelsForPreset(preset, issues)
                                } catch (t: Throwable) {
                                    presetMessage = context.getString(
                                        R.string.settings_translation_preset_download_models_failed_format,
                                        t.message ?: t.javaClass.simpleName
                                    )
                                    refreshPresetModelReadiness()
                                } finally {
                                    downloadingPresetId = null
                                }
                            }
                        }
                    }
                },
                onDelete = { preset ->
                    scope.launch {
                        viewModel.deleteTranslationPreset(preset.id)
                        translationPresets = translationPresets.filterNot { it.id == preset.id }
                        if (activeTranslationPresetId == preset.id) activeTranslationPresetId = ""
                    }
                }
            )
        }
    }

    val textOrientationSection: @Composable () -> Unit = {
        SectionCard(
            title = stringResource(R.string.settings_text_orientation_section_title),
            anchorKey = SectionKeys.TEXT_ORIENTATION,
            onAnchor = onAnchor
        ) {
            SwitchRow(
                stringResource(R.string.settings_orient_auto_detect_title),
                textOrientAutoDetect,
                helpText = stringResource(R.string.settings_orient_auto_detect_summary)
            ) { enabled ->
                textOrientAutoDetect = enabled
                scope.launch { viewModel.saveTextOrientationAutoDetect(enabled) }
            }
            if (textOrientAutoDetect) {
                Text(
                    stringResource(R.string.settings_orient_manual_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    EngineChip(manualTextOrient, null,
                        stringResource(R.string.settings_orient_manual_auto)) {
                        manualTextOrient = it
                        scope.launch { viewModel.saveManualTextOrientation(it) }
                    }
                    EngineChip(manualTextOrient, com.gameocr.app.ocr.TextOrientation.HORIZONTAL_LTR,
                        stringResource(R.string.settings_orient_horizontal_ltr)) {
                        manualTextOrient = it
                        scope.launch { viewModel.saveManualTextOrientation(it) }
                    }
                    EngineChip(manualTextOrient, com.gameocr.app.ocr.TextOrientation.VERTICAL_RTL,
                        stringResource(R.string.settings_orient_vertical_rtl)) {
                        manualTextOrient = it
                        scope.launch { viewModel.saveManualTextOrientation(it) }
                    }
                    EngineChip(manualTextOrient, com.gameocr.app.ocr.TextOrientation.STACKED,
                        stringResource(R.string.settings_orient_stacked)) {
                        manualTextOrient = it
                        scope.launch { viewModel.saveManualTextOrientation(it) }
                    }
                }
                if (manualTextOrient != null) {
                    Text(
                        stringResource(R.string.settings_orient_manual_active_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
                OrientationModelSection(
                    status = orientationModelStatus,
                    downloading = orientationModelDownloading,
                    modelReady = orientationModelReady,
                    onDownload = {
                        requestModelDownload(context.getString(R.string.settings_orientation_model_name)) {
                            scope.launch {
                                orientationModelDownloading = true
                                try {
                                    viewModel.downloadOrientationModel { msg -> orientationModelStatus = msg }
                                    refreshOrientationModelState()
                                } catch (t: Throwable) {
                                    orientationModelStatus = context.getString(
                                        R.string.settings_orientation_model_download_failed_format,
                                        t.message ?: t.javaClass.simpleName
                                    )
                                    orientationModelReady = withContext(Dispatchers.IO) {
                                        viewModel.orientationModelReady()
                                    }
                                } finally {
                                    orientationModelDownloading = false
                                }
                            }
                        }
                    },
                    onImport = { uris ->
                        scope.launch {
                            orientationModelDownloading = true
                            try {
                                val n = viewModel.importOrientationModelFromLocal(uris)
                                val state = withContext(Dispatchers.IO) { viewModel.orientationModelUiState() }
                                orientationModelStatus = context.getString(
                                    R.string.settings_orientation_model_imported_format,
                                    n, state.status
                                )
                                orientationModelReady = state.ready
                            } finally {
                                orientationModelDownloading = false
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            viewModel.deleteOrientationModel()
                            refreshOrientationModelState()
                        }
                    }
                )
            }
        }
    }

    val tryBack: () -> Unit = {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    BackHandler { tryBack() }

    val currentTranslationPresetHash = currentTranslationPresetHash()
    val matchingTranslationPresetId = currentMatchingTranslationPresetId(currentTranslationPresetHash)
    LaunchedEffect(
        initialSettings,
        activeTranslationPresetId,
        translationPresets,
        currentTranslationPresetHash
    ) {
        if (initialSettings == null) return@LaunchedEffect
        if (activeTranslationPresetId != matchingTranslationPresetId) {
            activeTranslationPresetId = matchingTranslationPresetId
            viewModel.setActiveTranslationPreset(matchingTranslationPresetId)
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    scope.launch { doSave(); onBack() }
                }) { Text(stringResource(R.string.settings_unsaved_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onBack()
                    }) { Text(stringResource(R.string.settings_unsaved_discard)) }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.settings_unsaved_keep_editing))
                    }
                }
            }
        )
    }

    // 源语言↔OCR 联动：检查能否识别当前源语言；不能则按"用户刚动的是哪一边"决定推荐方向。
    pendingModelDownload?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingModelDownload = null },
            title = { Text(stringResource(R.string.settings_model_download_network_warning_title)) },
            text = {
                Text(
                    stringResource(
                        modelDownloadNetworkWarningMessageRes(pending.warning),
                        pending.modelLabel
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val confirmed = pending.onConfirmed
                    pendingModelDownload = null
                    confirmed()
                }) {
                    Text(stringResource(R.string.settings_model_download_network_warning_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingModelDownload = null }) {
                    Text(stringResource(R.string.settings_model_download_network_warning_cancel))
                }
            }
        )
    }

    if (showOverlayFontDeleteTip) {
        AlertDialog(
            onDismissRequest = {
                if (overlayFontDeleteTipCountdown == 0) showOverlayFontDeleteTip = false
            },
            title = { Text(stringResource(R.string.settings_overlay_font_delete_tip_title)) },
            text = { Text(stringResource(R.string.settings_overlay_font_delete_tip_message)) },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(
                        enabled = overlayFontDeleteTipCountdown == 0,
                        onClick = { showOverlayFontDeleteTip = false }
                    ) {
                        Text(
                            overlayFontDeleteTipAckLabel(
                                baseLabel = stringResource(R.string.settings_overlay_font_delete_tip_ack),
                                countdown = overlayFontDeleteTipCountdown
                            )
                        )
                    }
                }
            }
        )
    }

    pendingOverlayFontDelete?.let { font ->
        AlertDialog(
            onDismissRequest = { pendingOverlayFontDelete = null },
            title = { Text(stringResource(R.string.settings_overlay_font_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_overlay_font_delete_confirm_message,
                        font.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverlayFontDelete = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            viewModel.deleteOverlayFont(font.fileName)
                        }
                        if (deleted) {
                            overlayFontEntries = OverlayFontPolicy.removeImportedFont(
                                overlayFontEntries,
                                font.fileName
                            )
                            if (overlayFontFileName == font.fileName) {
                                overlayFontFileName = ""
                                overlayFontDisplayName = ""
                                overlayFontTypeface = null
                            }
                            overlayFontMessage = context.getString(
                                R.string.settings_overlay_font_delete_success_format,
                                font.displayName
                            )
                            overlayFontMessageIsError = false
                        } else {
                            overlayFontMessage = context.getString(R.string.settings_overlay_font_error_invalid)
                            overlayFontMessageIsError = true
                        }
                    }
                }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverlayFontDelete = null }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            }
        )
    }

    if (showSakuraFallbackDialog) {
        val sourceName = com.gameocr.app.data.Languages.nameOf(context, sourceLang)
        val targetName = com.gameocr.app.data.Languages.nameOf(context, targetLang)
        val sakuraLanguageIssue = sakuraLanguageIssue(sourceLang, targetLang)
        val sakuraIssueMessage = when {
            sakuraLanguageIssue.sourceUnsupported && sakuraLanguageIssue.targetUnsupported -> stringResource(
                R.string.sakura_fallback_dialog_issue_pair,
                sourceName,
                targetName
            )
            sakuraLanguageIssue.sourceUnsupported -> stringResource(
                R.string.sakura_fallback_dialog_issue_source,
                sourceName
            )
            sakuraLanguageIssue.targetUnsupported -> stringResource(
                R.string.sakura_fallback_dialog_issue_target,
                targetName
            )
            else -> stringResource(
                R.string.sakura_fallback_dialog_issue_pair,
                sourceName,
                targetName
            )
        }
        val supportedLanguageActionLabel = when {
            sakuraLanguageIssue.sourceUnsupported && sakuraLanguageIssue.targetUnsupported ->
                R.string.sakura_fallback_dialog_set_supported_pair
            sakuraLanguageIssue.targetUnsupported -> R.string.sakura_fallback_dialog_set_target_zh_cn
            else -> R.string.sakura_fallback_dialog_set_japanese
        }
        val missingFallbackFields = missingOpenAiFallbackFields(baseUrl, apiKey, model)
        val fallbackBaseUrlLabel = stringResource(R.string.settings_base_url)
        val fallbackApiKeyLabel = stringResource(R.string.settings_api_key)
        val fallbackModelLabel = stringResource(R.string.settings_model)
        val missingFallbackLabels = missingFallbackFields.joinToString(", ") { field ->
            when (field) {
                OpenAiFallbackField.BASE_URL -> fallbackBaseUrlLabel
                OpenAiFallbackField.API_KEY -> fallbackApiKeyLabel
                OpenAiFallbackField.MODEL -> fallbackModelLabel
            }
        }
        AlertDialog(
            onDismissRequest = {
                showSakuraFallbackDialog = false
                if (missingFallbackFields.isNotEmpty()) {
                    translatorEngine = TranslatorEngine.OPENAI
                }
            },
            title = { Text(stringResource(R.string.sakura_fallback_dialog_title)) },
            text = {
                Text(
                    if (missingFallbackFields.isEmpty()) {
                        stringResource(R.string.sakura_fallback_dialog_message_configured, sakuraIssueMessage)
                    } else {
                        stringResource(
                            R.string.sakura_fallback_dialog_message_missing,
                            sakuraIssueMessage,
                            missingFallbackLabels
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSakuraFallbackDialog = false
                    if (missingFallbackFields.isEmpty()) {
                        translatorEngine = TranslatorEngine.LOCAL_SAKURA
                    } else {
                        translatorEngine = TranslatorEngine.OPENAI
                    }
                }) {
                    Text(
                        stringResource(
                            if (missingFallbackFields.isEmpty()) {
                                R.string.sakura_fallback_dialog_use_fallback
                            } else {
                                R.string.sakura_fallback_dialog_configure_fallback
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSakuraFallbackDialog = false
                    if (sakuraLanguageIssue.sourceUnsupported) {
                        sourceLang = "ja"
                    }
                    if (sakuraLanguageIssue.targetUnsupported) {
                        targetLang = SAKURA_TARGET_LANG_ZH_CN
                    }
                    translatorEngine = TranslatorEngine.LOCAL_SAKURA
                }) {
                    Text(stringResource(supportedLanguageActionLabel))
                }
            }
        )
    }

    var ocrLangIssue by remember { mutableStateOf<OcrLangIssue?>(null) }
    var langCheckPrimed by remember { mutableStateOf(false) }
    var lastCheckedLang by remember { mutableStateOf<String?>(null) }
    // dismissedFor 只在"本次会话内同一语言已被用户点过保持不变"时生效；用户切到别的语言再切回
    // 来就重新检查。
    var langDismissedFor by remember { mutableStateOf<String?>(null) }
    // 跟踪上次 OCR 端的完整状态。下次 LaunchedEffect 跑时和当前比对，判断这次"主要"是
    // 改了源语言还是改了 OCR 端，进而决定推荐方向：
    //  - 源语言变 → 推荐改 OCR（旧行为）
    //  - OCR 端变 → 推荐改源语言（修复"撤销用户操作"的 bug）
    var prevOcrEngine by remember { mutableStateOf(ocrEngine) }
    var prevBaiduEndpoint by remember { mutableStateOf(baiduEndpoint) }
    var prevBaiduLanguage by remember { mutableStateOf(baiduLanguage) }
    var prevTencentEndpoint by remember { mutableStateOf(tencentEndpoint) }
    var prevTencentLanguage by remember { mutableStateOf(tencentLanguage) }
    LaunchedEffect(
        sourceLang, ocrEngine,
        baiduEndpoint, baiduLanguage,
        tencentEndpoint, tencentLanguage
    ) {
        timber.log.Timber.tag("OcrLangLink").i(
            "[trigger] sourceLang=%s ocrEngine=%s baiduEp=%s baiduLang=%s tencentEp=%s tencentLang=%s | primed=%s lastChecked=%s dismissedFor=%s",
            sourceLang, ocrEngine, baiduEndpoint, baiduLanguage,
            tencentEndpoint, tencentLanguage,
            langCheckPrimed, lastCheckedLang, langDismissedFor
        )
        // 首次跑（load 完成那一瞬间）跳过；只在用户真正改 state 时触发
        if (!langCheckPrimed) {
            langCheckPrimed = true
            lastCheckedLang = sourceLang
            prevOcrEngine = ocrEngine
            prevBaiduEndpoint = baiduEndpoint
            prevBaiduLanguage = baiduLanguage
            prevTencentEndpoint = tencentEndpoint
            prevTencentLanguage = tencentLanguage
            timber.log.Timber.tag("OcrLangLink").i(
                "[skip-prime] first run, set primed=true lastChecked=%s -> no dialog", sourceLang
            )
            return@LaunchedEffect
        }
        val sourceChanged = sourceLang != lastCheckedLang
        val ocrSideChanged = ocrEngine != prevOcrEngine ||
            baiduEndpoint != prevBaiduEndpoint || baiduLanguage != prevBaiduLanguage ||
            tencentEndpoint != prevTencentEndpoint || tencentLanguage != prevTencentLanguage
        timber.log.Timber.tag("OcrLangLink").i(
            "[direction] sourceChanged=%s ocrSideChanged=%s", sourceChanged, ocrSideChanged
        )
        // 源语言换成了别的值：清掉上次 dismissed，相当于"用户对新语言态度待定，需要重新提示"
        if (sourceChanged) {
            timber.log.Timber.tag("OcrLangLink").i(
                "[lang-changed] %s -> %s, clearing dismissedFor(was=%s)",
                lastCheckedLang, sourceLang, langDismissedFor
            )
            langDismissedFor = null
            lastCheckedLang = sourceLang
        }
        // 同步 prev（在所有 early return 之前，避免下次再误判同一次变化）
        prevOcrEngine = ocrEngine
        prevBaiduEndpoint = baiduEndpoint
        prevBaiduLanguage = baiduLanguage
        prevTencentEndpoint = tencentEndpoint
        prevTencentLanguage = tencentLanguage

        if (sourceLang.isBlank()) {
            ocrLangIssue = null
            timber.log.Timber.tag("OcrLangLink").i("[skip-blank] sourceLang is blank -> no dialog")
            return@LaunchedEffect
        }
        if (sourceLang == langDismissedFor) {
            timber.log.Timber.tag("OcrLangLink").i(
                "[skip-dismissed] sourceLang=%s already in dismissedFor -> no dialog", sourceLang
            )
            return@LaunchedEffect
        }
        val supported = com.gameocr.app.ocr.OcrLanguageCapability.supports(
            engine = ocrEngine,
            sourceCode = sourceLang,
            baiduEndpoint = baiduEndpoint,
            tencentEndpoint = tencentEndpoint,
            baiduLanguage = baiduLanguage,
            tencentLanguage = tencentLanguage
        )
        timber.log.Timber.tag("OcrLangLink").i(
            "[supports] engine=%s lang=%s -> %s", ocrEngine, sourceLang, supported
        )
        if (supported) {
            // supports=true 仍可能不是"最优"：云端 AUTO_DETECT / CHN_ENG / MIX 等通用模式
            // 对小语种识别准确率明显低于精确指定 language。如果用户刚改了 sourceLang 或
            // OCR 端，且枚举里有精确匹配项，弹"升级"建议——只是切云端内部 language，不换引擎。
            val better = com.gameocr.app.ocr.OcrLanguageCapability.betterOcrLanguageFor(
                sourceCode = sourceLang,
                engine = ocrEngine,
                baiduEndpoint = baiduEndpoint,
                baiduLanguage = baiduLanguage,
                tencentEndpoint = tencentEndpoint,
                tencentLanguage = tencentLanguage
            )
            if (better != null && (sourceChanged || ocrSideChanged)) {
                timber.log.Timber.tag("OcrLangLink").i(
                    "[upgrade] supports=true but better config available: %s", better
                )
                ocrLangIssue = OcrLangIssue.FixOcr(sourceLang, better)
                timber.log.Timber.tag("OcrLangLink").i(
                    "[dialog] SHOW for sourceLang=%s (upgrade)", sourceLang
                )
            } else {
                ocrLangIssue = null
                timber.log.Timber.tag("OcrLangLink").i("[skip-supported] -> no dialog")
            }
            return@LaunchedEffect
        }
        // 方向选择：用户刚动 OCR 端（且源语言没动）→ 优先反向推荐改源语言；反向无解
        // （OCR 端是通用模式如 CHN_ENG / MIX，没有单一对应 BCP-47）→ fallback 到 forward，
        // 避免静默——总比让用户摸不清当前配置识别不了源语言强。
        if (ocrSideChanged && !sourceChanged) {
            val targetSource = com.gameocr.app.ocr.OcrLanguageCapability.inferSourceFor(
                engine = ocrEngine,
                baiduLanguage = baiduLanguage,
                tencentLanguage = tencentLanguage
            )
            timber.log.Timber.tag("OcrLangLink").i(
                "[reverse-recommend] inferredSource=%s currentSource=%s", targetSource, sourceLang
            )
            if (targetSource != null && targetSource != sourceLang) {
                ocrLangIssue = OcrLangIssue.FixSource(sourceLang, targetSource)
                timber.log.Timber.tag("OcrLangLink").i(
                    "[dialog] SHOW for sourceLang=%s (reverse)", sourceLang
                )
                return@LaunchedEffect
            }
            // 反向失败 → fallthrough 到 forward 推荐（在下面统一处理）
            timber.log.Timber.tag("OcrLangLink").i(
                "[reverse-fallback] no inferred source, fallback to forward recommendation"
            )
        }
        val rec = com.gameocr.app.ocr.OcrLanguageCapability.recommendFor(
            sourceCode = sourceLang,
            currentEngine = ocrEngine,
            currentBaiduEndpoint = baiduEndpoint,
            currentTencentEndpoint = tencentEndpoint,
            hasBaiduKey = baiduKey.isNotBlank() && baiduSecret.isNotBlank(),
            hasTencentKey = tencentId.isNotBlank() && tencentKey.isNotBlank()
        )
        timber.log.Timber.tag("OcrLangLink").i("[recommend] rec=%s", rec)
        ocrLangIssue = rec?.let { OcrLangIssue.FixOcr(sourceLang, it) }
        timber.log.Timber.tag("OcrLangLink").i(
            "[dialog] %s for sourceLang=%s (forward)",
            if (ocrLangIssue != null) "SHOW" else "skip(no-rec)", sourceLang
        )
    }
    ocrLangIssue?.let { issue ->
        val sourceName = com.gameocr.app.data.Languages.nameOf(context, issue.sourceCode)
        AlertDialog(
            onDismissRequest = {
                timber.log.Timber.tag("OcrLangLink").i(
                    "[dialog-dismiss-outside] mark dismissedFor=%s", issue.sourceCode
                )
                langDismissedFor = issue.sourceCode
                ocrLangIssue = null
            },
            title = { Text(stringResource(R.string.ocr_lang_issue_title)) },
            text = {
                when (issue) {
                    is OcrLangIssue.FixOcr -> {
                        // 三段式文案：keysMissing > tune（同引擎，仅改内部语种参数）> 默认（换引擎）
                        val rec = issue.recommendation
                        val recEngineLabel = stringResource(ocrEngineLabelRes(rec.engine))
                        val tuneNewLabel: String = when {
                            rec.engine == OcrEngineKind.BAIDU && rec.baiduLanguage != null ->
                                stringResource(rec.baiduLanguage.displayNameRes)
                            rec.engine == OcrEngineKind.TENCENT && rec.tencentLanguage != null ->
                                stringResource(rec.tencentLanguage.displayNameRes)
                            else -> ""
                        }
                        val tuneOldLabel: String = when (rec.engine) {
                            OcrEngineKind.BAIDU -> stringResource(baiduLanguage.displayNameRes)
                            OcrEngineKind.TENCENT -> stringResource(tencentLanguage.displayNameRes)
                            else -> ""
                        }
                        val isTuneMode = !rec.keysMissing && rec.engine == ocrEngine && tuneNewLabel.isNotEmpty()
                        Text(
                            when {
                                rec.keysMissing -> stringResource(
                                    R.string.ocr_lang_issue_msg_keys_missing, sourceName, recEngineLabel
                                )
                                isTuneMode -> stringResource(
                                    R.string.ocr_lang_issue_msg_tune,
                                    sourceName, recEngineLabel, tuneOldLabel, tuneNewLabel
                                )
                                else -> stringResource(
                                    R.string.ocr_lang_issue_msg, sourceName, recEngineLabel
                                )
                            }
                        )
                    }
                    is OcrLangIssue.FixSource -> {
                        // 反向：用户改了 OCR 端，推荐改源语言去匹配
                        val engineLabel = stringResource(ocrEngineLabelRes(ocrEngine))
                        val ocrLangLabel: String = when (ocrEngine) {
                            OcrEngineKind.BAIDU -> stringResource(baiduLanguage.displayNameRes)
                            OcrEngineKind.TENCENT -> stringResource(tencentLanguage.displayNameRes)
                            // ML_KIT 单语种引擎：用引擎自身的 chip label 代替"识别语种"概念
                            else -> engineLabel
                        }
                        val recSourceName = com.gameocr.app.data.Languages.nameOf(
                            context, issue.recommendedSourceCode
                        )
                        Text(stringResource(
                            R.string.ocr_lang_issue_msg_source_tune,
                            engineLabel, ocrLangLabel, sourceName, recSourceName
                        ))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (issue) {
                        is OcrLangIssue.FixOcr -> {
                            val rec = issue.recommendation
                            timber.log.Timber.tag("OcrLangLink").i(
                                "[dialog-apply-ocr] keysMissing=%s rec=%s", rec.keysMissing, rec
                            )
                            if (rec.keysMissing) {
                                ocrEngine = rec.engine
                                rec.baiduEndpoint?.let { baiduEndpoint = it }
                                rec.tencentEndpoint?.let { tencentEndpoint = it }
                                scope.launch {
                                    anchors[SectionKeys.OCR]?.let { y -> scrollState.animateScrollTo(y) }
                                }
                            } else {
                                ocrEngine = rec.engine
                                rec.baiduEndpoint?.let { baiduEndpoint = it }
                                rec.baiduLanguage?.let { baiduLanguage = it }
                                rec.tencentEndpoint?.let { tencentEndpoint = it }
                                rec.tencentLanguage?.let { tencentLanguage = it }
                            }
                        }
                        is OcrLangIssue.FixSource -> {
                            timber.log.Timber.tag("OcrLangLink").i(
                                "[dialog-apply-source] %s -> %s",
                                sourceLang, issue.recommendedSourceCode
                            )
                            sourceLang = issue.recommendedSourceCode
                        }
                    }
                    ocrLangIssue = null
                }) {
                    val keysMissing = (issue as? OcrLangIssue.FixOcr)?.recommendation?.keysMissing == true
                    Text(stringResource(
                        if (keysMissing) R.string.ocr_lang_issue_btn_setup
                        else R.string.ocr_lang_issue_btn_apply
                    ))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    timber.log.Timber.tag("OcrLangLink").i(
                        "[dialog-keep] mark dismissedFor=%s", issue.sourceCode
                    )
                    langDismissedFor = issue.sourceCode
                    ocrLangIssue = null
                }) { Text(stringResource(R.string.ocr_lang_issue_btn_keep)) }
            }
        )
    }

    LaunchedEffect(Unit) {
        val s = viewModel.load()
        // suspend 操作必须在 Snapshot 块外做完
        val migratedPrompt = viewModel.migrateDefaultPromptIfStale(context)
        val paddleStatusPlaceholder = context.getString(R.string.settings_paddle_status_checking)
        val mangaOcrStatusPlaceholder = context.getString(R.string.settings_manga_ocr_status_checking)
        val orientationModelStatusPlaceholder = context.getString(R.string.settings_orientation_model_status_checking)
        timber.log.Timber.tag("OcrLangLink").i(
            "[load] sourceLang=%s ocrEngine=%s baiduEp=%s baiduLang=%s tencentEp=%s tencentLang=%s",
            s.sourceLang, s.ocrEngine, s.baiduOcrEndpoint, s.baiduOcrLanguage,
            s.tencentOcrEndpoint, s.tencentOcrLanguage
        )
        // 关键性能：把 40+ state 写入封进同一个 mutable snapshot，原子 apply 后只触发
        // 一次 observer 通知，避免 Compose 在每个 state 变化时 schedule 一次 recomposition
        // / derivedStateOf 重算，进设置页那段"卡一下"主要来自这里。
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            baseUrl = s.baseUrl
            apiKey = s.apiKey
            model = s.model
            prompt = migratedPrompt
            targetLang = s.targetLang
            sourceLang = s.sourceLang
            translatorEngine = if (isLocalLlmEngine(s.translatorEngine) && !localLlmDeviceCapable) {
                TranslatorEngine.OPENAI
            } else {
                s.translatorEngine
            }
            deeplKey = s.deeplApiKey
            youdaoAppKey = s.youdaoAppKey
            volcAk = s.volcAccessKeyId
            volcSk = s.volcSecretAccessKey
            volcRegion = s.volcRegion
            baiduFanyiAppId = s.baiduFanyiAppId
            baiduFanyiSecret = s.baiduFanyiSecretKey
            youdaoAppSecret = s.youdaoAppSecret
            deeplPro = s.deeplPro
            deeplProtocol = s.deeplProtocol
            deeplBaseUrl = s.deeplBaseUrl
            deeplBearerAuth = s.deeplBearerAuth
            deeplCustomToken = s.deeplCustomToken
            textSize = s.overlayTextSizeSp.toFloat()
            alpha = s.overlayAlpha
            overlayFontFileName = s.overlayFontFileName
            overlayFontDisplayName = s.overlayFontDisplayName
            overlayFontEntries = OverlayFontPolicy.upsertImportedFont(
                s.overlayFonts,
                s.overlayFontFileName,
                s.overlayFontDisplayName
            )
            loopInterval = s.captureLoopIntervalMs.toString()
            streaming = s.streamingTranslate
            renderMode = s.renderMode
            floatingWindowContentMode = s.floatingWindowContentMode
            floatingWindowLocked = s.floatingWindowLocked
            customBorderStyle = s.customBorderStyle
            placement = s.overlayPlacement
            overlayTheme = s.overlayTheme
            customBg = s.customBgColor
            customFg = s.customFgColor
            customBorder = s.customBorderColor
            customBorderW = s.customBorderWidth.toFloat()
            offsetX = s.overlayOffsetX.toFloat()
            offsetY = s.overlayOffsetY.toFloat()
            ocrEngine = s.ocrEngine
            baiduKey = s.baiduOcrApiKey
            baiduSecret = s.baiduOcrSecretKey
            baiduEndpoint = s.baiduOcrEndpoint
            baiduLanguage = s.baiduOcrLanguage
            umiOcrBaseUrl = s.umiOcrBaseUrl
            lunaOcrBaseUrl = s.lunaOcrBaseUrl
            paddleAiStudioToken = s.paddleAiStudioToken
            tencentId = s.tencentSecretId
            tencentKey = s.tencentSecretKey
            tencentRegion = s.tencentRegion
            tencentEndpoint = s.tencentOcrEndpoint
            tencentLanguage = s.tencentOcrLanguage
            paddleModelVersion = s.paddleModelVersion
            llmMirrorChoice = s.localLlmMirror
            llmMirror = s.localLlmMirrorUrl
            // 不阻塞主线程：file.exists() + file.length() 走 IO Dispatcher。先给占位
            // 文字，IO 完成后再覆盖；进设置的瞬间不卡顿。
            paddleStatus = checkingPlaceholderIfUnresolved(paddleStatus, paddleStatusPlaceholder)
            mangaOcrStatus = checkingPlaceholderIfUnresolved(mangaOcrStatus, mangaOcrStatusPlaceholder)
            orientationModelStatus =
                checkingPlaceholderIfUnresolved(orientationModelStatus, orientationModelStatusPlaceholder)
            preUpscale = s.preprocess.upscale2x
            preInvert = s.preprocess.invert
            preBinarize = s.preprocess.binarize
            a11yVolume = s.a11yVolumeTrigger
            floatingSize = s.floatingButtonSizeDp.toFloat()
            floatingSnapEdge = s.floatingButtonSnapToEdge
            floatingAutoDock = s.floatingButtonAutoDock
            floatingDockInset = s.floatingButtonDockInsetDp.toFloat()
            menuOrder = s.floatingMenuItemOrder
            arcMenuPageSize = s.arcMenuPageSize.toFloat()
            currentSkill = s.floatingButtonSkill
            dictionaryPrompt = s.dictionaryPrompt
            translationPresets = s.translationPresets
            activeTranslationPresetId = s.activeTranslationPresetId
            pinnedLanguages = s.pinnedLanguages
            allowWrap = s.overlayAllowWrap
            avoidCollision = s.overlayAvoidCollision
            apiTimeoutSec = s.apiTimeoutSeconds.toFloat()
            mergeAdjacent = s.mergeAdjacentBlocks
            mergeStrength = s.mergeStrength
            textOrientAutoDetect = s.textOrientationAutoDetect
            dbnetProb = s.dbnetProbThresh
            dbnetScore = s.dbnetBoxScoreThresh
            dbnetUnclip = s.dbnetUnclipRatio
            mangaOcrDbnetUnclip = s.mangaOcrDbnetUnclipRatio
            dbnetGap = s.bubbleClusterGap
            manualTextOrient = s.manualTextOrientation
            cleartextHostsText = s.cleartextAllowedHosts.joinToString("\n")
            // 同一个 snapshot 内 capture 初始 Settings——既走 buildSnapshot() 单源路径，
            // 又跟所有 state 在同一原子 apply 里，不会被中间帧看到。
            initialSettings = buildSnapshot()
        }
    }

    // paddleStatus 独立异步加载：file.exists() / file.length() 走 IO 线程，避免阻塞首帧。
    val settingsLoaded = initialSettings != null
    LaunchedEffect(settingsLoaded, paddleModelVersion) {
        if (!settingsLoaded) return@LaunchedEffect
        refreshPaddleModelState(paddleModelVersion)
    }
    // 同理 mangaOcrStatus；manga-ocr 7 个文件 stat 也走 IO。
    LaunchedEffect(settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        refreshMangaOcrModelState()
    }
    LaunchedEffect(settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        refreshOrientationModelState()
    }
    LaunchedEffect(settingsLoaded, translationPresets) {
        if (!settingsLoaded) return@LaunchedEffect
        refreshPresetModelReadiness()
    }

    val closeSearch: () -> Unit = {
        searchActive = false
        searchQuery = ""
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                        LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
                    } else {
                        Text(stringResource(R.string.settings_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (searchActive) closeSearch else tryBack) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (searchActive) R.string.settings_search_close else R.string.common_back
                            )
                        )
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.settings_search_btn)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // 防护：load 完成前 state 是默认占位值，此时保存会把空字符串 / 默认 enum
                    // 写入 DataStore，覆盖用户实际数据。LaunchedEffect 完成（~13ms）才把
                    // initialSettings 设值，那之后才允许保存。
                    if (initialSettings == null) return@ExtendedFloatingActionButton
                    scope.launch { doSave(); onBack() }
                },
                icon = { Icon(Icons.Default.Save, contentDescription = null) },
                text = { Text(stringResource(if (dirty) R.string.settings_save_btn else R.string.settings_saved_btn)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            // 直接 inflate Column——不显示 spinner，避免"按下设置 → spinner → UI"那段空白卡顿感。
            // state 默认值（空字符串 / 默认 enum）会先短暂显示，LaunchedEffect 在 ~13ms 内 Snapshot
            // 原子更新所有 state 到实际保存值——肉眼几乎不察觉闪烁。代价：用户在 initialSettings
            // 还是 null 时点保存按钮会用默认值覆盖数据，所以下面 FAB 加了 enabled 防护。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // —— 应用语言 ——
            SectionCard(title = stringResource(R.string.settings_section_app_lang), anchorKey = SectionKeys.APP_LANG, onAnchor = onAnchor) {
                AppLanguageSelector()
            }

            // —— 主题模式 ——
            SectionCard(title = stringResource(R.string.settings_section_theme_mode), anchorKey = SectionKeys.THEME_MODE, onAnchor = onAnchor) {
                ThemeModeSelector()
            }

            translationPresetSection()

            // —— 翻译后端 ——
            SectionCard(title = stringResource(R.string.settings_section_translator), anchorKey = SectionKeys.TRANSLATE, onAnchor = onAnchor) {
                Text(stringResource(R.string.settings_label_translator_engine), style = MaterialTheme.typography.labelLarge)

                // 与 OCR 一样分组：端侧 LLM / 云端 LLM / 云端翻译。chip 多了平铺 FlowRow 也能换行，
                // 但用户难以一眼区分 LLM 类（重质量、配 prompt）与传统翻译 API 类（重稳定性、限频）。
                Text(
                    stringResource(R.string.settings_translator_group_local_llm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(
                        translatorEngine,
                        TranslatorEngine.LOCAL_SAKURA,
                        stringResource(R.string.settings_engine_local_sakura),
                        enabled = localLlmDeviceCapable
                    ) { selectTranslatorEngine(it) }
                    EngineChip(
                        translatorEngine,
                        TranslatorEngine.LOCAL_HY_MT2,
                        stringResource(R.string.settings_engine_local_hymt2),
                        enabled = localLlmDeviceCapable
                    ) { selectTranslatorEngine(it) }
                }
                Text(
                    stringResource(R.string.settings_translator_group_cloud_llm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(translatorEngine, TranslatorEngine.OPENAI, stringResource(R.string.settings_engine_openai_llm)) { translatorEngine = it }
                }
                Text(
                    stringResource(R.string.settings_translator_group_cloud),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(translatorEngine, TranslatorEngine.DEEPL, stringResource(R.string.settings_engine_deepl)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.GOOGLE, stringResource(R.string.settings_engine_google)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.VOLC, stringResource(R.string.settings_engine_volc)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.BAIDU_FANYI, stringResource(R.string.settings_engine_baidu_fanyi)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.TENCENT, stringResource(R.string.settings_engine_tencent)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.YOUDAO_PICTRANS, stringResource(R.string.settings_engine_youdao_pictrans)) { translatorEngine = it }
                }
                // 切换引擎时清掉上一引擎的测试结果——继续显示会让用户以为新引擎"已经测过"。
                LaunchedEffect(translatorEngine) {
                    testMessage = null
                    testSuccess = false
                    fetchedModels = emptyList()
                    modelPickerExpanded = false
                    // 切到 LOCAL_* 时刷新模型状态文案；切走时不动（保留上次结果，少做无谓 IO）。
                    localLlmModelKindFor(translatorEngine)?.let { kind ->
                        refreshLlmModelState(kind)
                    } ?: run {
                        llmModelReady = false
                    }
                }

                // 端侧 LLM 翻译区块。当前支持 Sakura（日译中）和 Hy-MT2（多语种）。
                val currentKind = localLlmModelKindFor(translatorEngine)
                if (currentKind != null) {
                    LocalLlmSection(
                        currentKindLabel = currentKind.displayName,
                        deviceCapable = localLlmDeviceCapable,
                        modelReady = llmModelReady,
                        status = llmModelStatus.ifBlank { stringResource(R.string.llm_status_missing) },
                        downloading = llmDownloading,
                        onDownload = {
                            requestModelDownload(currentKind.displayName) {
                                scope.launch {
                                    llmDownloading = true
                                    try {
                                        viewModel.saveLlmMirror(llmMirrorChoice, llmMirror)
                                        viewModel.downloadLlmModel(currentKind) { msg -> llmModelStatus = msg }
                                        refreshLlmModelState(currentKind)
                                    } catch (t: Throwable) {
                                        llmModelStatus = "${t.javaClass.simpleName}: ${t.message}"
                                        llmModelReady = withContext(Dispatchers.IO) {
                                            viewModel.llmModelReady(currentKind)
                                        }
                                    } finally {
                                        llmDownloading = false
                                    }
                                }
                            }
                        },
                        onImport = { uris ->
                            scope.launch {
                                llmDownloading = true
                                try {
                                    val n = viewModel.importLlmFromLocal(uris, currentKind)
                                    refreshLlmModelState(currentKind)
                                    Timber.i("imported $n LLM model file(s) from local")
                                } finally {
                                    llmDownloading = false
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                viewModel.deleteLlmModel(currentKind)
                                refreshLlmModelState(currentKind)
                            }
                        }
                    )
                }

                if (translatorEngine == TranslatorEngine.OPENAI) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.settings_base_url)) },
                        placeholder = { Text(stringResource(R.string.settings_base_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    SecretTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = stringResource(R.string.settings_api_key),
                        placeholder = stringResource(R.string.settings_api_key_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text(stringResource(R.string.settings_model)) },
                        placeholder = { Text(stringResource(R.string.settings_model_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    // 测试连接成功时，下面这块允许从拉到的 model 列表里选一个回填到 model 字段。
                    if (fetchedModels.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = modelPickerExpanded,
                            onExpandedChange = { modelPickerExpanded = !modelPickerExpanded }
                        ) {
                            OutlinedTextField(
                                value = "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.settings_test_pick_model)) },
                                placeholder = { Text("${fetchedModels.size} models") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelPickerExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = modelPickerExpanded,
                                onDismissRequest = { modelPickerExpanded = false }
                            ) {
                                fetchedModels.forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text(id) },
                                        onClick = {
                                            model = id
                                            modelPickerExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else if (translatorEngine == TranslatorEngine.DEEPL) {
                    SecretTextField(
                        value = deeplKey, onValueChange = { deeplKey = it },
                        label = stringResource(R.string.settings_deepl_api_key),
                        placeholder = stringResource(R.string.settings_deepl_key_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(
                        stringResource(R.string.settings_deepl_use_pro),
                        deeplPro,
                        // OFFICIAL / AUTO 协议都会走官方端点（AUTO 用作 fallback），Pro 都生效；纯 DEEPLX 协议下 Pro 无意义
                        enabled = deeplProtocol != com.gameocr.app.data.DeeplProtocol.DEEPLX
                    ) { deeplPro = it }
                    Text(
                        stringResource(R.string.settings_deepl_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // —— 高级（自架 / deeplx）——
                    // 折叠掉避免吓到只用官方 DeepL 的用户；展开有自定义 URL + Bearer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deeplAdvancedExpanded = !deeplAdvancedExpanded }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (deeplAdvancedExpanded) "▼ " else "▶ ") +
                                stringResource(R.string.settings_deepl_advanced_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (deeplAdvancedExpanded) {
                        Text(
                            stringResource(R.string.settings_deepl_protocol_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EngineChip(deeplProtocol, com.gameocr.app.data.DeeplProtocol.OFFICIAL,
                                stringResource(R.string.settings_deepl_protocol_official)) { deeplProtocol = it }
                            EngineChip(deeplProtocol, com.gameocr.app.data.DeeplProtocol.DEEPLX,
                                stringResource(R.string.settings_deepl_protocol_deeplx)) { deeplProtocol = it }
                            EngineChip(deeplProtocol, com.gameocr.app.data.DeeplProtocol.AUTO,
                                stringResource(R.string.settings_deepl_protocol_auto)) { deeplProtocol = it }
                        }
                        Text(
                            stringResource(when (deeplProtocol) {
                                com.gameocr.app.data.DeeplProtocol.OFFICIAL -> R.string.settings_deepl_protocol_official_hint
                                com.gameocr.app.data.DeeplProtocol.DEEPLX -> R.string.settings_deepl_protocol_deeplx_hint
                                com.gameocr.app.data.DeeplProtocol.AUTO -> R.string.settings_deepl_protocol_auto_hint
                            }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = deeplBaseUrl,
                            onValueChange = { deeplBaseUrl = it },
                            label = { Text(stringResource(R.string.settings_deepl_base_url)) },
                            placeholder = { Text(stringResource(R.string.settings_deepl_base_url_placeholder)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Text(
                            stringResource(R.string.settings_deepl_base_url_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SecretTextField(
                            value = deeplCustomToken,
                            onValueChange = { deeplCustomToken = it },
                            label = stringResource(R.string.settings_deepl_custom_token),
                            placeholder = stringResource(R.string.settings_deepl_custom_token_placeholder),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.settings_deepl_custom_token_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SwitchRow(
                            stringResource(R.string.settings_deepl_bearer_label),
                            deeplBearerAuth,
                            // DEEPLX / AUTO 都用 customToken，Bearer 才有意义；OFFICIAL 不读
                            enabled = deeplProtocol != com.gameocr.app.data.DeeplProtocol.OFFICIAL
                        ) { deeplBearerAuth = it }
                        Text(
                            stringResource(R.string.settings_deepl_bearer_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(
                                if (deeplProtocol != com.gameocr.app.data.DeeplProtocol.OFFICIAL) 1f else 0.4f
                            )
                        )
                    }
                } else if (translatorEngine == TranslatorEngine.YOUDAO_PICTRANS) {
                    // YOUDAO_PICTRANS：端到端引擎，OCR + 翻译一起出，会绕过 ocrEngine 设置
                    SecretTextField(
                        value = youdaoAppKey, onValueChange = { youdaoAppKey = it },
                        label = stringResource(R.string.settings_youdao_app_key),
                        placeholder = stringResource(R.string.settings_youdao_app_key_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecretTextField(
                        value = youdaoAppSecret, onValueChange = { youdaoAppSecret = it },
                        label = stringResource(R.string.settings_youdao_app_secret),
                        placeholder = stringResource(R.string.settings_youdao_app_secret_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_youdao_pictrans_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (translatorEngine == TranslatorEngine.VOLC) {
                    // 火山引擎机器翻译：AK + SK + region；SignV4 鉴权
                    SecretTextField(
                        value = volcAk, onValueChange = { volcAk = it },
                        label = stringResource(R.string.settings_volc_access_key_id),
                        placeholder = stringResource(R.string.settings_volc_ak_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecretTextField(
                        value = volcSk, onValueChange = { volcSk = it },
                        label = stringResource(R.string.settings_volc_secret_access_key),
                        placeholder = stringResource(R.string.settings_volc_sk_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = volcRegion, onValueChange = { volcRegion = it },
                        label = { Text(stringResource(R.string.settings_volc_region)) },
                        placeholder = { Text("cn-north-1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_volc_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (translatorEngine == TranslatorEngine.BAIDU_FANYI) {
                    // 百度翻译开放平台（fanyi-api.baidu.com）—— 与百度智能云 OCR 不是一回事
                    OutlinedTextField(
                        value = baiduFanyiAppId, onValueChange = { baiduFanyiAppId = it },
                        label = { Text(stringResource(R.string.settings_baidu_fanyi_app_id)) },
                        placeholder = { Text(stringResource(R.string.settings_baidu_fanyi_app_id_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecretTextField(
                        value = baiduFanyiSecret, onValueChange = { baiduFanyiSecret = it },
                        label = stringResource(R.string.settings_baidu_fanyi_secret_key),
                        placeholder = stringResource(R.string.settings_baidu_fanyi_secret_key_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_baidu_fanyi_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (translatorEngine == TranslatorEngine.TENCENT) {
                    // 腾讯云翻译：与 OCR 共用同一套 SecretId/Key/Region（state 双向绑定，
                    // 在这里改和在 OCR 区改完全等价）。region 默认 ap-guangzhou，TMT 各地域通用。
                    OutlinedTextField(
                        value = tencentId, onValueChange = { tencentId = it },
                        label = { Text(stringResource(R.string.settings_tencent_id_label)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    SecretTextField(
                        value = tencentKey, onValueChange = { tencentKey = it },
                        label = stringResource(R.string.settings_tencent_key_label),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tencentRegion, onValueChange = { tencentRegion = it },
                        label = { Text(stringResource(R.string.settings_tencent_region)) },
                        placeholder = { Text("ap-guangzhou") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text(
                        stringResource(R.string.settings_tencent_trans_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (translatorEngine == TranslatorEngine.GOOGLE) {
                    // GOOGLE：无 key，仅提示风险。改 else if 明确匹配——避免后续新增枚举（如 LOCAL_*）
                    // 落入 else 兜底，错误显示 Google 文案。
                    Text(
                        stringResource(R.string.settings_google_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // —— 测试连接 ——
                // 验证 baseUrl/key/model（或 DeepL key/endpoint）能不能用；DeepL 顺便返回剩余额度，
                // OpenAI 顺便拉 model 列表回填到上方下拉。状态文字按成功/失败着色，下次点击覆盖。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !testRunning,
                        onClick = {
                            testRunning = true
                            testMessage = null
                            scope.launch {
                                val result = viewModel.testTranslator(
                                    translatorEngine = translatorEngine,
                                    baseUrl = baseUrl,
                                    apiKey = apiKey,
                                    model = model,
                                    deeplKey = deeplKey,
                                    deeplPro = deeplPro,
                                    deeplProtocol = deeplProtocol,
                                    deeplBaseUrl = deeplBaseUrl,
                                    deeplBearerAuth = deeplBearerAuth,
                                    deeplCustomToken = deeplCustomToken,
                                    youdaoAppKey = youdaoAppKey,
                                    youdaoAppSecret = youdaoAppSecret,
                                    apiTimeoutSeconds = apiTimeoutSec.toInt(),
                                    volcAccessKeyId = volcAk,
                                    volcSecretAccessKey = volcSk,
                                    volcRegion = volcRegion,
                                    baiduFanyiAppId = baiduFanyiAppId,
                                    baiduFanyiSecretKey = baiduFanyiSecret,
                                    tencentSecretId = tencentId,
                                    tencentSecretKey = tencentKey,
                                    tencentRegion = tencentRegion
                                )
                                testRunning = false
                                testSuccess = result.success
                                testMessage = result.message
                                if (result.success && result.models.isNotEmpty()) {
                                    fetchedModels = result.models
                                }
                            }
                        }
                    ) {
                        Text(
                            if (testRunning) stringResource(R.string.settings_test_testing)
                            else stringResource(R.string.settings_test_connection)
                        )
                    }
                    if (testRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                testMessage?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testSuccess) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                val onTogglePin: (String) -> Unit = { code ->
                    // 乐观更新本地 + 异步落盘。togglePinLanguage 内部用 repo.update 是原子的。
                    pinnedLanguages = if (pinnedLanguages.contains(code))
                        pinnedLanguages - code else pinnedLanguages + code
                    scope.launch { viewModel.togglePinLanguage(code) }
                }
                LanguagePicker(
                    label = stringResource(R.string.settings_source_lang),
                    currentCode = sourceLang,
                    onSelect = {
                        timber.log.Timber.tag("OcrLangLink").i(
                            "[user-select-source] %s -> %s", sourceLang, it
                        )
                        sourceLang = it
                        if (translatorEngine == TranslatorEngine.LOCAL_SAKURA && !supportsSakuraLanguagePair(it, targetLang)) {
                            showSakuraFallbackDialog = true
                        }
                    },
                    pinned = pinnedLanguages,
                    onTogglePin = onTogglePin
                )
                LanguagePicker(
                    label = stringResource(R.string.settings_target_lang),
                    currentCode = targetLang,
                    onSelect = {
                        targetLang = it
                        if (translatorEngine == TranslatorEngine.LOCAL_SAKURA && !supportsSakuraLanguagePair(sourceLang, it)) {
                            showSakuraFallbackDialog = true
                        }
                    },
                    pinned = pinnedLanguages,
                    onTogglePin = onTogglePin
                )
                // Prompt / 流式开关只对 LLM 类（OpenAI 兼容）翻译引擎有意义；
                // DeepL 是机器翻译 API，不读 prompt、也不走 SSE，隐藏避免误导。
                if (translatorEngine == TranslatorEngine.OPENAI) {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        label = { Text(stringResource(R.string.settings_prompt_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3, maxLines = 6
                    )

                    // Prompt 健康检查：缺占位符时显眼提示，可能时给一键修复按钮
                    // （把已硬编码的目标/源语言字样替换为 {target}/{source} 占位符）
                    val hasTargetPlaceholder = prompt.contains("{target}") || prompt.contains("{target_lang}")
                    val hasSourcePlaceholder = prompt.contains("{source}") || prompt.contains("{source_lang}")
                    val targetName = com.gameocr.app.data.Languages.nameOf(context, targetLang)
                    val sourceName = com.gameocr.app.data.Languages.nameOf(context, sourceLang)
                    val autoName = com.gameocr.app.data.Languages.nameOf(context, com.gameocr.app.data.Languages.AUTO.code)
                    val canFixTarget = !hasTargetPlaceholder && targetName.isNotBlank() &&
                        prompt.contains(targetName)
                    val canFixSource = !hasSourcePlaceholder && sourceName.isNotBlank() &&
                        sourceName != autoName && prompt.contains(sourceName)
                    if (!hasTargetPlaceholder || !hasSourcePlaceholder) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val missingPart = buildString {
                                    if (!hasTargetPlaceholder) append("{target}")
                                    if (!hasTargetPlaceholder && !hasSourcePlaceholder) append(" / ")
                                    if (!hasSourcePlaceholder) append("{source}")
                                }
                                Text(
                                    stringResource(R.string.settings_prompt_warn_missing_format, missingPart),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    stringResource(R.string.settings_prompt_warn_hint_format, targetName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (canFixTarget) {
                                    TextButton(onClick = {
                                        prompt = prompt.replace(targetName, "{target}")
                                    }) { Text(stringResource(R.string.settings_prompt_replace_target_format, targetName)) }
                                }
                                if (canFixSource) {
                                    TextButton(onClick = {
                                        prompt = prompt.replace(sourceName, "{source}")
                                    }) { Text(stringResource(R.string.settings_prompt_replace_source_format, sourceName)) }
                                }
                            }
                        }
                    }

                    // 二次确认对话框：自定义 prompt 一旦覆盖无法撤销，避免误点。
                    var showResetMainPromptDialog by remember { mutableStateOf(false) }
                    var showResetDictPromptDialog by remember { mutableStateOf(false) }
                    val defaultPrompt = stringResource(R.string.default_prompt)
                    TextButton(onClick = { showResetMainPromptDialog = true }) {
                        Text(stringResource(R.string.settings_prompt_reset))
                    }
                    if (showResetMainPromptDialog) {
                        AlertDialog(
                            onDismissRequest = { showResetMainPromptDialog = false },
                            title = { Text(stringResource(R.string.settings_prompt_reset_confirm_title)) },
                            text = { Text(stringResource(R.string.settings_reset_confirm_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    prompt = defaultPrompt
                                    showResetMainPromptDialog = false
                                }) { Text(stringResource(R.string.settings_reset_confirm_yes)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetMainPromptDialog = false }) {
                                    Text(stringResource(R.string.settings_reset_confirm_no))
                                }
                            }
                        )
                    }
                    SwitchRow(stringResource(R.string.settings_streaming), streaming) { streaming = it }

                    // 划词翻译词典 Prompt（仅 OpenAI 兼容引擎用，跟主翻译 Prompt 同源所以放一起）
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.settings_dictionary_prompt_title),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        stringResource(R.string.settings_dictionary_prompt_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = dictionaryPrompt,
                        onValueChange = { v ->
                            dictionaryPrompt = v
                            scope.launch { viewModel.saveDictionaryPrompt(v) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 12
                    )
                    val defaultDictPrompt = stringResource(R.string.default_dictionary_prompt)
                    TextButton(onClick = { showResetDictPromptDialog = true }) {
                        Text(stringResource(R.string.settings_dictionary_prompt_reset))
                    }
                    if (showResetDictPromptDialog) {
                        AlertDialog(
                            onDismissRequest = { showResetDictPromptDialog = false },
                            title = { Text(stringResource(R.string.settings_dictionary_prompt_reset_confirm_title)) },
                            text = { Text(stringResource(R.string.settings_reset_confirm_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    dictionaryPrompt = defaultDictPrompt
                                    scope.launch { viewModel.saveDictionaryPrompt(defaultDictPrompt) }
                                    showResetDictPromptDialog = false
                                }) { Text(stringResource(R.string.settings_reset_confirm_yes)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetDictPromptDialog = false }) {
                                    Text(stringResource(R.string.settings_reset_confirm_no))
                                }
                            }
                        )
                    }
                }
            }

            // —— OCR 引擎 ——
            // 端到端翻译引擎（有道图翻）会跳过 OCR 阶段，整个 OCR 设置区当前会被无视——
            // 灰显 + 禁用 chip 让用户一眼明白 + 不能误操作。
            val ocrSectionDisabled = translatorEngine == TranslatorEngine.YOUDAO_PICTRANS
            SectionCard(
                title = stringResource(R.string.settings_section_ocr),
                anchorKey = SectionKeys.OCR,
                onAnchor = onAnchor,
                helpText = stringResource(R.string.settings_ocr_intro)
            ) {
                if (ocrSectionDisabled) {
                    Text(
                        stringResource(R.string.settings_ocr_disabled_by_pictrans),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.alpha(if (ocrSectionDisabled) 0.5f else 1f)
                ) {
                // 分组改成 端侧 / 云端 两组 FlowRow——chip 多了 Row 横向溢出会挤掉末尾的 chip
                // （Paddle 之前就被挤没了）。FlowRow 自适应换行不丢任何 chip。
                Text(
                    stringResource(R.string.settings_ocr_group_local),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_AUTO, stringResource(R.string.settings_ocr_chip_auto), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_JAPANESE, stringResource(R.string.settings_ocr_chip_japanese), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_KOREAN, stringResource(R.string.settings_ocr_chip_korean), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_CHINESE, stringResource(R.string.settings_ocr_chip_chinese), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.ML_KIT_LATIN, stringResource(R.string.settings_ocr_chip_latin), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.PADDLE_ONNX, stringResource(R.string.settings_ocr_chip_paddle), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.MANGA_OCR_JA, stringResource(R.string.settings_ocr_chip_manga_ocr_ja), enabled = !ocrSectionDisabled) { ocrEngine = it }
                }
                Text(
                    stringResource(R.string.settings_ocr_group_local_http),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.UMI_OCR, stringResource(R.string.settings_ocr_chip_umi), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.LUNA_OCR, stringResource(R.string.settings_ocr_chip_luna), enabled = !ocrSectionDisabled) { ocrEngine = it }
                }
                Text(
                    stringResource(R.string.settings_ocr_group_cloud),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(ocrEngine, OcrEngineKind.BAIDU, stringResource(R.string.settings_ocr_chip_baidu), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.TENCENT, stringResource(R.string.settings_ocr_chip_tencent), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.YOUDAO, stringResource(R.string.settings_ocr_chip_youdao), enabled = !ocrSectionDisabled) { ocrEngine = it }
                    EngineChip(ocrEngine, OcrEngineKind.PADDLE_AI_STUDIO, stringResource(R.string.settings_ocr_chip_paddle_ai_studio), enabled = !ocrSectionDisabled) { ocrEngine = it }
                }
                if (ocrEngine == OcrEngineKind.BAIDU) {
                    SecretTextField(
                        value = baiduKey, onValueChange = { baiduKey = it },
                        label = stringResource(R.string.settings_baidu_api_key),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecretTextField(
                        value = baiduSecret, onValueChange = { baiduSecret = it },
                        label = stringResource(R.string.settings_baidu_secret_key),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_baidu_endpoint_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.GENERAL_BASIC, stringResource(R.string.settings_baidu_endpoint_standard)) { baiduEndpoint = it }
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.GENERAL, stringResource(R.string.settings_baidu_endpoint_standard_loc)) { baiduEndpoint = it }
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.WEBIMAGE, stringResource(R.string.settings_baidu_endpoint_webimage)) { baiduEndpoint = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.ACCURATE_BASIC, stringResource(R.string.settings_baidu_endpoint_accurate)) { baiduEndpoint = it }
                        EngineChip(baiduEndpoint, com.gameocr.app.data.BaiduOcrEndpoint.ACCURATE, stringResource(R.string.settings_baidu_endpoint_accurate_loc)) { baiduEndpoint = it }
                    }
                    // 除 webimage 外四个端点都支持 language_type：
                    //  - 标准系（general_basic / general）只支持 10 种主流
                    //  - 高精度系（accurate_basic / accurate）支持全 25 种
                    val baiduAcceptsLang = baiduEndpoint != com.gameocr.app.data.BaiduOcrEndpoint.WEBIMAGE
                    if (baiduAcceptsLang) {
                        EnumLanguagePicker(
                            label = stringResource(R.string.settings_baidu_lang_label),
                            current = baiduLanguage,
                            options = com.gameocr.app.data.BaiduOcrLanguage.entries
                                .filter { it.supportedOn(baiduEndpoint) },
                            labelResOf = { it.displayNameRes },
                            bcp47Of = { it.bcp47 },
                            pinnedBcp47 = pinnedLanguages,
                            onTogglePin = { code -> scope.launch { viewModel.togglePinLanguage(code) } },
                            onSelect = { baiduLanguage = it }
                        )
                    } else {
                        Text(
                            stringResource(R.string.settings_baidu_lang_loc_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        stringResource(
                            R.string.settings_baidu_current_format,
                            stringResource(baiduEndpoint.displayNameRes),
                            stringResource(baiduEndpoint.freeQuotaRes),
                            stringResource(if (baiduEndpoint.hasLocation) R.string.settings_baidu_with_loc_hint else R.string.settings_baidu_no_loc_hint)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.settings_baidu_image_limit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (ocrEngine == OcrEngineKind.UMI_OCR) {
                    OutlinedTextField(
                        value = umiOcrBaseUrl,
                        onValueChange = { umiOcrBaseUrl = it },
                        label = { Text(stringResource(R.string.settings_umi_ocr_url_label)) },
                        placeholder = { Text(stringResource(R.string.settings_umi_ocr_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Text(
                        stringResource(R.string.settings_umi_ocr_url_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ocrEngine == OcrEngineKind.LUNA_OCR) {
                    OutlinedTextField(
                        value = lunaOcrBaseUrl,
                        onValueChange = { lunaOcrBaseUrl = it },
                        label = { Text(stringResource(R.string.settings_luna_ocr_url_label)) },
                        placeholder = { Text(stringResource(R.string.settings_luna_ocr_url_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Text(
                        stringResource(R.string.settings_luna_ocr_url_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ocrEngine == OcrEngineKind.TENCENT) {
                    OutlinedTextField(
                        value = tencentId, onValueChange = { tencentId = it },
                        label = { Text(stringResource(R.string.settings_tencent_id_label)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    SecretTextField(
                        value = tencentKey, onValueChange = { tencentKey = it },
                        label = stringResource(R.string.settings_tencent_key_label),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tencentRegion, onValueChange = { tencentRegion = it },
                        label = { Text(stringResource(R.string.settings_tencent_region)) },
                        placeholder = { Text("ap-guangzhou") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text(
                        stringResource(R.string.settings_tencent_endpoint_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        EngineChip(tencentEndpoint, com.gameocr.app.data.TencentOcrEndpoint.GENERAL_BASIC, stringResource(R.string.settings_tencent_endpoint_general_basic)) { tencentEndpoint = it }
                        EngineChip(tencentEndpoint, com.gameocr.app.data.TencentOcrEndpoint.GENERAL_ACCURATE, stringResource(R.string.settings_tencent_endpoint_general_accurate)) { tencentEndpoint = it }
                        EngineChip(tencentEndpoint, com.gameocr.app.data.TencentOcrEndpoint.RECOGNIZE_AGENT, stringResource(R.string.settings_tencent_endpoint_recognize_agent)) { tencentEndpoint = it }
                    }
                    // 只有 GeneralBasicOCR 真正接受 LanguageType；其它端点不显示选择器并给提示
                    if (tencentEndpoint == com.gameocr.app.data.TencentOcrEndpoint.GENERAL_BASIC) {
                        EnumLanguagePicker(
                            label = stringResource(R.string.settings_tencent_lang_label),
                            current = tencentLanguage,
                            options = com.gameocr.app.data.TencentOcrLanguage.entries,
                            labelResOf = { it.displayNameRes },
                            bcp47Of = { it.bcp47 },
                            pinnedBcp47 = pinnedLanguages,
                            onTogglePin = { code -> scope.launch { viewModel.togglePinLanguage(code) } },
                            onSelect = { tencentLanguage = it }
                        )
                    } else if (tencentEndpoint == com.gameocr.app.data.TencentOcrEndpoint.GENERAL_ACCURATE) {
                        Text(
                            stringResource(R.string.settings_tencent_lang_accurate_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        stringResource(
                            R.string.settings_tencent_current_format,
                            stringResource(tencentEndpoint.displayNameRes),
                            stringResource(tencentEndpoint.descRes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ocrEngine == OcrEngineKind.YOUDAO) {
                    SecretTextField(
                        value = youdaoAppKey, onValueChange = { youdaoAppKey = it },
                        label = stringResource(R.string.settings_youdao_app_key),
                        placeholder = stringResource(R.string.settings_youdao_app_key_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecretTextField(
                        value = youdaoAppSecret, onValueChange = { youdaoAppSecret = it },
                        label = stringResource(R.string.settings_youdao_app_secret),
                        placeholder = stringResource(R.string.settings_youdao_app_secret_placeholder),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.settings_youdao_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ocrEngine == OcrEngineKind.PADDLE_AI_STUDIO) {
                    SecretTextField(
                        value = paddleAiStudioToken,
                        onValueChange = { paddleAiStudioToken = it },
                        label = stringResource(R.string.settings_paddle_ai_studio_token),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (ocrEngine == OcrEngineKind.PADDLE_ONNX) {
                    PaddleSection(
                        status = paddleStatus,
                        downloading = paddleDownloading,
                        modelReady = paddleModelReady,
                        modelVersion = paddleModelVersion,
                        onModelVersionChange = { newVer ->
                            paddleModelVersion = newVer
                            paddleModelReady = false
                            scope.launch { viewModel.savePaddleModelVersion(newVer) }
                        },
                        onDownload = {
                            requestModelDownload(context.getString(paddleModelVersion.displayNameRes)) {
                                scope.launch {
                                    paddleDownloading = true
                                    try {
                                        viewModel.downloadPaddleModels(paddleModelVersion) { msg -> paddleStatus = msg }
                                        refreshPaddleModelState(paddleModelVersion)
                                    } catch (t: Throwable) {
                                        paddleStatus = context.getString(
                                            R.string.settings_paddle_download_failed_format,
                                            t.message ?: ""
                                        )
                                        paddleModelReady = withContext(Dispatchers.IO) {
                                            viewModel.isPaddleInstalled(paddleModelVersion)
                                        }
                                    } finally {
                                        paddleDownloading = false
                                    }
                                }
                            }
                        },
                        onImport = { uris ->
                            scope.launch {
                                paddleDownloading = true
                                try {
                                    val n = viewModel.importPaddleFromLocal(paddleModelVersion, uris)
                                    val state = withContext(Dispatchers.IO) {
                                        viewModel.paddleModelUiState(paddleModelVersion)
                                    }
                                    paddleStatus = context.getString(
                                        R.string.settings_paddle_imported_format,
                                        n, state.status
                                    )
                                    paddleModelReady = state.ready
                                } finally {
                                    paddleDownloading = false
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                viewModel.deletePaddleModels()
                                refreshPaddleModelState(paddleModelVersion)
                            }
                        }
                    )
                }

                if (ocrEngine == OcrEngineKind.MANGA_OCR_JA) {
                    MangaOcrSection(
                        status = mangaOcrStatus,
                        downloading = mangaOcrDownloading,
                        modelReady = mangaOcrModelReady,
                        onDownload = {
                            requestModelDownload(context.getString(R.string.settings_manga_ocr_model_name)) {
                                scope.launch {
                                mangaOcrDownloading = true
                                try {
                                    viewModel.downloadMangaOcrModels { msg -> mangaOcrStatus = msg }
                                    refreshMangaOcrModelState()
                                    // manga-ocr 复用 PaddleOCR DBNet 做检测；如果 Paddle 还没下，级联拉一遍，
                                    // 用户少点一次"下载 PaddleOCR"按钮。失败不影响 manga-ocr 状态显示。
                                    if (!viewModel.isPaddleInstalled(paddleModelVersion)) {
                                        try {
                                            viewModel.downloadPaddleModels(paddleModelVersion) { msg -> paddleStatus = msg }
                                            refreshPaddleModelState(paddleModelVersion)
                                        } catch (t: Throwable) {
                                            Timber.w(t, "cascade Paddle download after manga-ocr failed")
                                            paddleModelReady = withContext(Dispatchers.IO) {
                                                viewModel.isPaddleInstalled(paddleModelVersion)
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    mangaOcrStatus = context.getString(
                                        R.string.settings_manga_ocr_download_failed_format,
                                        t.message ?: t.javaClass.simpleName
                                    )
                                    mangaOcrModelReady = withContext(Dispatchers.IO) {
                                        viewModel.mangaOcrModelReady()
                                    }
                                } finally {
                                    mangaOcrDownloading = false
                                }
                            }
                            }
                        },
                        onImport = { uris ->
                            scope.launch {
                                mangaOcrDownloading = true
                                try {
                                    val n = viewModel.importMangaOcrFromLocal(uris)
                                    val state = withContext(Dispatchers.IO) { viewModel.mangaOcrModelUiState() }
                                    mangaOcrStatus = context.getString(
                                        R.string.settings_manga_ocr_imported_format,
                                        n, state.status
                                    )
                                    mangaOcrModelReady = state.ready
                                } finally {
                                    mangaOcrDownloading = false
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                viewModel.deleteMangaOcrModels()
                                refreshMangaOcrModelState()
                            }
                        }
                    )
                }

                // DBNet post-process tuning is only relevant for PaddleOCR / MangaOCR.
                if (ocrEngine == OcrEngineKind.PADDLE_ONNX || ocrEngine == OcrEngineKind.MANGA_OCR_JA) {
                    val isMangaOcrDbnet = ocrEngine == OcrEngineKind.MANGA_OCR_JA
                    val currentDbnetUnclip = if (isMangaOcrDbnet) mangaOcrDbnetUnclip else dbnetUnclip
                    val defaultSettings = Settings()
                    val defaultDbnetUnclip = if (isMangaOcrDbnet) {
                        defaultSettings.mangaOcrDbnetUnclipRatio
                    } else {
                        defaultSettings.dbnetUnclipRatio
                    }
                    val saveDbnetNow: () -> Unit = {
                        scope.launch {
                            val unclipToSave = if (ocrEngine == OcrEngineKind.MANGA_OCR_JA) {
                                mangaOcrDbnetUnclip
                            } else {
                                dbnetUnclip
                            }
                            viewModel.saveDbnetThresholds(
                                ocrEngine,
                                dbnetProb,
                                dbnetScore,
                                unclipToSave,
                                dbnetGap
                            )
                        }
                    }
                    val resetDbnetDefaults: () -> Unit = {
                        dbnetProb = defaultSettings.dbnetProbThresh
                        dbnetScore = defaultSettings.dbnetBoxScoreThresh
                        if (isMangaOcrDbnet) {
                            mangaOcrDbnetUnclip = defaultSettings.mangaOcrDbnetUnclipRatio
                        } else {
                            dbnetUnclip = defaultSettings.dbnetUnclipRatio
                        }
                        dbnetGap = defaultSettings.bubbleClusterGap
                        scope.launch {
                            viewModel.saveDbnetThresholds(
                                ocrEngine,
                                defaultSettings.dbnetProbThresh,
                                defaultSettings.dbnetBoxScoreThresh,
                                defaultDbnetUnclip,
                                defaultSettings.bubbleClusterGap
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dbnetAdvancedExpanded = !dbnetAdvancedExpanded }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (if (dbnetAdvancedExpanded) "▼ " else "▶ ") +
                                stringResource(R.string.settings_dbnet_advanced_header),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (dbnetAdvancedExpanded) {
                        Text(
                            stringResource(R.string.settings_dbnet_section_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            OutlinedButton(onClick = { showDbnetResetConfirm = true }) {
                                Text(stringResource(R.string.settings_dbnet_restore_defaults))
                            }
                        }
                        DbnetAdvancedSliderSection(
                            title = stringResource(R.string.settings_dbnet_prob_label, dbnetProb),
                            description = stringResource(R.string.settings_dbnet_prob_desc),
                            value = dbnetProb,
                            onValueChange = { dbnetProb = it },
                            onValueChangeFinished = saveDbnetNow,
                            valueRange = 0.10f..0.40f,
                            steps = 29
                        )
                        DbnetAdvancedSliderSection(
                            title = stringResource(R.string.settings_dbnet_score_label, dbnetScore),
                            description = stringResource(R.string.settings_dbnet_score_desc),
                            value = dbnetScore,
                            onValueChange = { dbnetScore = it },
                            onValueChangeFinished = saveDbnetNow,
                            valueRange = 0.20f..0.70f,
                            steps = 49
                        )
                        DbnetAdvancedSliderSection(
                            title = stringResource(R.string.settings_dbnet_unclip_label, currentDbnetUnclip),
                            description = stringResource(R.string.settings_dbnet_unclip_desc),
                            value = currentDbnetUnclip,
                            onValueChange = {
                                if (isMangaOcrDbnet) {
                                    mangaOcrDbnetUnclip = it
                                } else {
                                    dbnetUnclip = it
                                }
                            },
                            onValueChangeFinished = saveDbnetNow,
                            valueRange = 1.2f..2.5f,
                            steps = 25
                        )
                        DbnetAdvancedSliderSection(
                            title = stringResource(R.string.settings_dbnet_gap_label, dbnetGap),
                            description = stringResource(R.string.settings_dbnet_gap_desc),
                            value = dbnetGap.toFloat(),
                            onValueChange = { dbnetGap = it.toInt() },
                            onValueChangeFinished = saveDbnetNow,
                            valueRange = 8f..60f,
                            steps = 51
                        )
                    }
                    if (showDbnetResetConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDbnetResetConfirm = false },
                            title = {
                                Text(stringResource(R.string.settings_dbnet_restore_defaults_confirm_title))
                            },
                            text = {
                                Text(stringResource(R.string.settings_dbnet_restore_defaults_confirm_message))
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDbnetResetConfirm = false
                                        resetDbnetDefaults()
                                    }
                                ) {
                                    Text(stringResource(R.string.settings_dbnet_restore_defaults))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDbnetResetConfirm = false }) {
                                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                                }
                            }
                        )
                    }
                }
                } // 关闭 OCR section 内的"灰显 Column"（ocrSectionDisabled 控制 alpha）
            }

            textOrientationSection()

            // —— 图像预处理 ——
            SectionCard(title = stringResource(R.string.settings_section_preprocess), anchorKey = SectionKeys.PREPROCESS, onAnchor = onAnchor) {
                SwitchRow(
                    stringResource(R.string.settings_preprocess_upscale),
                    preUpscale,
                    helpText = stringResource(R.string.settings_preprocess_upscale_help)
                ) { preUpscale = it }
                if (cloudOcrUpscaleWarningVisible(ocrEngine, preUpscale)) {
                    Text(
                        stringResource(R.string.settings_preprocess_upscale_cloud_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SwitchRow(stringResource(R.string.settings_preprocess_invert), preInvert) { preInvert = it }
                SwitchRow(stringResource(R.string.settings_preprocess_binarize), preBinarize) { preBinarize = it }
            }

            // —— 显示 ——
            // 排列原则：能在预览里看到效果的样式项（配色 / 字号 / 透明度）放在上面，
            // 紧跟一个译文样式预览；预览下面才是几何项（显示模式 / 位置 / 微调），
            // 因为它们依赖原文 boundingBox，没有 OCR 上下文无法预览，只能在实际触发翻译时看到。
            SectionCard(title = stringResource(R.string.settings_section_overlay), anchorKey = SectionKeys.OVERLAY, onAnchor = onAnchor) {
                // —— 影响预览的样式项 ——
                Text(stringResource(R.string.settings_overlay_theme_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(overlayTheme, OverlayTheme.CLASSIC_DARK, stringResource(R.string.settings_theme_classic_dark)) { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.AMBER_GOLD, stringResource(R.string.settings_theme_amber_gold)) { overlayTheme = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(overlayTheme, OverlayTheme.PAPER_LIGHT, stringResource(R.string.settings_theme_paper_light)) { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.FROST_GLASS, stringResource(R.string.settings_theme_frost_glass)) { overlayTheme = it }
                    EngineChip(overlayTheme, OverlayTheme.CUSTOM, stringResource(R.string.settings_theme_custom)) { overlayTheme = it }
                }

                if (overlayTheme == OverlayTheme.CUSTOM) {
                    CustomThemeEditor(
                        bg = customBg, onBgChange = { customBg = it },
                        fg = customFg, onFgChange = { customFg = it },
                        border = customBorder, onBorderChange = { customBorder = it },
                        borderW = customBorderW, onBorderWChange = { customBorderW = it }
                    )
                    // 边框样式：仅在 CUSTOM 主题下显示。SOLID/DASHED/DOTTED 一行，DOUBLE/GROOVE 一行（避开 ExperimentalLayoutApi）。
                    Text(stringResource(R.string.settings_floating_window_border_style_label), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineChip(customBorderStyle, com.gameocr.app.data.BorderStyle.SOLID, stringResource(R.string.settings_border_style_solid)) {
                            customBorderStyle = it
                            scope.launch { viewModel.saveCustomBorderStyle(it) }
                        }
                        EngineChip(customBorderStyle, com.gameocr.app.data.BorderStyle.DASHED, stringResource(R.string.settings_border_style_dashed)) {
                            customBorderStyle = it
                            scope.launch { viewModel.saveCustomBorderStyle(it) }
                        }
                        EngineChip(customBorderStyle, com.gameocr.app.data.BorderStyle.DOTTED, stringResource(R.string.settings_border_style_dotted)) {
                            customBorderStyle = it
                            scope.launch { viewModel.saveCustomBorderStyle(it) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineChip(customBorderStyle, com.gameocr.app.data.BorderStyle.DOUBLE, stringResource(R.string.settings_border_style_double)) {
                            customBorderStyle = it
                            scope.launch { viewModel.saveCustomBorderStyle(it) }
                        }
                        EngineChip(customBorderStyle, com.gameocr.app.data.BorderStyle.GROOVE, stringResource(R.string.settings_border_style_groove)) {
                            customBorderStyle = it
                            scope.launch { viewModel.saveCustomBorderStyle(it) }
                        }
                    }
                }

                Text(stringResource(R.string.settings_textsize_label_format, textSize.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 10f..28f, steps = 17)

                Text(stringResource(R.string.settings_overlay_font_label), style = MaterialTheme.typography.labelLarge)
                val defaultOverlayFontName = stringResource(R.string.settings_overlay_font_default)
                val overlayFontChipEntries = OverlayFontPolicy.upsertImportedFont(
                    overlayFontEntries,
                    overlayFontFileName,
                    overlayFontDisplayName
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    OverlayFontChip(
                        selected = overlayFontFileName.isBlank(),
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { viewModel.resetOverlayFont() }
                                overlayFontFileName = ""
                                overlayFontDisplayName = ""
                                overlayFontTypeface = null
                                overlayFontMessage = context.getString(R.string.settings_overlay_font_reset_success)
                                overlayFontMessageIsError = false
                            }
                        },
                        label = defaultOverlayFontName,
                        onLongClick = {}
                    )
                    overlayFontChipEntries.forEach { font ->
                        OverlayFontChip(
                            selected = overlayFontFileName == font.fileName,
                            label = font.displayName,
                            onClick = {
                                scope.launch {
                                    val selected = withContext(Dispatchers.IO) {
                                        viewModel.selectOverlayFont(font.fileName, font.displayName)
                                    }
                                    if (selected) {
                                        overlayFontFileName = font.fileName
                                        overlayFontDisplayName = font.displayName
                                        overlayFontEntries = OverlayFontPolicy.upsertImportedFont(
                                            overlayFontEntries,
                                            font.fileName,
                                            font.displayName
                                        )
                                        overlayFontTypeface = withContext(Dispatchers.IO) {
                                            viewModel.overlayTypefaceFor(font.fileName)
                                        }
                                        overlayFontMessage = null
                                        overlayFontMessageIsError = false
                                    } else {
                                        overlayFontMessage = context.getString(R.string.settings_overlay_font_error_invalid)
                                        overlayFontMessageIsError = true
                                    }
                                }
                            },
                            onLongClick = { pendingOverlayFontDelete = font }
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        overlayFontMessage = null
                        overlayFontImportLauncher.launch(OverlayFontPolicy.OPEN_DOCUMENT_MIME_TYPES)
                    }
                ) {
                    Text(stringResource(R.string.settings_overlay_font_import))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 20.dp)
                ) {
                    overlayFontMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overlayFontMessageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                Text(stringResource(R.string.settings_alpha_label_format, (alpha * 100).toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.3f..1f)

                // —— 译文样式预览 ——
                // 紧跟在上面 3 个可调样式项之后；只反映 theme / 字号 / 透明度 / 自定义色 / 边框。
                // 与 OverlayManager 的颜色映射保持一致；改这里时记得同步 [overlayThemeColors]。
                OverlayPreviewCard(
                    theme = overlayTheme,
                    customBg = customBg,
                    customFg = customFg,
                    customBorder = customBorder,
                    customBorderW = customBorderW,
                    customBorderStyle = customBorderStyle,
                    textSize = textSize,
                    alpha = alpha,
                    overlayTypeface = overlayFontTypeface
                )

                // —— 几何项（预览看不到，只能实际触发翻译时看到效果）——
                Text(stringResource(R.string.settings_render_mode_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(renderMode, RenderMode.BLOCKS, stringResource(R.string.settings_render_blocks_chip)) { renderMode = it }
                    EngineChip(renderMode, RenderMode.FLOATING_WINDOW, stringResource(R.string.settings_render_floating_window_chip)) { renderMode = it }
                }

                if (renderMode == RenderMode.FLOATING_WINDOW) {
                    // 悬浮窗口内容形态：原文+译文 / 仅译文。立即生效，不进 save 流程。
                    Text(stringResource(R.string.settings_floating_window_content_label), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineChip(
                            floatingWindowContentMode,
                            com.gameocr.app.data.FloatingWindowContentMode.SRC_AND_DST,
                            stringResource(R.string.settings_floating_window_content_src_and_dst)
                        ) {
                            floatingWindowContentMode = it
                            scope.launch { viewModel.saveFloatingWindowContentMode(it) }
                        }
                        EngineChip(
                            floatingWindowContentMode,
                            com.gameocr.app.data.FloatingWindowContentMode.DST_ONLY,
                            stringResource(R.string.settings_floating_window_content_dst_only)
                        ) {
                            floatingWindowContentMode = it
                            scope.launch { viewModel.saveFloatingWindowContentMode(it) }
                        }
                    }
                    SwitchRow(stringResource(R.string.settings_floating_window_locked), floatingWindowLocked) {
                        floatingWindowLocked = it
                        scope.launch { viewModel.saveFloatingWindowLocked(it) }
                    }
                    Text(
                        stringResource(R.string.settings_floating_window_locked_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedButton(onClick = {
                        scope.launch { viewModel.resetFloatingWindowGeometry() }
                    }) {
                        Text(stringResource(R.string.settings_floating_window_reset_geometry))
                    }
                }

                if (renderMode == RenderMode.BLOCKS) {
                    Text(stringResource(R.string.settings_placement_label), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineChip(placement, OverlayPlacement.BELOW, stringResource(R.string.settings_placement_below_chip)) { placement = it }
                        EngineChip(placement, OverlayPlacement.OVERLAP, stringResource(R.string.settings_placement_overlap_chip)) { placement = it }
                        EngineChip(placement, OverlayPlacement.ABOVE, stringResource(R.string.settings_placement_above_chip)) { placement = it }
                    }

                    Text(stringResource(R.string.settings_offset_x_format, offsetX.toInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -200f..200f)

                    Text(stringResource(R.string.settings_offset_y_format, offsetY.toInt()), style = MaterialTheme.typography.labelLarge)
                    Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -100f..100f)

                    SwitchRow(stringResource(R.string.settings_allow_wrap), allowWrap) { allowWrap = it }
                    SwitchRow(stringResource(R.string.settings_avoid_collision), avoidCollision) { avoidCollision = it }
                    Text(
                        stringResource(R.string.settings_avoid_collision_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SwitchRow(stringResource(R.string.settings_merge_adjacent), mergeAdjacent) { mergeAdjacent = it }
                    if (mergeAdjacent) {
                        Text(
                            stringResource(R.string.settings_merge_strength_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EngineChip(mergeStrength, com.gameocr.app.data.MergeStrength.CONSERVATIVE,
                                stringResource(R.string.settings_merge_strength_conservative)) { mergeStrength = it }
                            EngineChip(mergeStrength, com.gameocr.app.data.MergeStrength.STANDARD,
                                stringResource(R.string.settings_merge_strength_standard)) { mergeStrength = it }
                            EngineChip(mergeStrength, com.gameocr.app.data.MergeStrength.AGGRESSIVE,
                                stringResource(R.string.settings_merge_strength_aggressive)) { mergeStrength = it }
                        }
                        Text(
                            stringResource(when (mergeStrength) {
                                com.gameocr.app.data.MergeStrength.CONSERVATIVE -> R.string.settings_merge_strength_conservative_hint
                                com.gameocr.app.data.MergeStrength.STANDARD -> R.string.settings_merge_strength_standard_hint
                                com.gameocr.app.data.MergeStrength.AGGRESSIVE -> R.string.settings_merge_strength_aggressive_hint
                            }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        stringResource(R.string.settings_merge_adjacent_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }

            // —— 悬浮按钮 ——
            SectionCard(title = stringResource(R.string.settings_section_floating), anchorKey = SectionKeys.FLOATING, onAnchor = onAnchor) {
                Text(stringResource(R.string.settings_floating_size_format, floatingSize.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = floatingSize,
                    onValueChange = { floatingSize = it },
                    valueRange = 32f..96f,
                    steps = (96 - 32) / 4 - 1
                )

                SwitchRow(stringResource(R.string.settings_floating_snap_edge_label), floatingSnapEdge) { floatingSnapEdge = it }
                Text(
                    stringResource(R.string.settings_floating_snap_edge_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SwitchRow(
                    stringResource(R.string.settings_floating_auto_dock_label),
                    floatingAutoDock,
                    enabled = floatingSnapEdge
                ) { floatingAutoDock = it }
                Text(
                    stringResource(R.string.settings_floating_auto_dock_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(if (floatingSnapEdge) 1f else 0.4f)
                )

                Text(
                    stringResource(R.string.settings_floating_dock_inset_format, floatingDockInset.toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.alpha(if (floatingSnapEdge) 1f else 0.4f)
                )
                Slider(
                    value = floatingDockInset,
                    onValueChange = { floatingDockInset = it },
                    valueRange = 0f..40f,
                    steps = 39,
                    enabled = floatingSnapEdge
                )
                OutlinedButton(
                    onClick = { insetPreviewActive = !insetPreviewActive },
                    enabled = floatingSnapEdge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(
                        if (insetPreviewActive) R.string.settings_floating_dock_inset_preview_stop
                        else R.string.settings_floating_dock_inset_preview_start
                    ))
                }
                Text(
                    stringResource(R.string.settings_floating_dock_inset_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(if (floatingSnapEdge) 1f else 0.4f)
                )
            }

            // —— 弧菜单按钮顺序 ——
            SectionCard(
                title = stringResource(R.string.settings_section_arc_menu),
                anchorKey = SectionKeys.ARC_MENU,
                onAnchor = onAnchor
            ) {
                Text(
                    stringResource(R.string.settings_arc_menu_page_size, arcMenuPageSize.toInt()),
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = arcMenuPageSize,
                    onValueChange = { arcMenuPageSize = it.roundToInt().coerceIn(
                        FloatingMenu.MIN_PAGE_SIZE,
                        FloatingMenu.MAX_PAGE_SIZE
                    ).toFloat() },
                    onValueChangeFinished = {
                        scope.launch { viewModel.saveArcMenuPageSize(arcMenuPageSize.toInt()) }
                    },
                    valueRange = FloatingMenu.MIN_PAGE_SIZE.toFloat()..FloatingMenu.MAX_PAGE_SIZE.toFloat(),
                    steps = FloatingMenu.MAX_PAGE_SIZE - FloatingMenu.MIN_PAGE_SIZE - 1
                )
                Text(
                    stringResource(R.string.settings_arc_menu_page_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.settings_arc_menu_order_desc,
                        arcMenuPageSize.toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ArcMenuOrderEditor(
                    order = menuOrder,
                    currentSkill = currentSkill,
                    onReorder = { next ->
                        menuOrder = next
                        scope.launch { viewModel.saveArcMenuOrder(next) }
                    }
                )
            }

            // —— 触发器 ——
            SectionCard(title = stringResource(R.string.settings_section_trigger), anchorKey = SectionKeys.TRIGGER, onAnchor = onAnchor) {
                OutlinedTextField(
                    value = loopInterval,
                    onValueChange = { loopInterval = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.settings_loop_interval_label)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                SwitchRow(stringResource(R.string.settings_a11y_volume_label), a11yVolume) { a11yVolume = it }
                Text(
                    stringResource(R.string.settings_a11y_volume_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_btn_open_a11y)) }
            }

            // —— 网络（全局，跨 OCR / 翻译）——
            SectionCard(title = stringResource(R.string.settings_section_network), anchorKey = SectionKeys.NETWORK, onAnchor = onAnchor) {
                Text(
                    stringResource(R.string.settings_api_timeout_format, apiTimeoutSec.toInt()),
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = apiTimeoutSec,
                    onValueChange = { apiTimeoutSec = it },
                    valueRange = 5f..120f,
                    steps = 22
                )
                Text(
                    stringResource(R.string.settings_api_timeout_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    stringResource(R.string.settings_cleartext_hosts_label),
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedTextField(
                    value = cleartextHostsText,
                    onValueChange = { cleartextHostsText = it },
                    placeholder = { Text(stringResource(R.string.settings_cleartext_hosts_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2
                )
                Text(
                    stringResource(R.string.settings_cleartext_hosts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                // —— 端侧 LLM 下载源 ——
                // 放在网络 section（而非端侧 LLM section）：
                //   1) 选源是网络行为，跟"明文白名单 / 超时"是同一层抽象；
                //   2) 用户切 chip 时不必每次重看 radio 列表，UI 更紧凑。
                // Radio 切换立即落盘，避免用户切完忘点下载导致丢失；自定义 URL 字段编辑频繁，
                // 仅在切回别的 radio 或点下载时落盘。
                Text(
                    stringResource(R.string.llm_mirror_section_label),
                    style = MaterialTheme.typography.labelLarge
                )
                val saveLlmMirrorNow: (com.gameocr.app.data.LlmMirrorChoice) -> Unit = { c ->
                    llmMirrorChoice = c
                    scope.launch { viewModel.saveLlmMirror(c, llmMirror) }
                }
                LlmMirrorRadioRow(
                    label = stringResource(R.string.llm_mirror_hf_official),
                    selected = llmMirrorChoice == com.gameocr.app.data.LlmMirrorChoice.HF_OFFICIAL,
                    enabled = true
                ) { saveLlmMirrorNow(com.gameocr.app.data.LlmMirrorChoice.HF_OFFICIAL) }
                LlmMirrorRadioRow(
                    label = stringResource(R.string.llm_mirror_hf_mirror),
                    selected = llmMirrorChoice == com.gameocr.app.data.LlmMirrorChoice.HF_MIRROR,
                    enabled = true
                ) { saveLlmMirrorNow(com.gameocr.app.data.LlmMirrorChoice.HF_MIRROR) }
                LlmMirrorRadioRow(
                    label = stringResource(R.string.llm_mirror_custom),
                    selected = llmMirrorChoice == com.gameocr.app.data.LlmMirrorChoice.CUSTOM,
                    enabled = true
                ) { saveLlmMirrorNow(com.gameocr.app.data.LlmMirrorChoice.CUSTOM) }
                if (llmMirrorChoice == com.gameocr.app.data.LlmMirrorChoice.CUSTOM) {
                    OutlinedTextField(
                        value = llmMirror,
                        onValueChange = { llmMirror = it },
                        label = { Text(stringResource(R.string.llm_mirror_label)) },
                        placeholder = { Text(stringResource(R.string.llm_mirror_placeholder)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            }

            // 给 FAB 留出底部空间，避免最后一项被遮挡
            Box(modifier = Modifier.size(80.dp))
            }

            // 搜索下拉：浮在 Column 之上。匹配项点击后滚到对应 section 顶部并关闭搜索。
            if (searchActive && searchQuery.isNotBlank()) {
                val matches = remember(searchQuery) {
                    SETTING_ITEMS.filter { it.matches(context, searchQuery) }.take(20)
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .heightIn(max = 320.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    if (matches.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_search_no_match),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn {
                            items(matches) { entry ->
                                ListItem(
                                    headlineContent = { Text(stringResource(entry.itemLabelRes)) },
                                    supportingContent = { Text(stringResource(entry.sectionLabelRes)) },
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    modifier = Modifier.clickable {
                                        val y = anchors[entry.sectionKey] ?: 0
                                        scope.launch { scrollState.animateScrollTo(y) }
                                        closeSearch()
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 译文样式实时预览卡。展示一段假的"原文 + 译文"，按当前 theme/字号/透明度/自定义色/边框/边框样式渲染。
 *
 * 与 [com.gameocr.app.overlay.OverlayManager] / [com.gameocr.app.overlay.DraggableOverlayWindow] 的视觉保持一致：
 * - 主题颜色映射见 [overlayThemeColors]（务必与 OverlayManager 同步）
 * - alpha 整体应用到 box（模拟 view.setAlpha 的效果，叠加自身像素 alpha）
 * - 棋盘格底色用 linear gradient 模拟实际屏幕背景，让透明度变化肉眼可见
 * - 边框样式：仅 CUSTOM 主题下读 [customBorderStyle]，预设主题恒为 SOLID（与 DraggableOverlayWindow 一致）；
 *   DASH/DOT 间距、DOUBLE 间隙、GROOVE 明暗各 ±40% 全部复制 OverlayManager / DraggableOverlayWindow 的硬编码
 */
private fun overlayFontImportErrorMessage(
    context: android.content.Context,
    error: OverlayFontImportError
): String = context.getString(
    when (error) {
        OverlayFontImportError.UNSUPPORTED_EXTENSION -> R.string.settings_overlay_font_error_extension
        OverlayFontImportError.EMPTY_FILE -> R.string.settings_overlay_font_error_empty
        OverlayFontImportError.TOO_LARGE -> R.string.settings_overlay_font_error_too_large
        OverlayFontImportError.UNREADABLE -> R.string.settings_overlay_font_error_unreadable
        OverlayFontImportError.INVALID_FONT -> R.string.settings_overlay_font_error_invalid
        OverlayFontImportError.COPY_FAILED -> R.string.settings_overlay_font_error_copy_failed
    }
)

@Composable
private fun OverlayPreviewCard(
    theme: OverlayTheme,
    customBg: Int,
    customFg: Int,
    customBorder: Int,
    customBorderW: Float,
    customBorderStyle: com.gameocr.app.data.BorderStyle,
    textSize: Float,
    alpha: Float,
    overlayTypeface: android.graphics.Typeface?
) {
    val colors = overlayThemeColors(theme, customBg, customFg, customBorder, customBorderW.toInt())
    // 仅 CUSTOM 主题 + borderDp > 0 时让用户选的 borderStyle 生效；与 DraggableOverlayWindow 一致
    val effectiveBorderStyle = if (theme == OverlayTheme.CUSTOM)
        customBorderStyle else com.gameocr.app.data.BorderStyle.SOLID
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_overlay_preview_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1F2937), Color(0xFF374151), Color(0xFF1F2937))
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(alpha)
                    .background(
                        Color(colors.bg),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .borderStyleOverlay(
                        borderDp = colors.borderDp,
                        borderColor = colors.border,
                        borderStyle = effectiveBorderStyle,
                        cornerRadiusDp = 6f
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val previewText = stringResource(R.string.settings_overlay_preview_sample)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    factory = { ctx ->
                        android.widget.TextView(ctx).apply {
                            setIncludeFontPadding(true)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }
                    },
                    update = { view ->
                        view.text = previewText
                        view.setTextColor(colors.fg)
                        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
                        view.typeface = overlayTypeface
                    }
                )
            }
        }
    }
}

/**
 * 按 [borderStyle] 在 box 上画一圈边框，五种样式行为复制
 * [com.gameocr.app.overlay.DraggableOverlayWindow.shellBackground]：
 * - SOLID：单条 stroke
 * - DASHED：dashPathEffect(8dp on, 5dp off)
 * - DOTTED：dashPathEffect(2dp on, 3dp off)
 * - DOUBLE：外圈 + 内圈两条同色 stroke，间距 = w + 3dp
 * - GROOVE：外圈暗色 (-40%)、内圈亮色 (+40%)，inset = w
 *
 * borderDp <= 0 时直接 noop。
 */
private fun Modifier.borderStyleOverlay(
    borderDp: Int,
    borderColor: Int,
    borderStyle: com.gameocr.app.data.BorderStyle,
    cornerRadiusDp: Float
): Modifier = this.then(
    Modifier.drawBehind {
        if (borderDp <= 0) return@drawBehind
        val w = borderDp.dp.toPx()
        val cornerPx = cornerRadiusDp.dp.toPx()
        val color = Color(borderColor)
        // stroke 居中绘制，rect 往内 inset w/2 才能让外缘正好贴 box 边
        val inset = w / 2f
        val outerRect = androidx.compose.ui.geometry.Rect(
            left = inset,
            top = inset,
            right = size.width - inset,
            bottom = size.height - inset
        )
        val outerRadius = (cornerPx - inset).coerceAtLeast(0f)
        when (borderStyle) {
            com.gameocr.app.data.BorderStyle.SOLID -> drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(outerRect.left, outerRect.top),
                size = androidx.compose.ui.geometry.Size(outerRect.width, outerRect.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(outerRadius),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
            )
            com.gameocr.app.data.BorderStyle.DASHED -> drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(outerRect.left, outerRect.top),
                size = androidx.compose.ui.geometry.Size(outerRect.width, outerRect.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(outerRadius),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(8.dp.toPx(), 5.dp.toPx())
                    )
                )
            )
            com.gameocr.app.data.BorderStyle.DOTTED -> drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(outerRect.left, outerRect.top),
                size = androidx.compose.ui.geometry.Size(outerRect.width, outerRect.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(outerRadius),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(2.dp.toPx(), 3.dp.toPx())
                    )
                )
            )
            com.gameocr.app.data.BorderStyle.DOUBLE -> {
                // 外圈
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(outerRect.left, outerRect.top),
                    size = androidx.compose.ui.geometry.Size(outerRect.width, outerRect.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(outerRadius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
                )
                // 内圈：间距 = w + 3dp（与 LayerDrawable.setLayerInset 一致）
                val gap = w + 3.dp.toPx()
                val innerInset = inset + gap
                val innerRect = androidx.compose.ui.geometry.Rect(
                    left = innerInset, top = innerInset,
                    right = size.width - innerInset, bottom = size.height - innerInset
                )
                if (innerRect.width > 0f && innerRect.height > 0f) {
                    drawRoundRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(innerRect.left, innerRect.top),
                        size = androidx.compose.ui.geometry.Size(innerRect.width, innerRect.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            (outerRadius - gap).coerceAtLeast(0f)
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
                    )
                }
            }
            com.gameocr.app.data.BorderStyle.GROOVE -> {
                // 外圈暗色
                drawRoundRect(
                    color = Color(shadeArgb(borderColor, -0.4f)),
                    topLeft = androidx.compose.ui.geometry.Offset(outerRect.left, outerRect.top),
                    size = androidx.compose.ui.geometry.Size(outerRect.width, outerRect.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(outerRadius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
                )
                // 内圈亮色，inset = w
                val innerInset = inset + w
                val innerRect = androidx.compose.ui.geometry.Rect(
                    left = innerInset, top = innerInset,
                    right = size.width - innerInset, bottom = size.height - innerInset
                )
                if (innerRect.width > 0f && innerRect.height > 0f) {
                    drawRoundRect(
                        color = Color(shadeArgb(borderColor, 0.4f)),
                        topLeft = androidx.compose.ui.geometry.Offset(innerRect.left, innerRect.top),
                        size = androidx.compose.ui.geometry.Size(innerRect.width, innerRect.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            (outerRadius - w).coerceAtLeast(0f)
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
                    )
                }
            }
        }
    }
)

/** 与 [com.gameocr.app.overlay.DraggableOverlayWindow.shadeColor] 行为一致。factor>0 加亮、<0 加暗。 */
private fun shadeArgb(color: Int, factor: Float): Int {
    val a = (color shr 24) and 0xFF
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val nr = if (factor >= 0) r + ((255 - r) * factor).toInt() else (r * (1 + factor)).toInt()
    val ng = if (factor >= 0) g + ((255 - g) * factor).toInt() else (g * (1 + factor)).toInt()
    val nb = if (factor >= 0) b + ((255 - b) * factor).toInt() else (b * (1 + factor)).toInt()
    return (a shl 24) or
        (nr.coerceIn(0, 255) shl 16) or
        (ng.coerceIn(0, 255) shl 8) or
        nb.coerceIn(0, 255)
}

/** 主题 → ARGB 颜色映射。与 [com.gameocr.app.overlay.OverlayManager] 内的硬编码必须保持一致。 */
private data class ThemeColors(val bg: Int, val fg: Int, val border: Int, val borderDp: Int)

private fun overlayThemeColors(
    theme: OverlayTheme,
    customBg: Int,
    customFg: Int,
    customBorder: Int,
    customBorderW: Int
): ThemeColors = when (theme) {
    OverlayTheme.CLASSIC_DARK ->
        ThemeColors(bg = 0xE6000000.toInt(), fg = 0xFFFFFFFF.toInt(), border = 0, borderDp = 0)
    OverlayTheme.AMBER_GOLD ->
        ThemeColors(bg = 0xF0241608.toInt(), fg = 0xFFFFD27F.toInt(), border = 0xFFB8860B.toInt(), borderDp = 2)
    OverlayTheme.PAPER_LIGHT ->
        ThemeColors(bg = 0xF0F5EFE0.toInt(), fg = 0xFF3E2A1F.toInt(), border = 0xFFB68850.toInt(), borderDp = 1)
    OverlayTheme.FROST_GLASS ->
        ThemeColors(bg = 0xCC1E293B.toInt(), fg = 0xFFE0F2FE.toInt(), border = 0xFF60A5FA.toInt(), borderDp = 1)
    OverlayTheme.CUSTOM ->
        ThemeColors(bg = customBg, fg = customFg, border = customBorder, borderDp = customBorderW.coerceAtLeast(0))
}

/** 搜索可用的 section key 常量。和 [SETTING_ITEMS] 的 sectionKey 对齐。 */
private object SectionKeys {
    const val TRANSLATE = "translate"
    const val PRESETS = "presets"
    const val OCR = "ocr"
    const val TEXT_ORIENTATION = "text_orientation"
    const val PREPROCESS = "preprocess"
    const val OVERLAY = "overlay"
    const val FLOATING = "floating"
    const val ARC_MENU = "arc_menu"
    const val TRIGGER = "trigger"
    const val NETWORK = "network"
    const val APP_LANG = "app_lang"
    const val THEME_MODE = "theme_mode"
}

/**
 * 搜索索引条目。sectionLabel/itemLabel 走 res id 跟随系统语言；keywords 同时塞中英文，
 * 让用户用任何一种语言搜索都能命中（i18n 后用户可能习惯输入哪种都说不定）。
 */
/** 把 UI 多行输入框文本拆成 host 列表，trim 每行、去空。保存 / snapshot 对比都走这里保证一致。 */
private fun parseCleartextHosts(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

private data class SearchEntry(
    val sectionKey: String,
    @androidx.annotation.StringRes val sectionLabelRes: Int,
    @androidx.annotation.StringRes val itemLabelRes: Int,
    val keywords: List<String> = emptyList()
) {
    fun matches(context: android.content.Context, q: String): Boolean {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return false
        return context.getString(itemLabelRes).lowercase().contains(s) ||
            context.getString(sectionLabelRes).lowercase().contains(s) ||
            keywords.any { it.lowercase().contains(s) }
    }
}

/**
 * 设置项可搜索索引。新增设置项时同步加一行；匹配后跳到所在 section 顶部。
 * keywords 混合中英文：英文系统下用户用英文输入仍能搜到中文 section / 反之亦然。
 */
private val SETTING_ITEMS: List<SearchEntry> = listOf(
    SearchEntry(SectionKeys.PRESETS, R.string.settings_section_translation_presets, R.string.settings_section_translation_presets, listOf("preset", "presets", "profile", "mode", "系统预设方案", "翻译预设", "预设", "模式")),

    // —— 翻译后端 ——
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_translator_engine, listOf("OpenAI", "DeepL", "LLM", "翻译引擎")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_base_url, listOf("base url")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_api_key, listOf("api key")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_model_name, listOf("model", "模型名")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_api_key, listOf("deepl")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_pro, listOf("deepl pro")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_advanced, listOf("deeplx", "bearer", "official", "protocol", "自架", "高级", "协议", "deepl base url")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_youdao_pictrans, listOf("youdao", "有道", "图片翻译", "pictrans", "ocrtransapi", "端到端")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_google, listOf("google", "谷歌", "translate")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_volc, listOf("volc", "volcengine", "火山", "字节", "doubao", "bytedance", "access key", "AK", "SK", "region", "区域")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_baidu_fanyi, listOf("baidu fanyi", "百度翻译", "fanyi-api", "appid", "开放平台")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_tencent_translator, listOf("tencent", "腾讯", "tmt", "tmtcloud", "腾讯云翻译")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_source_lang, listOf("source", "源语言")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_target_lang, listOf("target", "目标语言")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_prompt, listOf("prompt", "提示词", "system")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_dictionary_prompt, listOf("dictionary", "词典", "划词", "word select", "phonetic", "音标", "释义", "definition", "prompt")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_streaming, listOf("streaming", "流式")),

    // —— OCR 引擎 ——
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_ocr_switch, listOf("ML Kit", "百度", "腾讯", "Paddle", "OCR engine")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_paddle_download, listOf("ONNX", "v5", "镜像", "mirror", "本地导入", "local import", "import", "导入", "delete", "删除")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_manga_ocr_download, listOf("manga", "manga-ocr", "日漫", "漫画", "竖排", "vertical", "ONNX", "模型", "model", "download", "下载", "本地导入", "local import", "import", "导入", "delete", "删除")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_umi_ocr, listOf("umi", "Umi-OCR", "local http", "局域网", "本机", "PC", "1224", "api/ocr")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_luna_ocr, listOf("luna", "LunaTranslator", "露娜", "local http", "局域网", "本机", "PC", "api/ocr")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_baidu_api_key, listOf("baidu", "百度", "secret key", "secret", "密钥")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_baidu_endpoint, listOf("百度", "baidu", "general", "accurate", "webimage", "含位置", "标准版", "高精度")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_baidu_lang, listOf("百度", "baidu", "language", "语种", "CHN_ENG", "JAP", "KOR", "auto_detect")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_tencent_secret, listOf("tencent", "腾讯", "secret id", "secret key", "secretid", "secretkey", "密钥")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_tencent_endpoint, listOf("tencent", "腾讯", "general basic", "general accurate", "recognize agent", "高精度", "智能 agent")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_tencent_lang, listOf("tencent", "腾讯", "language", "语种", "mix", "zh_rare", "auto")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_tencent_region, listOf("tencent", "腾讯", "region", "区域", "ap-guangzhou", "广州")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_youdao_ocr, listOf("youdao", "有道", "ocrapi", "app key", "app secret")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_dbnet_advanced, listOf("dbnet", "threshold", "prob", "box score", "unclip", "bubble", "cluster", "gap", "advanced", "阈值", "二值化", "连通域", "外扩", "气泡", "聚类", "高级")),
    SearchEntry(SectionKeys.TEXT_ORIENTATION, R.string.settings_text_orientation_section_title, R.string.settings_orient_auto_detect_title, listOf("orientation", "text orientation", "direction", "vertical", "horizontal", "自动判别", "方向", "文本方向", "竖排", "横排")),
    SearchEntry(SectionKeys.TEXT_ORIENTATION, R.string.settings_text_orientation_section_title, R.string.settings_search_item_manual_orientation, listOf("manual", "lock", "orientation", "vertical", "horizontal", "stacked", "手动", "锁定", "方向", "竖排", "横排", "逐字")),
    SearchEntry(SectionKeys.TEXT_ORIENTATION, R.string.settings_text_orientation_section_title, R.string.settings_search_item_orientation_model, listOf("orientation model", "doc orientation", "direction model", "ONNX", "方向模型", "文本方向模型", "模型", "download", "下载", "本地导入", "local import", "导入", "delete", "删除")),

    // —— 图像预处理 ——
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_upscale, listOf("upscale", "放大", "上采样")),
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_invert, listOf("invert", "反色", "暗底白字")),
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_binarize, listOf("binarize", "otsu", "二值化")),

    // —— 显示 ——
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_render_mode, listOf("紧贴", "横幅", "banner", "render", "display mode", "floating window", "悬浮窗")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_placement, listOf("下方", "上方", "覆盖", "below", "above", "overlap", "placement")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_offset, listOf("offset", "微调")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_overlay_theme, listOf("深色", "浅色", "纸张", "霜玻璃", "琥珀", "theme", "dark", "light", "frost", "amber")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_custom_theme, listOf("custom", "border", "自定义", "边框")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_border_style, listOf("solid", "dashed", "dotted", "double", "groove", "实线", "虚线", "点线", "双线", "凹槽", "边框样式")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_text_size, listOf("font size", "字号", "字体大小")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_overlay_font, listOf("font", "ttf", "字体", "自定义字体", "译文字体")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_alpha, listOf("alpha", "opacity", "透明度")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_floating_window_content, listOf("floating window", "悬浮窗", "原文+译文", "仅译文", "src dst", "content mode")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_floating_window_locked, listOf("lock", "锁定", "悬浮窗")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_floating_window_reset, listOf("reset", "重置", "还原", "默认", "default", "floating window", "悬浮窗", "geometry", "几何", "位置", "尺寸", "size")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_allow_wrap, listOf("wrap", "换行", "single line", "多行")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_avoid_collision, listOf("collision", "碰撞", "避撞", "重叠")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_merge_adjacent, listOf("merge", "合并", "重叠", "拆段")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_merge_strength, listOf("strength", "强度", "保守", "标准", "激进", "conservative", "standard", "aggressive")),

    // —— 悬浮按钮 ——
    // 注意：floating_size 历史误指 OVERLAY，0.3.x 起改成 FLOATING（实际控件在 floating section）。
    SearchEntry(SectionKeys.FLOATING, R.string.settings_section_floating, R.string.settings_search_item_floating_size, listOf("floating", "圆球", "悬浮", "size", "大小")),
    SearchEntry(SectionKeys.FLOATING, R.string.settings_section_floating, R.string.settings_search_item_floating_snap, listOf("snap", "贴边", "edge")),
    SearchEntry(SectionKeys.FLOATING, R.string.settings_section_floating, R.string.settings_search_item_floating_auto_dock, listOf("auto dock", "自动停靠", "停靠", "藏边")),
    SearchEntry(SectionKeys.FLOATING, R.string.settings_section_floating, R.string.settings_search_item_floating_dock_inset, listOf("inset", "贴边距离", "手势", "全面屏", "gesture")),

    // —— 弧菜单按钮顺序 ——
    SearchEntry(SectionKeys.ARC_MENU, R.string.settings_section_arc_menu, R.string.settings_search_item_arc_menu_order, listOf("arc menu", "弧菜单", "弧形", "顺序", "order", "reorder", "排序", "拖动", "menu", "按钮", "page", "page size", "分页", "每页", "翻页", "loop", "region", "home", "skill", "技能", "划词", "language", "语言", "源语言", "目标语言")),

    // —— 触发器 ——
    SearchEntry(SectionKeys.TRIGGER, R.string.settings_section_trigger, R.string.settings_search_item_loop_interval, listOf("loop", "循环")),
    SearchEntry(SectionKeys.TRIGGER, R.string.settings_section_trigger, R.string.settings_search_item_a11y_volume, listOf("无障碍", "a11y", "accessibility", "volume", "音量")),

    // —— 网络 ——
    SearchEntry(SectionKeys.NETWORK, R.string.settings_section_network, R.string.settings_search_item_api_timeout, listOf("timeout", "超时", "网络", "network")),
    SearchEntry(SectionKeys.NETWORK, R.string.settings_section_network, R.string.settings_search_item_llm_mirror, listOf("mirror", "hf-mirror", "huggingface", "download source", "模型下载源", "下载源", "镜像", "自定义 URL", "local llm", "llm model")),
    SearchEntry(SectionKeys.NETWORK, R.string.settings_section_network, R.string.settings_search_item_cleartext_hosts, listOf("cleartext", "http", "明文", "白名单", "host", "自架", "私有")),

    SearchEntry(SectionKeys.APP_LANG, R.string.settings_section_app_lang, R.string.settings_section_app_lang, listOf("language", "locale", "语言", "中文", "english", "i18n")),

    SearchEntry(SectionKeys.THEME_MODE, R.string.settings_section_theme_mode, R.string.settings_section_theme_mode, listOf("theme", "夜间", "白天", "深色", "浅色", "dark", "light", "night")),
)

@Composable
private fun TranslationPresetSection(
    customPresets: List<TranslationPreset>,
    activeId: String,
    unsavedPreset: TranslationPreset?,
    message: String?,
    localLlmDeviceCapable: Boolean,
    llmModelReady: (LlmModelKind) -> Boolean,
    paddleModelReady: (PaddleModelVersion) -> Boolean,
    mangaOcrModelReady: Boolean,
    orientationModelReady: Boolean,
    modelDownloading: Boolean,
    downloadingPresetId: String?,
    onSaveUnsaved: (TranslationPreset) -> Unit,
    onApply: (TranslationPreset) -> Unit,
    onCopy: (TranslationPreset) -> Unit,
    onDownloadModels: (TranslationPreset, List<TranslationPresetModelIssue>) -> Unit,
    onDelete: (TranslationPreset) -> Unit
) {
    var pendingDeletePreset by remember { mutableStateOf<TranslationPreset?>(null) }
    var pendingSavePreset by remember { mutableStateOf<TranslationPreset?>(null) }
    var pendingSavePresetName by remember { mutableStateOf("") }
    var presetsExpanded by remember { mutableStateOf(false) }

    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    HorizontalDivider()
    TranslationPresetUnsavedSlot(
        preset = unsavedPreset,
        onSave = { preset ->
            pendingSavePreset = preset
            pendingSavePresetName = ""
        }
    )
    HorizontalDivider()
    val allPresets = TranslationPresetCatalog.all(customPresets)
        .filterNot { unsavedPreset != null && it.id == TranslationPresetCatalog.UNSAVED_DRAFT_ID }
    val existingPresetNames = allPresets.map { translationPresetDisplayName(it) }
    translationPresetVisibleItems(allPresets, presetsExpanded).forEach { preset ->
        val modelIssues = translationPresetModelIssues(
            preset = preset,
            localLlmDeviceCapable = localLlmDeviceCapable,
            llmModelReady = llmModelReady,
            paddleModelReady = paddleModelReady,
            mangaOcrModelReady = mangaOcrModelReady,
            orientationModelReady = orientationModelReady
        )
        TranslationPresetRow(
            preset = preset,
            selected = preset.id == activeId,
            modelIssues = modelIssues,
            downloadState = translationPresetModelDownloadState(
                presetId = preset.id,
                activePresetDownloadId = downloadingPresetId,
                modelDownloading = modelDownloading,
            ),
            onApply = { onApply(preset) },
            onCopy = { onCopy(preset) },
            onDownloadModels = { onDownloadModels(preset, modelIssues) },
            onDelete = { pendingDeletePreset = preset }
        )
    }
    if (translationPresetCollapseToggleVisible(allPresets.size)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { presetsExpanded = !presetsExpanded }) {
                Text(
                    if (presetsExpanded) {
                        stringResource(R.string.settings_translation_preset_collapse)
                    } else {
                        stringResource(R.string.settings_translation_preset_expand_format, allPresets.size)
                    }
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            rotationZ = if (presetsExpanded) 180f else 0f
                        }
                )
            }
        }
    }
    pendingDeletePreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDeletePreset = null },
            title = { Text(stringResource(R.string.settings_translation_preset_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_translation_preset_delete_confirm_message,
                        translationPresetDisplayName(preset)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletePreset = null
                    onDelete(preset)
                }) {
                    Text(stringResource(R.string.settings_translation_preset_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePreset = null }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            }
        )
    }
    pendingSavePreset?.let { preset ->
        val duplicateName = translationPresetNameExists(pendingSavePresetName, existingPresetNames)
        val saveNameValid = normalizedTranslationPresetName(pendingSavePresetName) != null && !duplicateName
        AlertDialog(
            onDismissRequest = {
                pendingSavePreset = null
                pendingSavePresetName = ""
            },
            title = { Text(stringResource(R.string.settings_translation_preset_save_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = pendingSavePresetName,
                    onValueChange = { pendingSavePresetName = it },
                    label = { Text(stringResource(R.string.settings_translation_preset_name)) },
                    placeholder = { Text(stringResource(R.string.settings_translation_preset_name_placeholder)) },
                    isError = duplicateName,
                    supportingText = if (duplicateName) {
                        { Text(stringResource(R.string.settings_translation_preset_name_duplicate)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveNameValid,
                    onClick = {
                        val presetToSave = namedTranslationPresetOrNull(
                            preset = preset,
                            nameInput = pendingSavePresetName,
                            id = newCustomPresetId(),
                        ) ?: return@TextButton
                        pendingSavePreset = null
                        pendingSavePresetName = ""
                        onSaveUnsaved(presetToSave)
                    }
                ) {
                    Text(stringResource(R.string.settings_translation_preset_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingSavePreset = null
                    pendingSavePresetName = ""
                }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            }
        )
    }
}

@Composable
private fun TranslationPresetUnsavedSlot(
    preset: TranslationPreset?,
    onSave: (TranslationPreset) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        if (preset != null) {
            UnsavedTranslationPresetRow(
                preset = preset,
                onSave = { onSave(preset) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = stringResource(R.string.settings_translation_preset_matched_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun UnsavedTranslationPresetRow(
    preset: TranslationPreset,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            translationPresetDisplayName(preset),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            translationPresetSummary(preset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onSave) {
            Text(stringResource(R.string.settings_translation_preset_save))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TranslationPresetRow(
    preset: TranslationPreset,
    selected: Boolean,
    modelIssues: List<TranslationPresetModelIssue>,
    downloadState: TranslationPresetModelDownloadState,
    onApply: () -> Unit,
    onCopy: () -> Unit,
    onDownloadModels: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    translationPresetDisplayName(preset),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    translationPresetSummary(preset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedButton(
                onClick = onApply,
                enabled = !selected && translationPresetCanApply(modelIssues)
            ) {
                Text(
                    stringResource(
                        if (selected) {
                            R.string.settings_translation_preset_applied_button
                        } else {
                            R.string.settings_translation_preset_apply
                        }
                    )
                )
            }
            if (modelIssues.any { it.downloadable }) {
                OutlinedButton(
                    onClick = onDownloadModels,
                    enabled = translationPresetModelDownloadEnabled(
                        issues = modelIssues,
                        downloadState = downloadState
                    )
                ) {
                    Text(
                        stringResource(
                            if (downloadState == TranslationPresetModelDownloadState.CURRENT_PRESET) {
                                R.string.settings_translation_preset_downloading_models
                            } else {
                                R.string.settings_translation_preset_download_models
                            }
                        )
                    )
                }
            }
            OutlinedButton(onClick = onCopy) {
                Text(stringResource(R.string.settings_translation_preset_copy))
            }
            if (!TranslationPresetCatalog.isBuiltIn(preset.id)) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.settings_translation_preset_delete))
                }
            }
        }
        if (translationPresetOtherDownloadHintVisible(modelIssues, downloadState)) {
            Text(
                stringResource(R.string.settings_translation_preset_other_download_busy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (modelIssues.isNotEmpty()) {
            val context = LocalContext.current
            val missingText = modelIssues.joinToString("、") { issue ->
                translationPresetModelIssueLabel(context, issue)
            }
            Text(
                stringResource(R.string.settings_translation_preset_missing_models_format, missingText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun translationPresetDisplayName(preset: TranslationPreset): String = when (preset.id) {
    TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH ->
        stringResource(R.string.settings_translation_preset_builtin_manga)
    else -> preset.name
}

private fun translationPresetModelIssueLabel(
    context: Context,
    issue: TranslationPresetModelIssue
): String = when (issue.kind) {
    TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED ->
        context.getString(R.string.settings_translation_preset_local_llm_unsupported)
    TranslationPresetModelIssueKind.LOCAL_LLM_MISSING ->
        context.getString(
            R.string.settings_translation_preset_missing_llm_format,
            issue.llmModelKind?.displayName.orEmpty()
        )
    TranslationPresetModelIssueKind.PADDLE_MISSING ->
        context.getString(
            R.string.settings_translation_preset_missing_paddle_format,
            issue.paddleModelVersion?.let { context.getString(it.displayNameRes) }.orEmpty()
        )
    TranslationPresetModelIssueKind.MANGA_OCR_MISSING ->
        context.getString(R.string.settings_translation_preset_missing_manga_ocr)
    TranslationPresetModelIssueKind.ORIENTATION_MISSING ->
        context.getString(R.string.settings_translation_preset_missing_orientation)
}

private fun translationPresetDownloadModelLabel(
    context: Context,
    issues: List<TranslationPresetModelIssue>
): String {
    val labels = translationPresetDownloadModelLabels(
        issues = issues,
        paddleModelLabel = { version -> context.getString(version.displayNameRes) },
        mangaOcrModelLabel = context.getString(R.string.settings_manga_ocr_model_name),
        orientationModelLabel = context.getString(R.string.settings_orientation_model_name)
    )
    return labels.joinToString(
        separator = context.getString(R.string.settings_translation_preset_download_model_separator)
    ).ifBlank {
        context.getString(R.string.settings_translation_preset_download_models)
    }
}

@Composable
private fun translationPresetSummary(preset: TranslationPreset): String {
    val context = LocalContext.current
    return stringResource(
        R.string.preset_quick_summary_format,
        ocrEngineLabel(preset.ocrEngine),
        presetLlmLabel(preset),
        Languages.nameOf(context, preset.sourceLang),
        Languages.nameOf(context, preset.targetLang)
    )
}

@Composable
private fun presetLlmLabel(preset: TranslationPreset): String = when (preset.translatorEngine) {
    TranslatorEngine.OPENAI -> preset.model.ifBlank { stringResource(R.string.settings_engine_openai_llm) }
    TranslatorEngine.LOCAL_SAKURA -> stringResource(R.string.settings_engine_local_sakura)
    TranslatorEngine.LOCAL_HY_MT2 -> stringResource(R.string.settings_engine_local_hymt2)
    else -> translatorEngineLabel(preset.translatorEngine)
}

@Composable
private fun ocrEngineLabel(engine: OcrEngineKind): String = stringResource(
    when (engine) {
        OcrEngineKind.ML_KIT_AUTO -> R.string.settings_ocr_chip_auto
        OcrEngineKind.ML_KIT_LATIN -> R.string.settings_ocr_chip_latin
        OcrEngineKind.ML_KIT_JAPANESE -> R.string.settings_ocr_chip_japanese
        OcrEngineKind.ML_KIT_KOREAN -> R.string.settings_ocr_chip_korean
        OcrEngineKind.ML_KIT_CHINESE -> R.string.settings_ocr_chip_chinese
        OcrEngineKind.BAIDU -> R.string.settings_ocr_chip_baidu
        OcrEngineKind.TENCENT -> R.string.settings_ocr_chip_tencent
        OcrEngineKind.YOUDAO -> R.string.settings_ocr_chip_youdao
        OcrEngineKind.PADDLE_AI_STUDIO -> R.string.settings_ocr_chip_paddle_ai_studio
        OcrEngineKind.UMI_OCR -> R.string.settings_ocr_chip_umi
        OcrEngineKind.LUNA_OCR -> R.string.settings_ocr_chip_luna
        OcrEngineKind.PADDLE_ONNX -> R.string.settings_ocr_chip_paddle
        OcrEngineKind.MANGA_OCR_JA -> R.string.settings_ocr_chip_manga_ocr_ja
    }
)

@Composable
private fun translatorEngineLabel(engine: TranslatorEngine): String = stringResource(
    when (engine) {
        TranslatorEngine.OPENAI -> R.string.settings_engine_openai_llm
        TranslatorEngine.DEEPL -> R.string.settings_engine_deepl
        TranslatorEngine.YOUDAO_PICTRANS -> R.string.settings_engine_youdao_pictrans
        TranslatorEngine.GOOGLE -> R.string.settings_engine_google
        TranslatorEngine.VOLC -> R.string.settings_engine_volc
        TranslatorEngine.BAIDU_FANYI -> R.string.settings_engine_baidu_fanyi
        TranslatorEngine.TENCENT -> R.string.settings_engine_tencent
        TranslatorEngine.LOCAL_SAKURA -> R.string.settings_engine_local_sakura
        TranslatorEngine.LOCAL_HY_MT2 -> R.string.settings_engine_local_hymt2
    }
)

@Composable
private fun DbnetAdvancedSliderSection(
    title: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps
        )
    }
}

private fun newCustomPresetId(): String = "custom_${System.currentTimeMillis()}"

internal const val TRANSLATION_PRESET_COLLAPSED_LIMIT: Int = 3

internal fun translationPresetCollapseToggleVisible(totalCount: Int): Boolean =
    totalCount > TRANSLATION_PRESET_COLLAPSED_LIMIT

internal fun <T> translationPresetVisibleItems(
    items: List<T>,
    expanded: Boolean
): List<T> = if (expanded || !translationPresetCollapseToggleVisible(items.size)) {
    items
} else {
    items.take(TRANSLATION_PRESET_COLLAPSED_LIMIT)
}

internal fun shouldShowOverlayFontDeleteTipBeforeImport(
    currentFileName: String,
    fonts: List<OverlayFontEntry>
): Boolean = currentFileName.isBlank() && OverlayFontPolicy.normalizeImportedFonts(fonts).isEmpty()

internal fun overlayFontDeleteTipAckLabel(
    baseLabel: String,
    countdown: Int
): String = if (countdown > 0) "($countdown) $baseLabel" else baseLabel

@Composable
private fun SectionCard(
    title: String,
    anchorKey: String? = null,
    onAnchor: ((String, Int) -> Unit)? = null,
    helpText: String? = null,
    content: @Composable () -> Unit
) {
    val baseModifier = Modifier.fillMaxWidth()
    val cardModifier = if (anchorKey != null && onAnchor != null) {
        baseModifier.onGloballyPositioned { coords ->
            onAnchor(anchorKey, coords.positionInParent().y.toInt())
        }
    } else baseModifier
    Card(
        modifier = cardModifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                helpText?.let { SettingHelpTooltip(text = it) }
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingHelpTooltip(
    text: String,
    modifier: Modifier = Modifier
) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        },
        state = state,
        modifier = modifier
    ) {
        IconButton(
            onClick = {
                if (state.isVisible) {
                    state.dismiss()
                } else {
                    scope.launch { state.show() }
                }
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.settings_help_content_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { p -> { Text(p) } },
        singleLine = true,
        modifier = modifier,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.secret_hide else R.string.secret_show
                    )
                )
            }
        }
    )
}

/**
 * 应用专属语言选项。tag = "" 表示跟随系统；其余是 BCP-47 标签。
 * 增加新语言时只需在 [APP_LANGUAGE_OPTIONS] 追加一行 + `values-xxx/strings.xml` 提供翻译
 * + `xml/locales_config.xml` 声明，无需改 UI 代码。
 */
private data class AppLanguageOption(
    val tag: String,
    @androidx.annotation.StringRes val labelRes: Int
)

private val APP_LANGUAGE_OPTIONS: List<AppLanguageOption> = listOf(
    AppLanguageOption("", R.string.settings_app_lang_follow_system),
    AppLanguageOption("zh-CN", R.string.settings_app_lang_zh),
    AppLanguageOption("en", R.string.settings_app_lang_en),
    // 未来扩展：zh-TW（繁中）/ mn（蒙）/ ug（维）等只需在此追加
)

/**
 * 应用专属语言切换。基于 [androidx.appcompat.app.AppCompatDelegate] 的 Per-App Languages，
 * 由系统持久化（LocaleManager），无需我们写本地存储；Android 13+ 切换后 framework 自动
 * 重建 Activity（[com.gameocr.app.ui.MainActivity] 的 route 用 rememberSaveable 保持）。
 *
 * "跟随系统" = 写入空 LocaleListCompat。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppLanguageSelector() {
    // 归一化系统返回的 BCP-47（"zh-Hans-CN" / "zh" / "en-US" 等）到 options 里精确 tag。
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val exact = APP_LANGUAGE_OPTIONS.firstOrNull {
            it.tag.isNotEmpty() && raw.equals(it.tag, ignoreCase = true)
        }
        if (exact != null) return exact.tag
        val primary = raw.substringBefore('-').lowercase()
        return APP_LANGUAGE_OPTIONS
            .firstOrNull { it.tag.startsWith(primary, ignoreCase = true) && it.tag.isNotEmpty() }
            ?.tag
            ?: ""
    }

    val context = LocalContext.current
    val initial = remember { normalize(com.gameocr.app.data.AppLocalePrefs.read(context)) }
    var tag by remember { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }

    val currentOption = APP_LANGUAGE_OPTIONS.firstOrNull { it.tag == tag } ?: APP_LANGUAGE_OPTIONS.first()
    val currentLabel = stringResource(currentOption.labelRes)

    val apply: (String) -> Unit = { newTag ->
        if (newTag != tag) {
            tag = newTag
            // 自管持久化：MainActivity.attachBaseContext 会在 recreate 后读 prefs 并包装
            // Configuration locale，绕开 AppCompatDelegate 在 ComponentActivity 上的持久化不稳问题。
            com.gameocr.app.data.AppLocalePrefs.write(context, newTag)
            (context as? android.app.Activity)?.recreate()
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            APP_LANGUAGE_OPTIONS.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(stringResource(opt.labelRes)) },
                    onClick = {
                        expanded = false
                        apply(opt.tag)
                    }
                )
            }
        }
    }
    Text(
        stringResource(R.string.settings_app_lang_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 主题模式（白天 / 夜间 / 跟随系统）。通过 [LocalThemeMode] 直接驱动 Compose 重组，
 * 不重建 Activity，瞬时生效。持久化由 [ThemeModeController.setMode] 内部完成。
 */
@Composable
private fun ThemeModeSelector() {
    val controller = com.gameocr.app.ui.theme.LocalThemeMode.current
    val mode = controller.mode
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        EngineChip(mode, com.gameocr.app.ui.theme.ThemeMode.FOLLOW_SYSTEM, stringResource(R.string.settings_theme_follow_system)) { controller.setMode(it) }
        EngineChip(mode, com.gameocr.app.ui.theme.ThemeMode.LIGHT, stringResource(R.string.settings_theme_light)) { controller.setMode(it) }
        EngineChip(mode, com.gameocr.app.ui.theme.ThemeMode.DARK, stringResource(R.string.settings_theme_dark)) { controller.setMode(it) }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    helpText: String? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        Text(
            text = label,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
                .alpha(if (enabled) 1f else 0.4f)
        )
        helpText?.let { SettingHelpTooltip(text = it) }
    }
}

@Composable
private fun <T> EngineChip(
    current: T,
    target: T,
    label: String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    FilterChip(
        selected = current == target,
        onClick = { onSelect(target) },
        label = { Text(label) },
        enabled = enabled
    )
}

/** OCR 引擎 → 用户可读 chip 标签资源 id（联动提示 dialog 复用）。 */
@Composable
private fun OverlayFontChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(max = 180.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .pointerInput(label, selected) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@androidx.annotation.StringRes
private fun ocrEngineLabelRes(engine: com.gameocr.app.data.OcrEngineKind): Int = when (engine) {
    com.gameocr.app.data.OcrEngineKind.ML_KIT_AUTO -> R.string.settings_ocr_chip_auto
    com.gameocr.app.data.OcrEngineKind.ML_KIT_LATIN -> R.string.settings_ocr_chip_latin
    com.gameocr.app.data.OcrEngineKind.ML_KIT_JAPANESE -> R.string.settings_ocr_chip_japanese
    com.gameocr.app.data.OcrEngineKind.ML_KIT_KOREAN -> R.string.settings_ocr_chip_korean
    com.gameocr.app.data.OcrEngineKind.ML_KIT_CHINESE -> R.string.settings_ocr_chip_chinese
    com.gameocr.app.data.OcrEngineKind.BAIDU -> R.string.settings_ocr_chip_baidu
    com.gameocr.app.data.OcrEngineKind.TENCENT -> R.string.settings_ocr_chip_tencent
    com.gameocr.app.data.OcrEngineKind.YOUDAO -> R.string.settings_ocr_chip_youdao
    com.gameocr.app.data.OcrEngineKind.PADDLE_AI_STUDIO -> R.string.settings_ocr_chip_paddle_ai_studio
    com.gameocr.app.data.OcrEngineKind.UMI_OCR -> R.string.settings_ocr_chip_umi
    com.gameocr.app.data.OcrEngineKind.LUNA_OCR -> R.string.settings_ocr_chip_luna
    com.gameocr.app.data.OcrEngineKind.PADDLE_ONNX -> R.string.settings_ocr_chip_paddle
    com.gameocr.app.data.OcrEngineKind.MANGA_OCR_JA -> R.string.settings_ocr_chip_manga_ocr_ja
}

/**
 * OCR 联动提示用。两种方向：
 *  - [FixOcr]：用户改源语言后当前 OCR 不支持，推荐改 OCR 端（旧行为）
 *  - [FixSource]：用户改 OCR 端（引擎 / 端点 / 内部语种）后与当前源语言不匹配，
 *                 推荐改源语言到匹配值——不是撤销用户操作
 */
private sealed class OcrLangIssue {
    abstract val sourceCode: String

    data class FixOcr(
        override val sourceCode: String,
        val recommendation: com.gameocr.app.ocr.OcrLanguageCapability.Recommendation
    ) : OcrLangIssue()

    data class FixSource(
        override val sourceCode: String,
        /** 建议把 sourceLang 改成这个 BCP-47 值，跟 OCR 端当前设置匹配。 */
        val recommendedSourceCode: String
    ) : OcrLangIssue()
}

internal enum class OpenAiFallbackField {
    BASE_URL,
    API_KEY,
    MODEL,
}

internal const val SAKURA_TARGET_LANG_ZH_CN = "zh-CN"

internal data class SakuraLanguageIssue(
    val sourceUnsupported: Boolean,
    val targetUnsupported: Boolean,
)

internal fun sakuraLanguageIssue(sourceLang: String, targetLang: String): SakuraLanguageIssue {
    return SakuraLanguageIssue(
        sourceUnsupported = !supportsSakuraSource(sourceLang),
        targetUnsupported = !supportsSakuraTarget(targetLang),
    )
}

internal fun supportsSakuraSource(sourceLang: String): Boolean {
    return com.gameocr.app.translate.RoutingTranslator.supportsSakuraSource(sourceLang)
}

internal fun supportsSakuraTarget(targetLang: String): Boolean {
    return com.gameocr.app.translate.RoutingTranslator.supportsSakuraTarget(targetLang)
}

internal fun supportsSakuraLanguagePair(sourceLang: String, targetLang: String): Boolean {
    return supportsSakuraSource(sourceLang) && supportsSakuraTarget(targetLang)
}

internal fun isLocalLlmEngine(engine: TranslatorEngine): Boolean = when (engine) {
    TranslatorEngine.LOCAL_SAKURA,
    TranslatorEngine.LOCAL_HY_MT2 -> true
    else -> false
}

internal fun localLlmModelKindFor(engine: TranslatorEngine): LlmModelKind? = when (engine) {
    TranslatorEngine.LOCAL_SAKURA -> LlmModelKind.SAKURA_1_5B_Q4
    TranslatorEngine.LOCAL_HY_MT2 -> LlmModelKind.HY_MT2_1_8B_Q4_K_M
    else -> null
}

internal enum class TranslationPresetModelIssueKind {
    LOCAL_LLM_UNSUPPORTED,
    LOCAL_LLM_MISSING,
    PADDLE_MISSING,
    MANGA_OCR_MISSING,
    ORIENTATION_MISSING,
}

internal data class TranslationPresetModelIssue(
    val kind: TranslationPresetModelIssueKind,
    val llmModelKind: LlmModelKind? = null,
    val paddleModelVersion: PaddleModelVersion? = null,
) {
    val downloadable: Boolean
        get() = kind != TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED
}

internal fun translationPresetModelIssues(
    preset: TranslationPreset,
    localLlmDeviceCapable: Boolean,
    llmModelReady: (LlmModelKind) -> Boolean,
    paddleModelReady: (PaddleModelVersion) -> Boolean,
    mangaOcrModelReady: Boolean,
    orientationModelReady: Boolean,
): List<TranslationPresetModelIssue> = buildList {
    localLlmModelKindFor(preset.translatorEngine)?.let { kind ->
        if (!localLlmDeviceCapable) {
            add(TranslationPresetModelIssue(TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED))
        } else if (!llmModelReady(kind)) {
            add(
                TranslationPresetModelIssue(
                    kind = TranslationPresetModelIssueKind.LOCAL_LLM_MISSING,
                    llmModelKind = kind
                )
            )
        }
    }
    when (preset.ocrEngine) {
        OcrEngineKind.PADDLE_ONNX -> {
            if (!paddleModelReady(preset.paddleModelVersion)) {
                add(
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = preset.paddleModelVersion
                    )
                )
            }
        }
        OcrEngineKind.MANGA_OCR_JA -> {
            if (!mangaOcrModelReady) {
                add(TranslationPresetModelIssue(TranslationPresetModelIssueKind.MANGA_OCR_MISSING))
            }
            if (!paddleModelReady(preset.paddleModelVersion)) {
                add(
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = preset.paddleModelVersion
                    )
                )
            }
        }
        OcrEngineKind.ML_KIT_AUTO,
        OcrEngineKind.ML_KIT_LATIN,
        OcrEngineKind.ML_KIT_JAPANESE,
        OcrEngineKind.ML_KIT_KOREAN,
        OcrEngineKind.ML_KIT_CHINESE,
        OcrEngineKind.BAIDU,
        OcrEngineKind.TENCENT,
        OcrEngineKind.YOUDAO,
        OcrEngineKind.PADDLE_AI_STUDIO,
        OcrEngineKind.UMI_OCR,
        OcrEngineKind.LUNA_OCR -> Unit
    }
    if (preset.textOrientationAutoDetect && !orientationModelReady) {
        add(TranslationPresetModelIssue(TranslationPresetModelIssueKind.ORIENTATION_MISSING))
    }
}

internal fun translationPresetCanApply(issues: List<TranslationPresetModelIssue>): Boolean =
    issues.isEmpty()

internal fun normalizedTranslationPresetName(input: String): String? =
    input.trim().takeIf { it.isNotEmpty() }

internal fun translationPresetNameExists(
    nameInput: String,
    existingNames: Iterable<String>,
): Boolean {
    val name = normalizedTranslationPresetName(nameInput) ?: return false
    return existingNames.any { existingName ->
        normalizedTranslationPresetName(existingName)?.equals(name, ignoreCase = true) == true
    }
}

internal fun translationPresetShortNameFor(name: String): String =
    name.take(8)

internal fun namedTranslationPresetOrNull(
    preset: TranslationPreset,
    nameInput: String,
    id: String = preset.id,
): TranslationPreset? {
    val name = normalizedTranslationPresetName(nameInput) ?: return null
    return preset.copy(
        id = id,
        name = name,
        shortName = translationPresetShortNameFor(name),
    )
}

internal enum class TranslationPresetModelDownloadState {
    IDLE,
    CURRENT_PRESET,
    BLOCKED_BY_OTHER_DOWNLOAD,
}

internal fun translationPresetModelDownloadState(
    presetId: String,
    activePresetDownloadId: String?,
    modelDownloading: Boolean,
): TranslationPresetModelDownloadState = when {
    activePresetDownloadId == presetId -> TranslationPresetModelDownloadState.CURRENT_PRESET
    modelDownloading -> TranslationPresetModelDownloadState.BLOCKED_BY_OTHER_DOWNLOAD
    else -> TranslationPresetModelDownloadState.IDLE
}

internal fun translationPresetModelDownloadEnabled(
    issues: List<TranslationPresetModelIssue>,
    downloadState: TranslationPresetModelDownloadState,
): Boolean = downloadState == TranslationPresetModelDownloadState.IDLE && issues.any { it.downloadable }

internal fun translationPresetDownloadModelLabels(
    issues: List<TranslationPresetModelIssue>,
    paddleModelLabel: (PaddleModelVersion) -> String,
    mangaOcrModelLabel: String,
    orientationModelLabel: String,
): List<String> = issues.asSequence()
    .filter { it.downloadable }
    .mapNotNull { issue ->
        when (issue.kind) {
            TranslationPresetModelIssueKind.LOCAL_LLM_MISSING ->
                issue.llmModelKind?.displayName
            TranslationPresetModelIssueKind.PADDLE_MISSING ->
                issue.paddleModelVersion?.let(paddleModelLabel)
            TranslationPresetModelIssueKind.MANGA_OCR_MISSING ->
                mangaOcrModelLabel
            TranslationPresetModelIssueKind.ORIENTATION_MISSING ->
                orientationModelLabel
            TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED ->
                null
        }
    }
    .distinct()
    .toList()

internal fun translationPresetOtherDownloadHintVisible(
    issues: List<TranslationPresetModelIssue>,
    downloadState: TranslationPresetModelDownloadState,
): Boolean = downloadState == TranslationPresetModelDownloadState.BLOCKED_BY_OTHER_DOWNLOAD &&
    issues.any { it.downloadable }

internal fun isCloudOcrEngineForUpscaleWarning(engine: OcrEngineKind): Boolean = when (engine) {
    OcrEngineKind.BAIDU,
    OcrEngineKind.TENCENT,
    OcrEngineKind.YOUDAO,
    OcrEngineKind.PADDLE_AI_STUDIO -> true
    OcrEngineKind.ML_KIT_AUTO,
    OcrEngineKind.ML_KIT_LATIN,
    OcrEngineKind.ML_KIT_JAPANESE,
    OcrEngineKind.ML_KIT_KOREAN,
    OcrEngineKind.ML_KIT_CHINESE,
    OcrEngineKind.UMI_OCR,
    OcrEngineKind.LUNA_OCR,
    OcrEngineKind.PADDLE_ONNX,
    OcrEngineKind.MANGA_OCR_JA -> false
}

internal fun cloudOcrUpscaleWarningVisible(
    engine: OcrEngineKind,
    upscale2x: Boolean,
): Boolean = upscale2x && isCloudOcrEngineForUpscaleWarning(engine)

internal fun missingOpenAiFallbackFields(
    baseUrl: String,
    apiKey: String,
    model: String,
): List<OpenAiFallbackField> = buildList {
    if (baseUrl.isBlank()) add(OpenAiFallbackField.BASE_URL)
    if (apiKey.isBlank()) add(OpenAiFallbackField.API_KEY)
    if (model.isBlank()) add(OpenAiFallbackField.MODEL)
}

@Composable
private fun CustomThemeEditor(
    bg: Int, onBgChange: (Int) -> Unit,
    fg: Int, onFgChange: (Int) -> Unit,
    border: Int, onBorderChange: (Int) -> Unit,
    borderW: Float, onBorderWChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ArgbPicker(stringResource(R.string.settings_custom_color_bg), bg, onBgChange)
        ArgbPicker(stringResource(R.string.settings_custom_color_fg), fg, onFgChange)
        ArgbPicker(stringResource(R.string.settings_custom_color_border), border, onBorderChange)
        Text(stringResource(R.string.settings_custom_color_border_w_format, borderW.toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(value = borderW, onValueChange = onBorderWChange, valueRange = 0f..6f, steps = 5)
    }
}

@Composable
private fun ArgbPicker(label: String, argb: Int, onChange: (Int) -> Unit) {
    val a = ((argb ushr 24) and 0xFF)
    val r = ((argb ushr 16) and 0xFF)
    val g = ((argb ushr 8) and 0xFF)
    val b = (argb and 0xFF)
    fun pack(na: Int, nr: Int, ng: Int, nb: Int): Int =
        ((na and 0xFF) shl 24) or ((nr and 0xFF) shl 16) or ((ng and 0xFF) shl 8) or (nb and 0xFF)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(argb),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
            )
            Text(
                "$label  #${"%08X".format(argb)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        SmallSlider("A", a) { onChange(pack(it, r, g, b)) }
        SmallSlider("R", r) { onChange(pack(a, it, g, b)) }
        SmallSlider("G", g) { onChange(pack(a, r, it, b)) }
        SmallSlider("B", b) { onChange(pack(a, r, g, it)) }
    }
}

@Composable
private fun SmallSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.size(width = 16.dp, height = 24.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.size(width = 32.dp, height = 24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaddleSection(
    status: String,
    downloading: Boolean,
    modelReady: Boolean,
    modelVersion: com.gameocr.app.data.PaddleModelVersion,
    onModelVersionChange: (com.gameocr.app.data.PaddleModelVersion) -> Unit,
    onDownload: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 模型版本下拉选择
        var versionExpanded by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.settings_paddle_model_version_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.padding(start = 6.dp)) {
                OutlinedCard(
                    onClick = { versionExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            stringResource(modelVersion.displayNameRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = versionExpanded)
                    }
                }
                DropdownMenu(
                    expanded = versionExpanded,
                    onDismissRequest = { versionExpanded = false }
                ) {
                    com.gameocr.app.data.PaddleModelVersion.entries.forEach { ver ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(ver.displayNameRes), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(ver.descRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onModelVersionChange(ver)
                                versionExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_paddle_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 状态行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Box(modifier = Modifier.size(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = downloadableModelDownloadEnabled(
                    downloading = downloading,
                    modelReady = modelReady,
                ),
                onClick = onDownload,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(
                    if (downloading) R.string.settings_paddle_btn_processing else R.string.settings_paddle_btn_auto_download
                ))
            }
            OutlinedButton(
                enabled = !downloading,
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_paddle_btn_local_import)) }
        }

        var showDeleteConfirm by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.settings_paddle_btn_delete)) }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
                text = { Text(stringResource(R.string.settings_model_delete_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) { Text(stringResource(R.string.settings_model_delete_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.settings_model_delete_confirm_no))
                    }
                }
            )
        }
    }
}

/** manga-ocr 端侧 OCR Section。与 [PaddleSection] 一一对应；唯一差异在文案与状态格式（MB vs KB）。 */
@Composable
private fun MangaOcrSection(
    status: String,
    downloading: Boolean,
    modelReady: Boolean,
    onDownload: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.settings_paddle_model_version_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.settings_manga_ocr_model_name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        // 模型描述已合并到 OCR 引擎选择那段 settings_ocr_intro 的 bullet 列表里；此处不再重复显示。

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Box(modifier = Modifier.size(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = downloadableModelDownloadEnabled(
                    downloading = downloading,
                    modelReady = modelReady,
                ),
                onClick = onDownload,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(
                    if (downloading) R.string.settings_manga_ocr_btn_processing else R.string.settings_manga_ocr_btn_auto_download
                ))
            }
            OutlinedButton(
                enabled = !downloading,
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_manga_ocr_btn_local_import)) }
        }

        var showDeleteConfirm by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.settings_manga_ocr_btn_delete)) }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
                text = { Text(stringResource(R.string.settings_model_delete_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) { Text(stringResource(R.string.settings_model_delete_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.settings_model_delete_confirm_no))
                    }
                }
            )
        }
    }
}

/**
 * 弧菜单按钮顺序编辑器。Compose 原生拖拽排序：
 * - 被拖项 zIndex 升层，graphicsLayer.translationY 跟手；
 * - 非拖项按 (draggedIdx, targetIdx) 计算「让位偏移」，animateFloatAsState 平滑过渡，
 *   targetIdx = draggedIdx + round(dragOffsetY / slotHeightPx)；
 * - 落位用相同 round 公式，保证落点与视觉一致。
 */
@Composable
private fun OrientationModelSection(
    status: String,
    downloading: Boolean,
    modelReady: Boolean,
    onDownload: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_orientation_model_name),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.settings_orientation_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Box(modifier = Modifier.size(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = downloadableModelDownloadEnabled(
                    downloading = downloading,
                    modelReady = modelReady,
                ),
                onClick = onDownload,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(
                        if (downloading) {
                            R.string.settings_orientation_model_btn_processing
                        } else {
                            R.string.settings_orientation_model_btn_auto_download
                        }
                    )
                )
            }
            OutlinedButton(
                enabled = !downloading,
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_orientation_model_btn_local_import)) }
        }
        var showDeleteConfirm by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.settings_orientation_model_btn_delete)) }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
                text = { Text(stringResource(R.string.settings_model_delete_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) { Text(stringResource(R.string.settings_model_delete_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.settings_model_delete_confirm_no))
                    }
                }
            )
        }
    }
}

@Composable
private fun ArcMenuOrderEditor(
    order: List<MenuItemId>,
    currentSkill: com.gameocr.app.data.FloatingSkill,
    onReorder: (List<MenuItemId>) -> Unit
) {
    val itemHeight = 56.dp
    val itemSpacing = 6.dp
    // 一个槽 = item + 行间距，落位 / 让位都按这个步长算
    val slotHeightPx = with(LocalDensity.current) { (itemHeight + itemSpacing).toPx() }
    var draggedIdx by remember { mutableStateOf(-1) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    val targetIdx = if (draggedIdx < 0) -1
        else (draggedIdx + (dragOffsetY / slotHeightPx).roundToInt())
            .coerceIn(0, order.size - 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        order.forEachIndexed { idx, id ->
            val isDragged = idx == draggedIdx
            // 让位规则：拖动项 idx → 目标 targetIdx，途经的项整体向反方向挪 1 个 slot
            val displacementPx = when {
                draggedIdx < 0 || isDragged -> 0f
                // 向下拖：原本在拖动项下方、且 ≤ targetIdx 的项要往上让一格
                draggedIdx < idx && idx <= targetIdx -> -slotHeightPx
                // 向上拖：原本在拖动项上方、且 ≥ targetIdx 的项要往下让一格
                draggedIdx > idx && idx >= targetIdx -> slotHeightPx
                else -> 0f
            }
            val animatedDisplacement by animateFloatAsState(
                targetValue = displacementPx,
                label = "arc_menu_displace_$idx"
            )
            val translation = if (isDragged) dragOffsetY else animatedDisplacement
            val bgColor = if (isDragged) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    // 被拖项浮到最上层，避免后绘制的 Row 把它盖住
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = translation }
                    .background(bgColor, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp)
                    .pointerInput(order, idx) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedIdx = idx
                                dragOffsetY = 0f
                            },
                            onDrag = { _, drag ->
                                dragOffsetY += drag.y
                            },
                            onDragEnd = {
                                val slots = (dragOffsetY / slotHeightPx).roundToInt()
                                val target = (idx + slots).coerceIn(0, order.size - 1)
                                if (target != idx) {
                                    val next = order.toMutableList().apply {
                                        val moved = removeAt(idx)
                                        add(target, moved)
                                    }
                                    onReorder(next)
                                }
                                draggedIdx = -1
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                draggedIdx = -1
                                dragOffsetY = 0f
                            }
                        )
                    }
            ) {
                // 左侧：三横杠拖动手柄（自绘 vector），明示「这一行可拖动」
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.settings_arc_menu_drag_handle),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(modifier = Modifier.width(12.dp))
                // 中间：菜单项真实图标
                Icon(
                    painter = androidx.compose.ui.res.painterResource(menuItemIconRes(id, currentSkill)),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Box(modifier = Modifier.width(12.dp))
                Text(
                    menuItemLabel(id, currentSkill),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            Box(modifier = Modifier.height(itemSpacing))
        }
    }
}

private class PendingModelDownload(
    val modelLabel: String,
    val warning: ModelDownloadNetworkWarning,
    val onConfirmed: () -> Unit,
)

internal fun modelDownloadNetworkWarningMessageRes(
    warning: ModelDownloadNetworkWarning,
): Int = when (warning) {
    ModelDownloadNetworkWarning.CELLULAR ->
        R.string.settings_model_download_network_warning_cellular_format
    ModelDownloadNetworkWarning.UNKNOWN ->
        R.string.settings_model_download_network_warning_unknown_format
}

@Composable
private fun menuItemLabel(id: MenuItemId, currentSkill: com.gameocr.app.data.FloatingSkill): String = when (id) {
        MenuItemId.LOOP -> stringResource(R.string.settings_arc_menu_item_loop)
        MenuItemId.REGION -> stringResource(R.string.settings_arc_menu_item_region)
        MenuItemId.LANGUAGE_PAIR -> stringResource(R.string.settings_arc_menu_item_language_pair)
        MenuItemId.PRESET_SWITCH -> stringResource(R.string.settings_arc_menu_item_preset)
        MenuItemId.SETTINGS -> stringResource(R.string.settings_arc_menu_item_settings)
        MenuItemId.HOME -> stringResource(R.string.settings_arc_menu_item_home)
    // 技能槽：跟弧菜单实际显示一致，文案 = 「切换主球操作 — <切换目标>」。
    // 未来加新 FloatingSkill 值时只需扩展 menuItemIconRes / 这里的 when，无需改文案模板。
    MenuItemId.FULL_SCREEN_SKILL -> {
        val nextSkillName = stringResource(
            when (currentSkill) {
                com.gameocr.app.data.FloatingSkill.FULL_SCREEN -> R.string.menu_word_select
                com.gameocr.app.data.FloatingSkill.WORD_SELECT -> R.string.menu_full_screen_skill
            }
        )
        stringResource(R.string.settings_arc_menu_item_skill_format, nextSkillName)
    }
}

private fun menuItemIconRes(id: MenuItemId, currentSkill: com.gameocr.app.data.FloatingSkill): Int = when (id) {
        MenuItemId.LOOP -> R.drawable.ic_menu_loop
        MenuItemId.REGION -> R.drawable.ic_menu_region
        MenuItemId.LANGUAGE_PAIR -> R.drawable.ic_menu_language_pair
        MenuItemId.PRESET_SWITCH -> R.drawable.ic_menu_preset
        MenuItemId.SETTINGS -> R.drawable.ic_menu_settings
        MenuItemId.HOME -> R.drawable.ic_menu_home
    // 技能槽预览要跟实际弧形菜单一致：图标表示“点击后切到的技能”。
    MenuItemId.FULL_SCREEN_SKILL -> when (currentSkill) {
        com.gameocr.app.data.FloatingSkill.FULL_SCREEN -> R.drawable.ic_menu_word_select
        com.gameocr.app.data.FloatingSkill.WORD_SELECT -> R.drawable.ic_menu_full_screen
    }
}

/**
 * 端侧 LLM 翻译 Section。结构与 [PaddleSection] / [MangaOcrSection] 1:1 对应：
 * 模型名 → 描述 → 状态行 → 主按钮对（下载 / 本地导入）→ 镜像 URL → 删除按钮。
 * 与 OCR 两个 Section 唯一差异：不再单独印 "powered by" / license 文案——许可信息走关于页统一展示。
 */
@Composable
private fun LocalLlmSection(
    currentKindLabel: String,
    deviceCapable: Boolean,
    modelReady: Boolean,
    status: String,
    downloading: Boolean,
    onDownload: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.llm_section_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                currentKindLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        Text(
            stringResource(R.string.llm_section_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!deviceCapable) {
            Text(
                stringResource(R.string.err_llm_device_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (downloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Box(modifier = Modifier.size(8.dp))
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        // 下载源选择（含自定义 URL）放在「网络」section 内，跨 OCR / 翻译共用；此处不再重复展示。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = localLlmDownloadEnabled(
                    downloading = downloading,
                    deviceCapable = deviceCapable,
                    modelReady = modelReady,
                ),
                onClick = onDownload,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(
                    if (downloading) R.string.settings_paddle_btn_processing else R.string.llm_download_button
                ))
            }
            OutlinedButton(
                enabled = !downloading,
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.settings_paddle_btn_local_import)) }
        }

        var showDeleteConfirm by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.llm_delete_button)) }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
                text = { Text(stringResource(R.string.settings_model_delete_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) { Text(stringResource(R.string.settings_model_delete_confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.settings_model_delete_confirm_no))
                    }
                }
            )
        }
    }
}

internal fun checkingPlaceholderIfUnresolved(currentStatus: String, checkingPlaceholder: String): String =
    currentStatus.ifBlank { checkingPlaceholder }

internal fun localLlmDownloadEnabled(
    downloading: Boolean,
    deviceCapable: Boolean,
    modelReady: Boolean,
): Boolean = !downloading && deviceCapable && !modelReady

internal fun downloadableModelDownloadEnabled(
    downloading: Boolean,
    modelReady: Boolean,
): Boolean = !downloading && !modelReady

@Composable
private fun LlmMirrorRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}
