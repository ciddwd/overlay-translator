package com.gameocr.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.DisposableEffect
import com.gameocr.app.overlay.EdgeInsetPreviewOverlay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import kotlinx.coroutines.Dispatchers
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
    // 翻译引擎"测试连接"按钮的瞬时状态：testing / 结果文字 / 成功色 / OpenAI 拉到的 model 列表。
    // 不进 Settings，纯 UI 状态；切换 engine 不清空（用户切回去还能看到上次的结果）。
    var testRunning by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelPickerExpanded by remember { mutableStateOf(false) }
    var textSize by remember { mutableStateOf(14f) }
    var alpha by remember { mutableStateOf(0.85f) }
    var loopInterval by remember { mutableStateOf("1000") }
    var streaming by remember { mutableStateOf(true) }
    var renderMode by remember { mutableStateOf(RenderMode.BLOCKS) }
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
    var tencentId by remember { mutableStateOf("") }
    var tencentKey by remember { mutableStateOf("") }
    var tencentEndpoint by remember { mutableStateOf(com.gameocr.app.data.TencentOcrEndpoint.GENERAL_BASIC) }
    var tencentLanguage by remember { mutableStateOf(com.gameocr.app.data.TencentOcrLanguage.AUTO) }
    var paddleMirror by remember { mutableStateOf("") }
    var paddleStatus by remember { mutableStateOf("") }
    var paddleDownloading by remember { mutableStateOf(false) }
    var preUpscale by remember { mutableStateOf(false) }
    var preInvert by remember { mutableStateOf(false) }
    var preBinarize by remember { mutableStateOf(false) }
    var a11yVolume by remember { mutableStateOf(false) }
    var floatingSize by remember { mutableStateOf(56f) }
    var floatingSnapEdge by remember { mutableStateOf(true) }
    var floatingAutoDock by remember { mutableStateOf(false) }
    var floatingDockInset by remember { mutableStateOf(0f) }
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

    // —— 搜索：顶部输入 → 下拉匹配项 → 点击 animateScrollTo 到对应 section 顶部 ——
    val scrollState = rememberScrollState()
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
        tencentSecretId = tencentId,
        tencentSecretKey = tencentKey,
        tencentOcrEndpoint = tencentEndpoint,
        tencentOcrLanguage = tencentLanguage,
        paddleModelMirrorUrl = paddleMirror,
        a11yVolumeTrigger = a11yVolume,
        translatorEngine = translatorEngine,
        deeplApiKey = deeplKey,
        deeplPro = deeplPro,
        deeplProtocol = deeplProtocol,
        deeplBaseUrl = deeplBaseUrl,
        deeplBearerAuth = deeplBearerAuth,
        deeplCustomToken = deeplCustomToken,
        youdaoAppKey = youdaoAppKey,
        youdaoAppSecret = youdaoAppSecret,
        floatingButtonSizeDp = floatingSize.toInt(),
        floatingButtonSnapToEdge = floatingSnapEdge,
        floatingButtonAutoDock = floatingAutoDock,
        floatingButtonDockInsetDp = floatingDockInset.toInt(),
        overlayAllowWrap = allowWrap,
        overlayAvoidCollision = avoidCollision,
        apiTimeoutSeconds = apiTimeoutSec.toInt(),
        mergeAdjacentBlocks = mergeAdjacent,
        mergeStrength = mergeStrength,
        cleartextAllowedHosts = parseCleartextHosts(cleartextHostsText)
    )
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
            tencentId = tencentId, tencentKey = tencentKey, tencentEndpoint = tencentEndpoint,
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
            translatorEngine = translatorEngine,
            deeplKey = deeplKey,
            deeplPro = deeplPro,
            deeplProtocol = deeplProtocol,
            deeplBaseUrl = deeplBaseUrl,
            deeplBearerAuth = deeplBearerAuth,
            deeplCustomToken = deeplCustomToken,
            paddleMirror = paddleMirror,
            youdaoAppKey = youdaoAppKey,
            youdaoAppSecret = youdaoAppSecret
        )
    }

    val tryBack: () -> Unit = {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    BackHandler { tryBack() }

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
            translatorEngine = s.translatorEngine
            deeplKey = s.deeplApiKey
            youdaoAppKey = s.youdaoAppKey
            youdaoAppSecret = s.youdaoAppSecret
            deeplPro = s.deeplPro
            deeplProtocol = s.deeplProtocol
            deeplBaseUrl = s.deeplBaseUrl
            deeplBearerAuth = s.deeplBearerAuth
            deeplCustomToken = s.deeplCustomToken
            textSize = s.overlayTextSizeSp.toFloat()
            alpha = s.overlayAlpha
            loopInterval = s.captureLoopIntervalMs.toString()
            streaming = s.streamingTranslate
            renderMode = s.renderMode
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
            tencentId = s.tencentSecretId
            tencentKey = s.tencentSecretKey
            tencentEndpoint = s.tencentOcrEndpoint
            tencentLanguage = s.tencentOcrLanguage
            paddleMirror = s.paddleModelMirrorUrl
            // 不阻塞主线程：file.exists() + file.length() 走 IO Dispatcher。先给占位
            // 文字，IO 完成后再覆盖；进设置的瞬间不卡顿。
            paddleStatus = paddleStatusPlaceholder
            preUpscale = s.preprocess.upscale2x
            preInvert = s.preprocess.invert
            preBinarize = s.preprocess.binarize
            a11yVolume = s.a11yVolumeTrigger
            floatingSize = s.floatingButtonSizeDp.toFloat()
            floatingSnapEdge = s.floatingButtonSnapToEdge
            floatingAutoDock = s.floatingButtonAutoDock
            floatingDockInset = s.floatingButtonDockInsetDp.toFloat()
            pinnedLanguages = s.pinnedLanguages
            allowWrap = s.overlayAllowWrap
            avoidCollision = s.overlayAvoidCollision
            apiTimeoutSec = s.apiTimeoutSeconds.toFloat()
            mergeAdjacent = s.mergeAdjacentBlocks
            mergeStrength = s.mergeStrength
            cleartextHostsText = s.cleartextAllowedHosts.joinToString("\n")
            // 同一个 snapshot 内 capture 初始 Settings——既走 buildSnapshot() 单源路径，
            // 又跟所有 state 在同一原子 apply 里，不会被中间帧看到。
            initialSettings = buildSnapshot()
        }
    }

    // paddleStatus 独立异步加载：file.exists() / file.length() 走 IO 线程，避免阻塞首帧。
    LaunchedEffect(Unit) {
        val status = withContext(Dispatchers.IO) { viewModel.paddleModelStatus() }
        paddleStatus = status
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

            // —— 翻译后端 ——
            SectionCard(title = stringResource(R.string.settings_section_translator), anchorKey = SectionKeys.TRANSLATE, onAnchor = onAnchor) {
                Text(stringResource(R.string.settings_label_translator_engine), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EngineChip(translatorEngine, TranslatorEngine.OPENAI, stringResource(R.string.settings_engine_openai_llm)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.DEEPL, stringResource(R.string.settings_engine_deepl)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.YOUDAO_PICTRANS, stringResource(R.string.settings_engine_youdao_pictrans)) { translatorEngine = it }
                    EngineChip(translatorEngine, TranslatorEngine.GOOGLE, stringResource(R.string.settings_engine_google)) { translatorEngine = it }
                }
                // 切换引擎时清掉上一引擎的测试结果——继续显示会让用户以为新引擎"已经测过"。
                LaunchedEffect(translatorEngine) {
                    testMessage = null
                    testSuccess = false
                    fetchedModels = emptyList()
                    modelPickerExpanded = false
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
                } else {
                    // GOOGLE：无 key，仅提示风险
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
                                    apiTimeoutSeconds = apiTimeoutSec.toInt()
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
                    },
                    pinned = pinnedLanguages,
                    onTogglePin = onTogglePin
                )
                LanguagePicker(
                    label = stringResource(R.string.settings_target_lang),
                    currentCode = targetLang,
                    onSelect = { targetLang = it },
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

                    val defaultPrompt = stringResource(R.string.default_prompt)
                    TextButton(onClick = { prompt = defaultPrompt }) {
                        Text(stringResource(R.string.settings_prompt_reset))
                    }
                    SwitchRow(stringResource(R.string.settings_streaming), streaming) { streaming = it }
                }
            }

            // —— OCR 引擎 ——
            // 端到端翻译引擎（有道图翻）会跳过 OCR 阶段，整个 OCR 设置区当前会被无视——
            // 灰显 + 禁用 chip 让用户一眼明白 + 不能误操作。
            val ocrSectionDisabled = translatorEngine == TranslatorEngine.YOUDAO_PICTRANS
            SectionCard(title = stringResource(R.string.settings_section_ocr), anchorKey = SectionKeys.OCR, onAnchor = onAnchor) {
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
                }

                // 各引擎用途说明：用户经常问"自动/日文/中文/拉丁"的差别
                Text(
                    stringResource(R.string.settings_ocr_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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

                if (ocrEngine == OcrEngineKind.PADDLE_ONNX) {
                    PaddleSection(
                        status = paddleStatus,
                        downloading = paddleDownloading,
                        mirror = paddleMirror,
                        onMirrorChange = { paddleMirror = it },
                        onDownload = {
                            scope.launch {
                                paddleDownloading = true
                                try {
                                    viewModel.savePaddleMirror(paddleMirror)
                                    viewModel.downloadPaddleModels { msg -> paddleStatus = msg }
                                    paddleStatus = viewModel.paddleModelStatus()
                                } catch (t: Throwable) {
                                    paddleStatus = context.getString(
                                        R.string.settings_paddle_download_failed_format,
                                        t.message ?: ""
                                    )
                                } finally {
                                    paddleDownloading = false
                                }
                            }
                        },
                        onImport = { uris ->
                            scope.launch {
                                paddleDownloading = true
                                try {
                                    val n = viewModel.importPaddleFromLocal(uris)
                                    paddleStatus = context.getString(
                                        R.string.settings_paddle_imported_format,
                                        n, viewModel.paddleModelStatus()
                                    )
                                } finally {
                                    paddleDownloading = false
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                viewModel.deletePaddleModels()
                                paddleStatus = viewModel.paddleModelStatus()
                            }
                        }
                    )
                }
                } // 关闭 OCR section 内的"灰显 Column"（ocrSectionDisabled 控制 alpha）
            }

            // —— 图像预处理 ——
            SectionCard(title = stringResource(R.string.settings_section_preprocess), anchorKey = SectionKeys.PREPROCESS, onAnchor = onAnchor) {
                SwitchRow(stringResource(R.string.settings_preprocess_upscale), preUpscale) { preUpscale = it }
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
                }

                Text(stringResource(R.string.settings_textsize_label_format, textSize.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 10f..28f, steps = 17)

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
                    textSize = textSize,
                    alpha = alpha
                )

                // —— 几何项（预览看不到，只能实际触发翻译时看到效果）——
                Text(stringResource(R.string.settings_render_mode_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineChip(renderMode, RenderMode.BLOCKS, stringResource(R.string.settings_render_blocks_chip)) { renderMode = it }
                    EngineChip(renderMode, RenderMode.BANNER, stringResource(R.string.settings_render_banner_chip)) { renderMode = it }
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
            }

            // 给 FAB 留出底部空间，避免最后一项被遮挡
            Box(modifier = Modifier.size(80.dp))
            }

            // 搜索下拉：浮在 Column 之上。匹配项点击后滚到对应 section 顶部并关闭搜索。
            if (searchActive && searchQuery.isNotBlank()) {
                val matches = remember(searchQuery) {
                    SETTING_ITEMS.filter { it.matches(context, searchQuery) }.take(10)
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
 * 译文样式实时预览卡。展示一段假的"原文 + 译文"，按当前 theme/字号/透明度/自定义色/边框渲染。
 *
 * 与 [com.gameocr.app.overlay.OverlayManager] 的视觉保持一致：
 * - 主题颜色映射见 [overlayThemeColors]（务必与 OverlayManager 同步）
 * - alpha 整体应用到 box（模拟 view.setAlpha 的效果，叠加自身像素 alpha）
 * - 棋盘格底色用 linear gradient 模拟实际屏幕背景，让透明度变化肉眼可见
 */
@Composable
private fun OverlayPreviewCard(
    theme: OverlayTheme,
    customBg: Int,
    customFg: Int,
    customBorder: Int,
    customBorderW: Float,
    textSize: Float,
    alpha: Float
) {
    val colors = overlayThemeColors(theme, customBg, customFg, customBorder, customBorderW.toInt())
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
                    .let { m ->
                        if (colors.borderDp > 0) {
                            m.border(
                                colors.borderDp.dp,
                                Color(colors.border),
                                RoundedCornerShape(6.dp)
                            )
                        } else m
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    stringResource(R.string.settings_overlay_preview_sample),
                    color = Color(colors.fg),
                    fontSize = textSize.sp
                )
            }
        }
    }
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
    const val OCR = "ocr"
    const val PREPROCESS = "preprocess"
    const val OVERLAY = "overlay"
    const val FLOATING = "floating"
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
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_translator_engine, listOf("OpenAI", "DeepL", "LLM", "翻译引擎")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_base_url, listOf("base url")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_api_key, listOf("api key")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_model_name, listOf("model", "模型名")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_api_key, listOf("deepl")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_deepl_pro, listOf("deepl pro")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_source_lang, listOf("source", "源语言")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_target_lang, listOf("target", "目标语言")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_prompt, listOf("prompt", "提示词", "system")),
    SearchEntry(SectionKeys.TRANSLATE, R.string.settings_section_translator, R.string.settings_search_item_streaming, listOf("streaming", "流式")),

    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_ocr_switch, listOf("ML Kit", "百度", "腾讯", "Paddle", "OCR engine")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_paddle_download, listOf("ONNX", "v5", "镜像", "mirror")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_baidu_api_key, listOf("baidu", "百度")),
    SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, R.string.settings_search_item_tencent_secret, listOf("tencent", "腾讯")),

    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_upscale, listOf("upscale", "放大", "上采样")),
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_invert, listOf("invert", "反色", "暗底白字")),
    SearchEntry(SectionKeys.PREPROCESS, R.string.settings_section_preprocess, R.string.settings_search_item_binarize, listOf("binarize", "otsu", "二值化")),

    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_render_mode, listOf("紧贴", "横幅", "banner", "render", "display mode")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_placement, listOf("下方", "上方", "覆盖", "below", "above", "overlap", "placement")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_offset, listOf("offset", "微调")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_overlay_theme, listOf("深色", "浅色", "纸张", "霜玻璃", "琥珀", "theme", "dark", "light", "frost", "amber")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_custom_theme, listOf("custom", "border", "自定义", "边框")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_text_size, listOf("font size", "字号", "字体大小")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_alpha, listOf("alpha", "opacity", "透明度")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_floating_size, listOf("floating", "圆球", "悬浮")),
    SearchEntry(SectionKeys.OVERLAY, R.string.settings_section_overlay, R.string.settings_search_item_merge_adjacent, listOf("merge", "合并", "重叠", "拆段")),

    SearchEntry(SectionKeys.TRIGGER, R.string.settings_section_trigger, R.string.settings_search_item_loop_interval, listOf("loop", "循环")),
    SearchEntry(SectionKeys.TRIGGER, R.string.settings_section_trigger, R.string.settings_search_item_a11y_volume, listOf("无障碍", "a11y", "accessibility", "volume", "音量")),

    SearchEntry(SectionKeys.NETWORK, R.string.settings_section_network, R.string.settings_search_item_api_timeout, listOf("timeout", "超时", "网络", "network")),

    SearchEntry(SectionKeys.APP_LANG, R.string.settings_section_app_lang, R.string.settings_section_app_lang, listOf("language", "locale", "语言", "中文", "english", "i18n")),

    SearchEntry(SectionKeys.THEME_MODE, R.string.settings_section_theme_mode, R.string.settings_section_theme_mode, listOf("theme", "夜间", "白天", "深色", "浅色", "dark", "light", "night")),
)

@Composable
private fun SectionCard(
    title: String,
    anchorKey: String? = null,
    onAnchor: ((String, Int) -> Unit)? = null,
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
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
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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
            modifier = Modifier.padding(start = 12.dp).alpha(if (enabled) 1f else 0.4f)
        )
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
    com.gameocr.app.data.OcrEngineKind.PADDLE_ONNX -> R.string.settings_ocr_chip_paddle
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

@Composable
private fun PaddleSection(
    status: String,
    downloading: Boolean,
    mirror: String,
    onMirrorChange: (String) -> Unit,
    onDownload: () -> Unit,
    onImport: (List<android.net.Uri>) -> Unit,
    onDelete: () -> Unit
) {
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onImport(uris) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 当前模型版本占位行：未来支持多版本切换时换成 DropdownMenu，这里先展示当前唯一版本
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
                stringResource(R.string.settings_paddle_model_name),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
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
                enabled = !downloading,
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

        OutlinedTextField(
            value = mirror, onValueChange = onMirrorChange,
            label = { Text(stringResource(R.string.settings_paddle_mirror_label)) },
            placeholder = { Text(stringResource(R.string.settings_paddle_mirror_placeholder)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.settings_paddle_btn_delete)) }
    }
}
