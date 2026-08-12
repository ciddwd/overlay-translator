package com.gameocr.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gameocr.app.BuildConfig
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.MediaProjectionRequestActivity
import com.gameocr.app.capture.RegionPickerActivity
import com.gameocr.app.data.Settings as AppSettings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.gallery.GalleryTaskStatus
import com.gameocr.app.gallery.GalleryTranslationTaskEntity
import com.gameocr.app.gallery.GalleryTranslationRepository
import com.gameocr.app.gallery.GalleryTranslationWorkPolicy
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.OrientationModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import com.gameocr.app.overlay.FloatingMenuTourPalette
import com.gameocr.app.overlay.FloatingMenuTourPrefs
import com.gameocr.app.rom.RomHelper
import com.gameocr.app.service.CaptureService
import com.gameocr.app.service.CaptureServiceState
import com.gameocr.app.shizuku.ShizukuCapabilities
import com.gameocr.app.shizuku.ShizukuManager
import com.gameocr.app.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@SuppressLint("ProduceStateDoesNotAssignValue")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenLegalNotices: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onGalleryImagesSelected: (List<String>) -> Unit,
    onOpenGalleryTasks: () -> Unit,
    onOpenGalleryTask: (String) -> Unit,
    initialStatusPresetPageIndex: Int,
    onStatusPresetPageChanged: (Int) -> Unit,
    initialCarouselPageIndex: Int,
    onCarouselPageChanged: (Int) -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canDrawOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var region by remember { mutableStateOf<CaptureRegion?>(null) }
    var shizukuAvail by remember { mutableStateOf(ShizukuCapabilities.Availability.NOT_INSTALLED) }
    var batteryOk by remember {
        mutableStateOf(RomHelper.isIgnoringBatteryOptimizations(context))
    }
    val serviceRunning by CaptureServiceState.running.collectAsState()
    val appSettings by viewModel.settings.collectAsState(initial = null)
    val featuredGalleryTaskState by produceState<MainGalleryTaskLoadState>(
        initialValue = MainGalleryTaskLoadState.Loading,
        key1 = viewModel,
    ) {
        viewModel.featuredGalleryTask.collectLatest { task ->
            value = MainGalleryTaskLoadState.Loaded(task)
        }
    }
    val featuredGalleryTask =
        (featuredGalleryTaskState as? MainGalleryTaskLoadState.Loaded)?.task
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(
            GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK
        )
    ) { uris ->
        if (uris.isNotEmpty()) onGalleryImagesSelected(uris.map { it.toString() })
    }
    val unsavedPresetName = stringResource(R.string.settings_translation_preset_unsaved_name)
    val presetPlans = remember(appSettings, unsavedPresetName) {
        appSettings?.let { presetCarouselPlans(it, unsavedPresetName) }
    }
    val presets = presetPlans?.presets.orEmpty()
    var presetModelIssues by remember {
        mutableStateOf<Map<String, List<TranslationPresetModelIssue>>?>(null)
    }
    var startMode by remember { mutableStateOf(StartMode.MEDIA_PROJECTION) }
    var userOverrodeMode by remember { mutableStateOf(false) }
    var showClearRegionDialog by remember { mutableStateOf(false) }
    var showSharePrompt by rememberSaveable { mutableStateOf(false) }
    var presetPageSeen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var presetCarouselSeen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var captureGallerySeen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var floatingTourCompleted by remember(context) {
        mutableStateOf(FloatingMenuTourPrefs.isCompleted(context))
    }
    var pendingPresetSwitch by remember { mutableStateOf<TranslationPreset?>(null) }
    var pendingSaveBeforePresetSwitch by remember { mutableStateOf<TranslationPreset?>(null) }
    var pendingPresetSaveName by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val presetNotReadyMessage = stringResource(R.string.main_preset_models_not_ready_message)
    val shareSubject = stringResource(R.string.settings_about_share_subject)
    val shareText = stringResource(
        R.string.settings_about_share_text,
        UpdateChecker.RELEASE_PAGE_URL,
        GITHUB_URL,
    )
    val shareChooserTitle = stringResource(R.string.settings_about_share_chooser)
    val onShareApp: () -> Unit = {
        launchAppShare(
            context = context,
            subject = shareSubject,
            text = shareText,
            chooserTitle = shareChooserTitle,
        )
    }
    LaunchedEffect(presets) {
        presetModelIssues = viewModel.presetModelIssues(presets)
    }
    LaunchedEffect(viewModel) {
        val storedPresetPageSeen = viewModel.hasSeenMainStatusPreset()
        val storedPresetCarouselSeen = viewModel.hasSeenMainPresetCarousel()
        val storedCaptureGallerySeen = viewModel.hasSeenMainCaptureGallery()
        if (presetPageSeen != true) presetPageSeen = storedPresetPageSeen
        if (presetCarouselSeen != true) presetCarouselSeen = storedPresetCarouselSeen
        if (captureGallerySeen != true) captureGallerySeen = storedCaptureGallerySeen
    }
    DisposableEffect(context) {
        val stopObserving = FloatingMenuTourPrefs.observeCompletion(context) { completed ->
            scope.launch { floatingTourCompleted = completed }
        }
        onDispose(stopObserving)
    }

    // 主屏一进就触发自动检查更新。autoCheckIfDue 内部 1h 节流，频繁进出主屏不会浪费 API
     // 额度；只有 hasUpdate（且不是用户已跳过的版本）时才弹 dialog，已最新 / 失败 静默不打扰。
    // hiltViewModel<UpdateViewModel>() 与 AboutContent 内部那个调用拿到同一实例，dialog 共享 state。
    val updateVm: com.gameocr.app.update.UpdateViewModel = hiltViewModel()
    val topUpdateState by updateVm.state.collectAsState()
    val autoChecking by updateVm.autoChecking.collectAsState()
    val mainGestureGuidesEnabled = shouldEnableMainGestureGuides(
        floatingTourCompleted = floatingTourCompleted,
        autoChecking = autoChecking,
        sharePromptVisible = showSharePrompt,
        updateDialogVisible =
            topUpdateState !is com.gameocr.app.update.UpdateViewModel.State.Idle,
    )
    LaunchedEffect(Unit) {
        updateVm.autoCheckIfDue()
        if (!viewModel.recordMainScreenEntryForSharePrompt()) return@LaunchedEffect

        delay(SHARE_PROMPT_DELAY_MS)
        val updateBlocksPrompt =
            updateVm.autoChecking.value ||
                updateVm.state.value !is com.gameocr.app.update.UpdateViewModel.State.Idle
        if (updateBlocksPrompt) return@LaunchedEffect

        viewModel.markSharePromptShown()
        showSharePrompt = true
    }
    // dialog 必须挂在 MainScreen 顶层，否则用户没滚到"关于"卡看不到自动检测的弹窗。
    // 0.3.0 及之前 dialog 只在 AboutContent 内挂载，导致"自动检测确实跑了但用户感觉没反应"。
    UpdateResultDialog(
        state = topUpdateState,
        onDismiss = { updateVm.reset() },
        onOpenRelease = { url ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            updateVm.reset()
        },
        onSkipVersion = { v -> updateVm.skipVersion(v) }
    )

    // 自动检查中的全屏 Loading 遮罩。VM 内有 600ms 最小展示，保证用户来得及看见。
    // 半透明 scrim + clickable 拦截点击防止用户瞎点；fillMaxSize 覆盖整屏（Dialog 例外，
    // Dialog 在另一窗口层之上，不会被这个遮罩盖住）。
    if (autoChecking) {
        AutoUpdateCheckOverlay()
    }

    // Shizuku 就绪时默认选 Shizuku（用户未手动切换过的前提下）。
    // 进入页面时 shizukuAvail 还在初始 NOT_INSTALLED，等 ON_RESUME 探测完才真实；
    // 这里跟着变化走，确保用户进来直接看到最优选项。
    LaunchedEffect(shizukuAvail) {
        if (!userOverrodeMode) {
            startMode = if (shizukuAvail == ShizukuCapabilities.Availability.READY ||
                shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED
            ) StartMode.SHIZUKU else StartMode.MEDIA_PROJECTION
        }
    }

    // binder 死亡 / 重启 / shell 特权变化都会触发重算 Availability，避免「Shizuku 被外部
    // 停了 / 未配对但 UI 还停留在『就绪 ✓』」的不一致。比仅在 ON_RESUME 探测可靠。
    val shizukuBinderAlive by viewModel.shizukuBinderAlive.collectAsState()
    val shizukuShellOk by viewModel.shizukuShellPrivilegeOk.collectAsState()
    LaunchedEffect(shizukuBinderAlive, shizukuShellOk) {
        shizukuAvail = viewModel.shizukuAvailability(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, presets) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    canDrawOverlay = Settings.canDrawOverlays(context)
                    region = viewModel.currentRegion()
                    shizukuAvail = viewModel.shizukuAvailability(context)
                    batteryOk = RomHelper.isIgnoringBatteryOptimizations(context)
                    if (!batteryOk) {
                        repeat(5) {
                            if (!batteryOk) {
                                delay(200)
                                batteryOk =
                                    RomHelper.isIgnoringBatteryOptimizations(context)
                            }
                        }
                    }
                    presetModelIssues = viewModel.presetModelIssues(presets)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showClearRegionDialog) {
        CatalystAlertDialog(
            onDismissRequest = { showClearRegionDialog = false },
            title = { Text(stringResource(R.string.main_clear_region_dialog_title)) },
            text = { Text(stringResource(R.string.main_clear_region_dialog_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearRegionDialog = false
                    scope.launch {
                        viewModel.clearRegion()
                        region = null
                    }
                }) { Text(stringResource(R.string.main_clear_region_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearRegionDialog = false }) {
                    Text(stringResource(R.string.main_clear_region_dialog_cancel))
                }
            }
        )
    }

    if (showSharePrompt) {
        SharePromptDialog(
            onShare = {
                showSharePrompt = false
                onShareApp()
            },
            onDecline = { showSharePrompt = false },
        )
    }

    val applyPresetNow: (TranslationPreset) -> Unit = { preset ->
        scope.launch {
            if (!viewModel.applyTranslationPreset(preset.id)) {
                presetModelIssues = viewModel.presetModelIssues(presets)
                snackbarHostState.showSnackbar(presetNotReadyMessage)
            }
        }
    }

    pendingPresetSwitch?.let { target ->
        CatalystAlertDialog(
            onDismissRequest = { pendingPresetSwitch = null },
            title = { Text(stringResource(R.string.main_preset_unsaved_switch_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.main_preset_unsaved_switch_message,
                        translationPresetDisplayName(target),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPresetSwitch = null
                        pendingPresetSaveName = ""
                        pendingSaveBeforePresetSwitch = target
                    }
                ) {
                    Text(stringResource(R.string.main_preset_save_then_apply))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            pendingPresetSwitch = null
                            applyPresetNow(target)
                        }
                    ) {
                        Text(stringResource(R.string.main_preset_discard_then_apply))
                    }
                    TextButton(onClick = { pendingPresetSwitch = null }) {
                        Text(stringResource(R.string.main_preset_switch_cancel))
                    }
                }
            },
        )
    }

    pendingSaveBeforePresetSwitch?.let { target ->
        val draft = presets.firstOrNull {
            it.id == TranslationPresetCatalog.UNSAVED_DRAFT_ID
        }
        val existingPresetNames = presets
            .filterNot { it.id == TranslationPresetCatalog.UNSAVED_DRAFT_ID }
            .map { translationPresetDisplayName(it) }
        val duplicateName = translationPresetNameExists(
            pendingPresetSaveName,
            existingPresetNames,
        )
        val saveNameValid =
            normalizedTranslationPresetName(pendingPresetSaveName) != null && !duplicateName
        CatalystAlertDialog(
            onDismissRequest = {
                pendingSaveBeforePresetSwitch = null
                pendingPresetSaveName = ""
            },
            title = {
                Text(stringResource(R.string.settings_translation_preset_save_dialog_title))
            },
            text = {
                OutlinedTextField(
                    value = pendingPresetSaveName,
                    onValueChange = { pendingPresetSaveName = it },
                    label = { Text(stringResource(R.string.settings_translation_preset_name)) },
                    placeholder = {
                        Text(stringResource(R.string.settings_translation_preset_name_placeholder))
                    },
                    isError = duplicateName,
                    supportingText = if (duplicateName) {
                        {
                            Text(
                                stringResource(
                                    R.string.settings_translation_preset_name_duplicate
                                )
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveNameValid && draft != null,
                    onClick = {
                        val presetToSave = draft?.let {
                            namedTranslationPresetOrNull(
                                preset = it,
                                nameInput = pendingPresetSaveName,
                                id = newCustomPresetId(),
                            )
                        } ?: return@TextButton
                        pendingSaveBeforePresetSwitch = null
                        pendingPresetSaveName = ""
                        scope.launch {
                            if (!viewModel.saveTranslationPresetAndApply(
                                    presetToSave = presetToSave,
                                    targetId = target.id,
                                )
                            ) {
                                presetModelIssues = viewModel.presetModelIssues(presets)
                                snackbarHostState.showSnackbar(presetNotReadyMessage)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.main_preset_save_then_apply))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSaveBeforePresetSwitch = null
                        pendingPresetSaveName = ""
                    }
                ) {
                    Text(stringResource(R.string.main_preset_switch_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    TextButton(onClick = onOpenLogs) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        Text(" ${stringResource(R.string.main_logs)}", modifier = Modifier.padding(start = 4.dp))
                    }
                    TextButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Text(" ${stringResource(R.string.main_settings)}", modifier = Modifier.padding(start = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡
            StatusPresetCarousel(
                initialPageIndex = initialStatusPresetPageIndex,
                onPageChanged = onStatusPresetPageChanged,
                canDrawOverlay = canDrawOverlay,
                region = region,
                shizukuAvail = shizukuAvail,
                batteryOk = batteryOk,
                serviceRunning = serviceRunning,
                presets = presets,
                activePresetId = presetPlans?.currentPresetId.orEmpty(),
                modelIssuesByPreset = presetModelIssues,
                onPresetSelected = { preset ->
                    if (shouldConfirmUnsavedPresetSwitch(
                            currentPresetId = presetPlans?.currentPresetId.orEmpty(),
                            targetPresetId = preset.id,
                        )
                    ) {
                        pendingPresetSwitch = preset
                    } else {
                        applyPresetNow(preset)
                    }
                },
                onPresetBlocked = {
                    scope.launch { snackbarHostState.showSnackbar(presetNotReadyMessage) }
                },
                showPresetDiscoveryHint =
                    presetPageSeen == false &&
                        mainGestureGuidesEnabled,
                onPresetPageSeen = {
                    if (presetPageSeen != true) {
                        presetPageSeen = true
                        scope.launch { viewModel.markMainStatusPresetSeen() }
                    }
                },
                showPresetCarouselDiscoveryHint =
                    presetCarouselSeen == false &&
                        mainGestureGuidesEnabled,
                onPresetCarouselSeen = {
                    if (presetCarouselSeen != true) {
                        presetCarouselSeen = true
                        scope.launch { viewModel.markMainPresetCarouselSeen() }
                    }
                },
            )

            CaptureGalleryCarousel(
                initialPageIndex = initialCarouselPageIndex,
                onPageChanged = onCarouselPageChanged,
                showDiscoveryHint =
                        captureGallerySeen == false &&
                        presetPageSeen == true &&
                        presetCarouselSeen == true &&
                        mainGestureGuidesEnabled,
                onCarouselSeen = {
                    if (captureGallerySeen != true) {
                        captureGallerySeen = true
                        scope.launch { viewModel.markMainCaptureGallerySeen() }
                    }
                },
                captureMeasurementKey = listOf(
                    canDrawOverlay,
                    serviceRunning,
                    startMode,
                    shizukuAvail,
                ),
                galleryMeasurementKey = listOf(
                    canDrawOverlay,
                    featuredGalleryTaskState,
                ),
                capturePage = { pageModifier ->
                    // 主操作：截屏服务
                    ActionCard(
                        title = stringResource(R.string.main_section_capture),
                        modifier = pageModifier,
                    ) {
                if (!canDrawOverlay) {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.packageName)
                            )
                            context.startActivity(intent)
                        }
                    ) { Text(stringResource(R.string.main_action_grant_overlay_first)) }
                } else {
                    val shizukuUsable = shizukuAvail == ShizukuCapabilities.Availability.READY ||
                        shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED

                    // 大主按钮：未运行 → primary 色"启动"；运行中 → error 色"停止"
                    if (serviceRunning) {
                        Button(
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            onClick = { context.startService(CaptureService.stopIntent(context)) }
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("  ${stringResource(R.string.main_action_stop)}", modifier = Modifier.padding(start = 4.dp))
                        }
                    } else {
                        val modeLabel = if (startMode == StartMode.SHIZUKU) "Shizuku" else "MediaProjection"
                        Button(
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            onClick = {
                                when (startMode) {
                                    StartMode.MEDIA_PROJECTION ->
                                        context.startActivity(
                                            MediaProjectionRequestActivity.newIntent(context)
                                        )
                                    StartMode.SHIZUKU -> scope.launch {
                                        val ok = viewModel.ensureShizukuReady()
                                        shizukuAvail = viewModel.shizukuAvailability(context)
                                        if (ok) {
                                            val svc = Intent(context, CaptureService::class.java).apply {
                                                action = CaptureService.ACTION_START
                                                putExtra(CaptureService.EXTRA_USE_SHIZUKU, true)
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                ContextCompat.startForegroundService(context, svc)
                                            } else {
                                                context.startService(svc)
                                            }
                                        } else {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.main_snack_shizuku_unavailable)
                                            )
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(
                                "  ${stringResource(R.string.main_action_start_format, modeLabel)}",
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    // 启动方式 tabs：服务运行中禁止切换；Shizuku 不可用时该项禁用
                    Text(
                        stringResource(R.string.main_label_start_mode),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = startMode == StartMode.MEDIA_PROJECTION,
                            onClick = {
                                startMode = StartMode.MEDIA_PROJECTION
                                userOverrodeMode = true
                            },
                            enabled = !serviceRunning,
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            label = { Text("MediaProjection") }
                        )
                        SegmentedButton(
                            selected = startMode == StartMode.SHIZUKU,
                            onClick = {
                                startMode = StartMode.SHIZUKU
                                userOverrodeMode = true
                            },
                            enabled = !serviceRunning && shizukuUsable,
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            label = { Text("Shizuku") }
                        )
                    }
                    val hintRes = when {
                        startMode == StartMode.MEDIA_PROJECTION -> R.string.main_hint_media_projection
                        shizukuAvail == ShizukuCapabilities.Availability.READY -> R.string.main_hint_shizuku_ready
                        shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_hint_shizuku_not_granted
                        shizukuAvail == ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED -> R.string.main_hint_shizuku_not_paired
                        shizukuAvail == ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_hint_shizuku_not_running
                        else -> R.string.main_hint_shizuku_not_installed
                    }
                    Text(
                        stringResource(hintRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 已授权时恢复原使用说明布局；未授权时紧凑显示授权提示。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canDrawOverlay) {
                        Text(
                            stringResource(R.string.main_label_usage),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.main_label_usage),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(mainUsageTextRes(canDrawOverlay)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onOpenOnboarding) {
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.main_help),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (canDrawOverlay) {
                    Text(
                        stringResource(mainUsageTextRes(canDrawOverlay)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                    }
                },
                galleryPage = { pageModifier ->
                    ActionCard(
                        title = stringResource(R.string.gallery_main_title),
                        modifier = pageModifier,
                    ) {
                Text(
                    text = stringResource(R.string.gallery_main_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        galleryPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Text(
                        stringResource(R.string.gallery_main_import),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenGalleryTasks,
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Text(
                        stringResource(R.string.gallery_main_all_tasks),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                when (
                    mainGalleryTaskSlot(
                        canDrawOverlay = canDrawOverlay,
                        isLoaded = featuredGalleryTaskState is MainGalleryTaskLoadState.Loaded,
                        hasTask = featuredGalleryTask != null,
                    )
                ) {
                    MainGalleryTaskSlot.PLACEHOLDER -> MainGalleryTaskSummaryPlaceholder()
                    MainGalleryTaskSlot.EMPTY -> MainGalleryTaskEmptyPlaceholder()
                    MainGalleryTaskSlot.TASK -> {
                        val task = requireNotNull(featuredGalleryTask)
                        MainGalleryTaskSummary(
                            task = task,
                            onClick = { onOpenGalleryTask(task.id) },
                        )
                    }
                    MainGalleryTaskSlot.HIDDEN -> Unit
                }
                    }
                },
            )

            // 截屏区域入口暂时隐藏；按产品要求保留完整代码，便于后续恢复。
            /* BEGIN_DISABLED_SCREENSHOT_REGION
            ActionCard(title = stringResource(R.string.main_section_region)) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // serviceRunning 时走 CaptureService 的 overlay 路径——floating window 模式，
                        // 绕开 Activity 在 HyperOS 横屏 long-edge cutout 上被强制 letterbox 的 OS 级硬限制。
                        // overlay 是 TYPE_APPLICATION_OVERLAY，会直接覆盖到当前主屏上方，无需切走 Activity。
                        // service 没跑时 fallback 到老 Activity 路径（这种场景通常竖屏，letterbox 影响小）。
                        if (serviceRunning) {
                            val intent = Intent(context, CaptureService::class.java).apply {
                                action = CaptureService.ACTION_PICK_REGION
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                ContextCompat.startForegroundService(context, intent)
                            } else {
                                context.startService(intent)
                            }
                        } else {
                            context.startActivity(RegionPickerActivity.newIntent(context))
                        }
                    }
                ) { Text(stringResource(R.string.main_btn_pick_region)) }
                if (region != null) {
                    // 跟「选择截屏区域」按钮同样的 OutlinedButton 样式，视觉对等；
                    // 清除是破坏性操作，弹二次确认避免误触。
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showClearRegionDialog = true }
                    ) { Text(stringResource(R.string.main_btn_clear_region)) }
                }
            }
            END_DISABLED_SCREENSHOT_REGION */

            // 系统兼容：自启动 + 电池白名单是两件事，拆成两个按钮分别引导。
            // 电池白名单可通过 PowerManager 检测当前状态，已加入时按钮显示已开启并禁用，
            // 让用户清楚下一步该点哪个；自启动没有公开 API 可探测，按钮始终可点。
            ActionCard(title = stringResource(R.string.main_section_rom_guide)) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        RomHelper.launchFirstAvailable(context, RomHelper.autoStartIntents(context))
                    }
                ) { Text(stringResource(R.string.main_btn_open_autostart)) }
                OutlinedButton(
                    enabled = !batteryOk,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        RomHelper.launchFirstAvailable(
                            context,
                            RomHelper.batteryWhitelistIntents(context)
                        )
                    }
                ) {
                    Text(
                        stringResource(
                            if (batteryOk) R.string.main_btn_battery_already_ok
                            else R.string.main_btn_open_battery_whitelist
                        )
                    )
                }
            }

            // 关于：放在主屏底部，方便用户一眼看到版本号 / GitHub
            ActionCard(title = stringResource(R.string.settings_section_about)) {
                AboutContent(
                    onOpenLegalNotices = onOpenLegalNotices,
                    onShareApp = onShareApp,
                )
            }

            // 底部留空
            Box(Modifier.size(24.dp))
        }
    }
}

internal const val GITHUB_URL = "https://github.com/ciddwd/overlay-translator"
internal const val QQ_GROUP_NUMBER = "1059655926"
internal const val QQ_GROUP_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=%2Fs0%2FaO4mEHsgutzjUnhGIQEWLcAcGPXTefUY2YwdMkPdnHHuB%2FpLZm9hPjcrw6n5&busi_data=eyJncm91cENvZGUiOiIxMDU5NjU1OTI2IiwidG9rZW4iOiJ4b25nS0FvSFQyMko4WjJTMHhGRlIwSnppeVB2eGJCNjFua0FDTGZzNUhEWlY3VkdPcFVaOEdMams0aEY3aFBTIiwidWluIjoiNTcyMjQyOTk4In0%3D&data=j7H7DHUunIEqMXYLZxhTkx-K_LZTTs5aBJS95LT_Y50uQy37d5IiUU2y3gAPcy9CYRzRufvHuTCaSHOQsLTkTw&svctype=4&tempid=h5_group_info"
private const val SHARE_PROMPT_DELAY_MS = 1_200L

@Composable
private fun AboutContent(
    onOpenLegalNotices: () -> Unit,
    onShareApp: () -> Unit,
) {
    val context = LocalContext.current
    val updateVm: com.gameocr.app.update.UpdateViewModel = hiltViewModel()
    val updateState by updateVm.state.collectAsState()

    Text(
        text = stringResource(R.string.settings_about_tagline),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = stringResource(R.string.settings_about_version_format, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedButton(
        enabled = updateState !is com.gameocr.app.update.UpdateViewModel.State.Checking,
        onClick = { updateVm.check() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(
                if (updateState is com.gameocr.app.update.UpdateViewModel.State.Checking)
                    R.string.update_btn_checking
                else R.string.update_btn_check
            )
        )
    }

    Text(
        text = stringResource(R.string.settings_about_github_label),
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        text = GITHUB_URL,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
    OutlinedButton(
        onClick = {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.settings_about_open_github)) }

    Text(
        text = stringResource(R.string.settings_about_open_source_licenses),
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        text = stringResource(R.string.settings_about_third_party_notice_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(
        onClick = onOpenLegalNotices,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.settings_about_view_licenses))
    }

    Text(
        text = stringResource(R.string.settings_about_share_label),
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        text = stringResource(R.string.settings_about_share_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(
        onClick = onShareApp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Share, contentDescription = null)
        Text(
            stringResource(R.string.settings_about_share_action),
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    Text(
        text = stringResource(R.string.settings_about_qq_group_label),
        style = MaterialTheme.typography.labelLarge
    )
    Text(
        text = stringResource(R.string.settings_about_qq_group_name_format, QQ_GROUP_NUMBER),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(
        onClick = {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        Text(
            stringResource(R.string.settings_about_join_qq_group),
            modifier = Modifier.padding(start = 8.dp)
        )
    }

    // 注意：UpdateResultDialog 已提到 MainScreen 顶层（避免自动检测弹窗被关于卡折叠遮住），
    // 这里不再重复挂——同一 ViewModel state，顶层 dialog 也响应"检查更新"按钮触发的 check()。
}

private fun launchAppShare(
    context: Context,
    subject: String,
    text: String,
    chooserTitle: String,
) {
    runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, chooserTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun SharePromptDialog(
    onShare: () -> Unit,
    onDecline: () -> Unit,
) {
    val palette = FloatingMenuTourPalette.colors(
        nightMode = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    )
    val surfaceColor = Color(palette.surface)
    val textColor = Color(palette.text)
    val secondaryTextColor = Color(palette.secondaryText)
    val accentColor = Color(palette.accent)
    val actionTextColor = Color(palette.actionText)
    val borderColor = Color(palette.border)

    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            contentColor = textColor,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = borderColor.copy(alpha = 0.24f),
                    contentColor = accentColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.main_share_prompt_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
                Text(
                    text = stringResource(R.string.main_share_prompt_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryTextColor,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        border = BorderStroke(1.dp, borderColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = secondaryTextColor,
                        ),
                    ) {
                        Text(stringResource(R.string.main_share_prompt_decline))
                    }
                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = actionTextColor,
                        ),
                    ) {
                        Text(stringResource(R.string.main_share_prompt_confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateResultDialog(
    state: com.gameocr.app.update.UpdateViewModel.State,
    onDismiss: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onSkipVersion: (String) -> Unit = {}
) {
    when (state) {
        is com.gameocr.app.update.UpdateViewModel.State.Loaded -> {
            val info = state.info
            CatalystAlertDialog(
                modifier = Modifier.testTag(UPDATE_DIALOG_TAG),
                onDismissRequest = onDismiss,
                contentScrollable = false,
                title = {
                    Text(
                        text = stringResource(
                            if (info.hasUpdate) R.string.update_dialog_title_new
                            else R.string.update_dialog_title_uptodate
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    Column {
                        Text(
                            text = stringResource(
                                R.string.update_dialog_versions_format,
                                info.currentVersion,
                                info.latestVersion,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (info.hasUpdate && !info.changelog.isNullOrBlank()) {
                            Text(
                                text = info.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(top = 8.dp)
                                    .verticalScroll(rememberScrollState())
                                    .testTag(UPDATE_DIALOG_RELEASE_NOTES_TAG)
                            )
                        }
                    }
                },
                confirmButton = {
                    if (info.hasUpdate) {
                        Button(onClick = {
                            // 有 APK 直链就走直链让浏览器 / 系统下载器直接下；否则跳 release 页
                            onOpenRelease(info.apkUrl ?: info.releaseUrl)
                        }) { Text(stringResource(R.string.update_dialog_btn_download)) }
                    } else {
                        Button(onClick = onDismiss) {
                            Text(stringResource(R.string.update_dialog_btn_ok))
                        }
                    }
                },
                // 有更新时把「跳过此版本」「稍后」并排塞进 dismissButton 槽位（AlertDialog 只有两个 button slot）
                dismissButton = if (info.hasUpdate) {
                    {
                        Row {
                            TextButton(onClick = { onSkipVersion(info.latestVersion) }) {
                                Text(stringResource(R.string.update_dialog_btn_skip))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.update_dialog_btn_later))
                            }
                        }
                    }
                } else null
            )
        }
        is com.gameocr.app.update.UpdateViewModel.State.Failed -> {
            CatalystAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_dialog_title_failed)) },
                text = {
                    Text(stringResource(R.string.update_dialog_failed_format, state.errorMessage))
                },
                confirmButton = {
                    TextButton(onClick = {
                        onOpenRelease(com.gameocr.app.update.UpdateChecker.RELEASE_PAGE_URL)
                    }) { Text(stringResource(R.string.update_dialog_btn_open_release)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_dialog_btn_close))
                    }
                }
            )
        }
        else -> Unit
    }
}

internal const val UPDATE_DIALOG_TAG = "update_dialog"
internal const val UPDATE_DIALOG_RELEASE_NOTES_TAG = "update_dialog_release_notes"

@Composable
private fun CaptureGalleryCarousel(
    initialPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    showDiscoveryHint: Boolean,
    onCarouselSeen: () -> Unit,
    captureMeasurementKey: Any?,
    galleryMeasurementKey: Any?,
    capturePage: @Composable (Modifier) -> Unit,
    galleryPage: @Composable (Modifier) -> Unit,
) {
    val initialPage = remember { captureGalleryCarouselInitialPage(initialPageIndex) }
    val pagerState = rememberPagerState(initialPage = initialPage) { Int.MAX_VALUE }
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnCarouselSeen by rememberUpdatedState(onCarouselSeen)
    val density = LocalDensity.current
    var captureHeightPx by remember { mutableIntStateOf(0) }
    var galleryHeightPx by remember { mutableIntStateOf(0) }
    var discoveryHintPlayed by rememberSaveable { mutableStateOf(false) }
    var discoveryGuideVisible by remember { mutableStateOf(false) }
    var discoveryScrollObserved by remember(pagerState) { mutableStateOf(false) }
    var discoveryScrollStartPage by remember(pagerState) {
        mutableIntStateOf(pagerState.settledPage)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .distinctUntilChanged()
            .collect { (scrolling, settledPage) ->
                if (scrolling) {
                    if (!discoveryScrollObserved) {
                        discoveryScrollStartPage = settledPage
                    }
                    discoveryScrollObserved = true
                    discoveryGuideVisible = false
                } else {
                    currentOnPageChanged(captureGalleryCarouselPageIndex(settledPage))
                    if (
                        discoveryScrollObserved &&
                        settledPage != discoveryScrollStartPage
                    ) {
                        currentOnCarouselSeen()
                    }
                    discoveryScrollObserved = false
                }
            }
    }
    LaunchedEffect(pagerState, showDiscoveryHint) {
        discoveryGuideVisible = false
        if (!showDiscoveryHint) return@LaunchedEffect
        delay(HORIZONTAL_DISCOVERY_HINT_DELAY_MS)
        if (
            !shouldRunHorizontalDiscoveryHint(
                hintEnabled = showDiscoveryHint,
                hintAlreadyPlayed = discoveryHintPlayed,
                hostVisible = captureGalleryCarouselPageIndex(pagerState.settledPage) ==
                    CAPTURE_CAROUSEL_PAGE,
                isScrollInProgress = pagerState.isScrollInProgress,
                itemCount = CAPTURE_GALLERY_PAGE_COUNT,
            )
        ) {
            return@LaunchedEffect
        }
        discoveryHintPlayed = true
        discoveryGuideVisible = true
        try {
            delay(HORIZONTAL_DISCOVERY_HINT_VISIBLE_MS)
        } finally {
            discoveryGuideVisible = false
        }
    }
    LaunchedEffect(captureMeasurementKey) {
        captureHeightPx = 0
    }
    LaunchedEffect(galleryMeasurementKey) {
        galleryHeightPx = 0
    }
    val commonHeightPx = captureGalleryCarouselCommonHeightPx(
        captureHeightPx = captureHeightPx,
        galleryHeightPx = galleryHeightPx,
    )
    val commonHeight = commonHeightPx?.let { with(density) { it.toDp() } }
    val indicatorPage = captureGalleryCarouselPageIndex(pagerState.currentPage)

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    commonHeight?.let { Modifier.height(it) }
                        ?: Modifier.wrapContentHeight()
                ),
            beyondViewportPageCount = 1,
        ) { page ->
            val pageOffset = (
                (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
            ).coerceIn(-1f, 1f)
            val pivotX = when {
                pageOffset < 0f -> 1f
                pageOffset > 0f -> 0f
                else -> 0.5f
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = captureGalleryCarouselRotation(pageOffset)
                        cameraDistance = 16f * density.density
                        transformOrigin = TransformOrigin(pivotX, 0.5f)
                    },
            ) {
                when (captureGalleryCarouselPageIndex(page)) {
                    CAPTURE_CAROUSEL_PAGE -> capturePage(
                        Modifier
                            .onSizeChanged { size ->
                                if (commonHeightPx == null && size.height > 0) {
                                    captureHeightPx = size.height
                                }
                            }
                            .then(
                                if (commonHeightPx != null) Modifier.fillMaxHeight()
                                else Modifier
                            )
                    )
                    GALLERY_CAROUSEL_PAGE -> galleryPage(
                        Modifier
                            .onSizeChanged { size ->
                                if (commonHeightPx == null && size.height > 0) {
                                    galleryHeightPx = size.height
                                }
                            }
                            .then(
                                if (commonHeightPx != null) Modifier.fillMaxHeight()
                                else Modifier
                            )
                    )
                }
                if (discoveryGuideVisible && page == pagerState.currentPage) {
                    SwipeHorizontalGuide(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(
                                y = horizontalDiscoveryGuideYOffsetDp(
                                    HorizontalDiscoveryGuideHost.CAPTURE_SERVICE,
                                ).dp,
                            )
                            .size(width = 200.dp, height = 84.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(CAPTURE_GALLERY_PAGE_COUNT) { page ->
                val selected = page == indicatorPage
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(
                            width = if (selected) 18.dp else 6.dp,
                            height = 6.dp,
                        ),
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ) {}
            }
        }
    }
}

private const val CAPTURE_GALLERY_PAGE_COUNT = 2
private const val CAPTURE_CAROUSEL_PAGE = 0
private const val GALLERY_CAROUSEL_PAGE = 1
private const val HORIZONTAL_DISCOVERY_HINT_DELAY_MS = 1_000L
private const val HORIZONTAL_DISCOVERY_HINT_VISIBLE_MS = 3_000L

internal fun captureGalleryCarouselPageIndex(page: Int): Int =
    Math.floorMod(page, CAPTURE_GALLERY_PAGE_COUNT)

internal fun captureGalleryCarouselInitialPage(pageIndex: Int = CAPTURE_CAROUSEL_PAGE): Int {
    val anchor = Int.MAX_VALUE / 2
    val captureAnchor = anchor - Math.floorMod(anchor, CAPTURE_GALLERY_PAGE_COUNT)
    return captureAnchor + Math.floorMod(pageIndex, CAPTURE_GALLERY_PAGE_COUNT)
}

internal fun captureGalleryCarouselRotation(pageOffset: Float): Float =
    pageOffset.coerceIn(-1f, 1f) * 90f

internal fun captureGalleryCarouselCommonHeightPx(
    captureHeightPx: Int,
    galleryHeightPx: Int,
): Int? = if (captureHeightPx > 0 && galleryHeightPx > 0) {
    maxOf(captureHeightPx, galleryHeightPx)
} else {
    null
}

internal fun shouldRunHorizontalDiscoveryHint(
    hintEnabled: Boolean,
    hintAlreadyPlayed: Boolean,
    hostVisible: Boolean,
    isScrollInProgress: Boolean,
    itemCount: Int,
): Boolean =
    hintEnabled &&
        !hintAlreadyPlayed &&
        hostVisible &&
        !isScrollInProgress &&
        itemCount > 1

internal fun shouldEnableMainGestureGuides(
    floatingTourCompleted: Boolean,
    autoChecking: Boolean,
    sharePromptVisible: Boolean,
    updateDialogVisible: Boolean,
): Boolean =
    floatingTourCompleted &&
        !autoChecking &&
        !sharePromptVisible &&
        !updateDialogVisible

internal enum class HorizontalDiscoveryGuideHost {
    PRESET,
    CAPTURE_SERVICE,
}

internal fun horizontalDiscoveryGuideYOffsetDp(
    host: HorizontalDiscoveryGuideHost,
): Int = when (host) {
    HorizontalDiscoveryGuideHost.PRESET -> 0
    HorizontalDiscoveryGuideHost.CAPTURE_SERVICE -> 24
}

@Composable
private fun MainGalleryTaskSummary(
    task: GalleryTranslationTaskEntity,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        MainGalleryTaskSummaryContent(task)
    }
}

@Composable
private fun MainGalleryTaskSummaryPlaceholder() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        MainGalleryTaskSummaryContent(task = null)
    }
}

@Composable
private fun MainGalleryTaskEmptyPlaceholder() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box {
            MainGalleryTaskSummaryContent(task = null)
            Text(
                stringResource(R.string.gallery_main_no_history_task),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MainGalleryTaskSummaryContent(
    task: GalleryTranslationTaskEntity?,
) {
    val placeholder = task == null
    val active = placeholder || isMainGalleryTaskActive(task.status)
    val totalCount = task?.totalCount ?: 0
    val completedCount = task?.completedCount ?: 0
    val successCount = task?.successCount ?: 0
    val failedCount = task?.failedCount ?: 0
    val contentModifier = if (placeholder) {
        Modifier
            .graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {}
    } else {
        Modifier
    }

        Column(
            modifier = Modifier
                .padding(12.dp)
                .then(contentModifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (active) {
                            R.string.gallery_main_active_task
                        } else {
                            R.string.gallery_main_latest_task
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.gallery_task_images, totalCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                task?.let {
                    mainGalleryTaskPresetLabel(
                        storedName = it.presetName,
                        builtInStoredName = TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH_NAME,
                        builtInDisplayName = stringResource(
                            R.string.settings_translation_preset_builtin_manga
                        ),
                        unsavedDisplayName = stringResource(
                            R.string.settings_translation_preset_unsaved_name
                        ),
                    )
                } ?: stringResource(R.string.settings_translation_preset_unsaved_name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.gallery_main_progress,
                    completedCount,
                    totalCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (active) {
                LinearProgressIndicator(
                    progress = {
                        mainGalleryTaskProgress(completedCount, totalCount)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(
                        R.string.gallery_task_success_count,
                        successCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(
                        R.string.gallery_task_failed_count,
                        failedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
}

internal sealed interface MainGalleryTaskLoadState {
    data object Loading : MainGalleryTaskLoadState
    data class Loaded(
        val task: GalleryTranslationTaskEntity?,
    ) : MainGalleryTaskLoadState
}

internal enum class MainGalleryTaskSlot {
    HIDDEN,
    PLACEHOLDER,
    EMPTY,
    TASK,
}

internal fun mainGalleryTaskSlot(
    canDrawOverlay: Boolean,
    isLoaded: Boolean,
    hasTask: Boolean,
): MainGalleryTaskSlot = when {
    !canDrawOverlay -> MainGalleryTaskSlot.HIDDEN
    !isLoaded -> MainGalleryTaskSlot.PLACEHOLDER
    hasTask -> MainGalleryTaskSlot.TASK
    else -> MainGalleryTaskSlot.EMPTY
}

internal fun isMainGalleryTaskActive(status: GalleryTaskStatus): Boolean =
    status == GalleryTaskStatus.QUEUED ||
        status == GalleryTaskStatus.RUNNING ||
        status == GalleryTaskStatus.WAITING_RETRY

internal fun mainUsageTextRes(canDrawOverlay: Boolean): Int =
    if (canDrawOverlay) {
        R.string.main_usage_text
    } else {
        R.string.main_usage_overlay_permission_required
    }

internal fun mainGalleryTaskProgress(completedCount: Int, totalCount: Int): Float =
    if (totalCount <= 0) {
        0f
    } else {
        (completedCount.toFloat() / totalCount).coerceIn(0f, 1f)
    }

internal fun mainGalleryTaskPresetLabel(
    storedName: String,
    builtInStoredName: String,
    builtInDisplayName: String,
    unsavedDisplayName: String,
): String = when {
    storedName.isBlank() -> unsavedDisplayName
    storedName == builtInStoredName -> builtInDisplayName
    else -> storedName
}

@Composable
private fun StatusPresetCarousel(
    initialPageIndex: Int,
    onPageChanged: (Int) -> Unit,
    canDrawOverlay: Boolean,
    region: CaptureRegion?,
    shizukuAvail: ShizukuCapabilities.Availability,
    batteryOk: Boolean,
    serviceRunning: Boolean,
    presets: List<TranslationPreset>,
    activePresetId: String,
    modelIssuesByPreset: Map<String, List<TranslationPresetModelIssue>>?,
    onPresetSelected: (TranslationPreset) -> Unit,
    onPresetBlocked: () -> Unit,
    showPresetDiscoveryHint: Boolean,
    onPresetPageSeen: () -> Unit,
    showPresetCarouselDiscoveryHint: Boolean,
    onPresetCarouselSeen: () -> Unit,
) {
    val initialPage = remember {
        mainStatusPresetInitialPage(initialPageIndex)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { STATUS_PRESET_PAGE_COUNT },
    )
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnPresetPageSeen by rememberUpdatedState(onPresetPageSeen)
    var discoveryHintPlayed by rememberSaveable { mutableStateOf(false) }
    var discoveryGuideVisible by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settledPage ->
                currentOnPageChanged(settledPage)
                if (mainStatusPresetPageWasSeen(settledPage, pagerState.pageCount)) {
                    currentOnPresetPageSeen()
                }
            }
    }

    LaunchedEffect(pagerState, showPresetDiscoveryHint) {
        if (!showPresetDiscoveryHint) return@LaunchedEffect
        delay(STATUS_PRESET_DISCOVERY_HINT_DELAY_MS)
        if (
            !shouldRunMainStatusPresetHint(
                presetPageSeen = false,
                hintAlreadyPlayed = discoveryHintPlayed,
                settledPage = pagerState.settledPage,
                isScrollInProgress = pagerState.isScrollInProgress,
                pageCount = pagerState.pageCount,
            )
        ) {
            return@LaunchedEffect
        }

        discoveryHintPlayed = true
        discoveryGuideVisible = true
        try {
            pagerState.animateScrollToPage(
                page = STATUS_PAGE,
                pageOffsetFraction = STATUS_PRESET_DISCOVERY_HINT_OFFSET_FRACTION,
                animationSpec = tween(
                    durationMillis = STATUS_PRESET_DISCOVERY_HINT_REVEAL_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            delay(STATUS_PRESET_DISCOVERY_HINT_HOLD_MS)
            pagerState.animateScrollToPage(
                page = STATUS_PAGE,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessVeryLow,
                ),
            )
        } finally {
            discoveryGuideVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp),
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MainScreenHorizontalPadding),
            pageSpacing = 12.dp,
        ) { page ->
            when (page) {
                STATUS_PAGE -> StatusCard(
                    canDrawOverlay = canDrawOverlay,
                    region = region,
                    shizukuAvail = shizukuAvail,
                    batteryOk = batteryOk,
                    serviceRunning = serviceRunning,
                    modifier = Modifier.fillMaxSize(),
                )
                PRESET_PAGE -> PresetCarouselCard(
                    presets = presets,
                    activePresetId = activePresetId,
                    modelIssuesByPreset = modelIssuesByPreset,
                    onPresetSelected = onPresetSelected,
                    onPresetBlocked = onPresetBlocked,
                    showHorizontalDiscoveryHint =
                        showPresetCarouselDiscoveryHint &&
                            pagerState.settledPage == PRESET_PAGE,
                    onHorizontalCarouselSeen = onPresetCarouselSeen,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(STATUS_PRESET_PAGE_COUNT) { page ->
                Surface(
                    modifier = Modifier.size(
                        width = 6.dp,
                        height = if (page == pagerState.currentPage) 16.dp else 6.dp,
                    ),
                    shape = CircleShape,
                    color = if (page == pagerState.currentPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ) {}
            }
        }
        if (discoveryGuideVisible) {
            SwipeUpGuide(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 64.dp, height = 148.dp),
            )
        }
    }
}

private const val STATUS_PRESET_PAGE_COUNT = 2
private const val STATUS_PAGE = 0
private const val PRESET_PAGE = 1
private const val STATUS_PRESET_DISCOVERY_HINT_DELAY_MS = 1_000L
private const val STATUS_PRESET_DISCOVERY_HINT_REVEAL_MS = 650
private const val STATUS_PRESET_DISCOVERY_HINT_HOLD_MS = 1_500L
private const val STATUS_PRESET_DISCOVERY_HINT_OFFSET_FRACTION = 0.18f

internal fun mainStatusPresetInitialPage(pageIndex: Int): Int =
    pageIndex.coerceIn(STATUS_PAGE, PRESET_PAGE)

internal fun shouldRunMainStatusPresetHint(
    presetPageSeen: Boolean,
    hintAlreadyPlayed: Boolean,
    settledPage: Int,
    isScrollInProgress: Boolean,
    pageCount: Int,
): Boolean =
    !presetPageSeen &&
        !hintAlreadyPlayed &&
        settledPage == STATUS_PAGE &&
        !isScrollInProgress &&
        pageCount > PRESET_PAGE

internal fun mainStatusPresetPageWasSeen(settledPage: Int, pageCount: Int): Boolean =
    pageCount > PRESET_PAGE && settledPage == PRESET_PAGE

@Composable
private fun StatusCard(
    canDrawOverlay: Boolean,
    region: CaptureRegion?,
    shizukuAvail: ShizukuCapabilities.Availability,
    batteryOk: Boolean,
    serviceRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.main_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StatusRow(
                stringResource(R.string.main_status_capture_service),
                ok = serviceRunning,
                detail = stringResource(if (serviceRunning) R.string.main_status_running else R.string.main_status_idle)
            )
            StatusRow(stringResource(R.string.main_status_overlay_perm), canDrawOverlay)
            StatusRow(
                stringResource(R.string.main_status_region),
                ok = true,
                detail = region?.let {
                    stringResource(R.string.main_status_region_format, it.width, it.height, it.left, it.top)
                } ?: stringResource(R.string.main_status_region_full)
            )
            StatusRow(
                stringResource(R.string.main_status_shizuku),
                ok = shizukuAvail == ShizukuCapabilities.Availability.READY,
                detail = stringResource(
                    when (shizukuAvail) {
                        ShizukuCapabilities.Availability.READY -> R.string.main_status_shizuku_ready
                        ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_status_shizuku_not_granted
                        ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED -> R.string.main_status_shizuku_not_paired
                        ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_status_shizuku_not_running
                        ShizukuCapabilities.Availability.NOT_INSTALLED -> R.string.main_status_shizuku_not_installed
                    }
                )
            )
            StatusRow(stringResource(R.string.main_status_battery_whitelist), batteryOk)
        }
    }
}

@Composable
internal fun PresetCarouselCard(
    presets: List<TranslationPreset>,
    activePresetId: String,
    modelIssuesByPreset: Map<String, List<TranslationPresetModelIssue>>?,
    onPresetSelected: (TranslationPreset) -> Unit,
    onPresetBlocked: () -> Unit,
    showHorizontalDiscoveryHint: Boolean = false,
    onHorizontalCarouselSeen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.main_preset_title),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(122.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.main_preset_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PresetCarousel(
                    presets = presets,
                    activePresetId = activePresetId,
                    modelIssuesByPreset = modelIssuesByPreset,
                    onPresetSelected = onPresetSelected,
                    onPresetBlocked = onPresetBlocked,
                    showHorizontalDiscoveryHint = showHorizontalDiscoveryHint,
                    onHorizontalCarouselSeen = onHorizontalCarouselSeen,
                )
            }
        }
    }
}

@Composable
internal fun PresetCarousel(
    presets: List<TranslationPreset>,
    activePresetId: String,
    modelIssuesByPreset: Map<String, List<TranslationPresetModelIssue>>?,
    onPresetSelected: (TranslationPreset) -> Unit,
    onPresetBlocked: () -> Unit,
    showHorizontalDiscoveryHint: Boolean = false,
    onHorizontalCarouselSeen: () -> Unit = {},
) {
    val presetIds = remember(presets) { presets.map(TranslationPreset::id) }
    val initialIndex = remember(presetIds, activePresetId) {
        presetCarouselActiveIndex(presetIds, activePresetId)
    }
    val pageCount = presetCarouselPageCount(presets.size)
    val initialPage = remember(presetIds, initialIndex) {
        presetCarouselInitialPage(presets.size, initialIndex)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { pageCount }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val autoApplyProgress = remember(pagerState) { Animatable(0f) }
    val currentOnHorizontalCarouselSeen by rememberUpdatedState(onHorizontalCarouselSeen)
    var userScrollObserved by remember(pagerState) { mutableStateOf(false) }
    var pendingAutoApplyPage by remember(pagerState) { mutableStateOf<Int?>(null) }
    var discoveryHintPlayed by rememberSaveable { mutableStateOf(false) }
    var discoveryGuideVisible by remember { mutableStateOf(false) }
    var discoveryScrollObserved by remember(pagerState) { mutableStateOf(false) }
    var discoveryScrollStartPage by remember(pagerState) {
        mutableIntStateOf(pagerState.settledPage)
    }

    LaunchedEffect(pagerState, presetIds, activePresetId, modelIssuesByPreset) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .distinctUntilChanged()
            .collectLatest { (scrolling, settledPage) ->
                if (scrolling) {
                    if (!discoveryScrollObserved) {
                        discoveryScrollStartPage = settledPage
                    }
                    discoveryScrollObserved = true
                    discoveryGuideVisible = false
                    pendingAutoApplyPage = null
                    autoApplyProgress.snapTo(0f)
                    userScrollObserved = true
                } else {
                    if (
                        discoveryScrollObserved &&
                        settledPage != discoveryScrollStartPage
                    ) {
                        currentOnHorizontalCarouselSeen()
                    }
                    discoveryScrollObserved = false
                    if (!userScrollObserved) return@collectLatest
                    pendingAutoApplyPage = settledPage
                    try {
                        autoApplyProgress.snapTo(0f)
                        autoApplyProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = PRESET_AUTO_APPLY_SETTLE_DELAY_MS.toInt(),
                                easing = LinearEasing,
                            ),
                        )
                        userScrollObserved = false
                        presetCarouselItemIndex(settledPage, presets.size)?.let { index ->
                            val preset = presets[index]
                            val modelIssues = modelIssuesByPreset?.get(preset.id)
                            if (preset.id != activePresetId) {
                                if (presetCarouselCanApply(modelIssues)) {
                                    onPresetSelected(preset)
                                } else {
                                    onPresetBlocked()
                                }
                            }
                        }
                    } finally {
                        if (pendingAutoApplyPage == settledPage) {
                            pendingAutoApplyPage = null
                        }
                    }
                }
            }
    }

    LaunchedEffect(pagerState, showHorizontalDiscoveryHint, presets.size) {
        discoveryGuideVisible = false
        if (!showHorizontalDiscoveryHint) return@LaunchedEffect
        delay(HORIZONTAL_DISCOVERY_HINT_DELAY_MS)
        if (
            !shouldRunHorizontalDiscoveryHint(
                hintEnabled = showHorizontalDiscoveryHint,
                hintAlreadyPlayed = discoveryHintPlayed,
                hostVisible = true,
                isScrollInProgress = pagerState.isScrollInProgress,
                itemCount = presets.size,
            )
        ) {
            return@LaunchedEffect
        }
        discoveryHintPlayed = true
        discoveryGuideVisible = true
        try {
            delay(HORIZONTAL_DISCOVERY_HINT_VISIBLE_MS)
        } finally {
            discoveryGuideVisible = false
        }
    }

    LaunchedEffect(activePresetId, presetIds) {
        val targetIndex = presetIds.indexOf(activePresetId)
        if (targetIndex >= 0) {
            val targetPage = presetCarouselNearestPage(
                currentPage = pagerState.settledPage,
                itemCount = presets.size,
                targetIndex = targetIndex,
            )
            if (targetPage != pagerState.settledPage) pagerState.scrollToPage(targetPage)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 46.dp),
            pageSpacing = 12.dp,
            verticalAlignment = Alignment.CenterVertically,
        ) { page ->
            val preset = presets[requireNotNull(presetCarouselItemIndex(page, presets.size))]
            val pageOffset = (
                (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
            ).coerceIn(-1f, 1f)
            val centered = page == pagerState.settledPage
            val waitingForAutoApply = page == pendingAutoApplyPage
            val selected = presetCarouselIsApplied(
                presetId = preset.id,
                activePresetId = activePresetId,
            )
            val pivotX = when {
                pageOffset < 0f -> 1f
                pageOffset > 0f -> 0f
                else -> 0.5f
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .presetAutoApplyFlowBorder(
                        visible = waitingForAutoApply,
                        progress = autoApplyProgress.value,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    .graphicsLayer {
                        rotationY = presetCarouselOutwardRotation(pageOffset)
                        scaleX = presetCarouselScale(pageOffset)
                        scaleY = presetCarouselScale(pageOffset)
                        alpha = 1f - (abs(pageOffset) * 0.22f)
                        cameraDistance = 14f * density
                        transformOrigin = TransformOrigin(pivotX, 0.5f)
                    }
                    .clickable(enabled = !centered) {
                        if (!centered) {
                            scope.launch { pagerState.animateScrollToPage(page) }
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = translationPresetDisplayName(preset),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = mainTranslationPresetNameMaxLines(preset.id),
                        overflow = TextOverflow.Ellipsis,
                    )
                    mainPresetDetailLines(translationPresetSummary(preset)).forEach { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (discoveryGuideVisible) {
            SwipeHorizontalGuide(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        y = horizontalDiscoveryGuideYOffsetDp(
                            HorizontalDiscoveryGuideHost.PRESET,
                        ).dp,
                    )
                    .size(width = 200.dp, height = 84.dp),
            )
        }
    }
}

internal data class PresetCarouselPlans(
    val presets: List<TranslationPreset>,
    val currentPresetId: String,
)

internal fun mainTranslationPresetNameMaxLines(presetId: String): Int =
    if (TranslationPresetCatalog.isBuiltIn(presetId)) 1 else 2

internal fun presetCarouselPlans(
    settings: AppSettings,
    unsavedPresetName: String,
): PresetCarouselPlans {
    val savedPresets = TranslationPresetCatalog.all(settings.translationPresets)
        .filterNot { it.id == TranslationPresetCatalog.UNSAVED_DRAFT_ID }
    val settingsHash = TranslationPresetCatalog.hashForSettings(settings)
    val matchingPreset = savedPresets.firstOrNull {
        it.id == settings.activeTranslationPresetId &&
            TranslationPresetCatalog.matchesHash(it, settingsHash)
    } ?: savedPresets.firstOrNull {
        TranslationPresetCatalog.matchesHash(it, settingsHash)
    }
    if (matchingPreset != null) {
        return PresetCarouselPlans(
            presets = savedPresets,
            currentPresetId = matchingPreset.id,
        )
    }

    val draft = TranslationPresetCatalog.fromSettings(
        id = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
        name = unsavedPresetName,
        shortName = unsavedPresetName.take(8),
        settings = settings,
    )
    return PresetCarouselPlans(
        presets = listOf(draft) + savedPresets,
        currentPresetId = draft.id,
    )
}

internal fun presetCarouselPageCount(itemCount: Int): Int = when {
    itemCount <= 0 -> 0
    itemCount == 1 -> 1
    else -> Int.MAX_VALUE
}

internal fun presetCarouselActiveIndex(presetIds: List<String>, activePresetId: String): Int =
    presetIds.indexOf(activePresetId).takeIf { it >= 0 } ?: 0

internal fun presetCarouselItemIndex(page: Int, itemCount: Int): Int? =
    itemCount.takeIf { it > 0 }?.let { Math.floorMod(page, it) }

internal fun presetCarouselCanApply(modelIssues: List<TranslationPresetModelIssue>?): Boolean =
    modelIssues?.let(::translationPresetCanApply) == true

internal fun presetCarouselIsApplied(
    presetId: String,
    activePresetId: String,
): Boolean = presetId == activePresetId

internal fun mainPresetDetailLines(summary: String): List<String> =
    summary.lineSequence().take(MAIN_PRESET_DETAIL_COUNT).toList()

private const val MAIN_PRESET_DETAIL_COUNT = 4
private const val PRESET_AUTO_APPLY_SETTLE_DELAY_MS = 600L

internal data class PresetFlowSegment(
    val startDistance: Float,
    val stopDistance: Float,
)

internal fun presetFlowSegments(
    progress: Float,
    pathLength: Float,
): List<PresetFlowSegment> {
    if (pathLength <= 0f) return emptyList()
    val stop = progress.coerceIn(0f, 1f) * pathLength
    return if (stop <= 0f) {
        emptyList()
    } else {
        listOf(PresetFlowSegment(0f, stop))
    }
}

private fun Modifier.presetAutoApplyFlowBorder(
    visible: Boolean,
    progress: Float,
    color: Color,
): Modifier = if (!visible) {
    this
} else {
    drawWithContent {
        drawContent()
        val strokeWidth = 1.dp.toPx()
        val outset = strokeWidth / 2f
        val radius = 12.dp.toPx() + outset
        val borderPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = -outset,
                    top = -outset,
                    right = size.width + outset,
                    bottom = size.height + outset,
                    cornerRadius = CornerRadius(radius, radius),
                )
            )
        }
        val pathMeasure = PathMeasure().apply {
            setPath(borderPath, forceClosed = true)
        }
        presetFlowSegments(progress, pathMeasure.length).forEach { segment ->
            val segmentPath = Path()
            pathMeasure.getSegment(
                startDistance = segment.startDistance,
                stopDistance = segment.stopDistance,
                destination = segmentPath,
                startWithMoveTo = true,
            )
            drawPath(
                path = segmentPath,
                color = color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

internal fun shouldConfirmUnsavedPresetSwitch(
    currentPresetId: String,
    targetPresetId: String,
): Boolean =
    currentPresetId == TranslationPresetCatalog.UNSAVED_DRAFT_ID &&
        targetPresetId != currentPresetId

internal fun presetCarouselInitialPage(itemCount: Int, activeIndex: Int): Int {
    if (itemCount <= 1) return 0
    val anchor = Int.MAX_VALUE / 2
    val normalizedIndex = Math.floorMod(activeIndex, itemCount)
    return anchor - Math.floorMod(anchor, itemCount) + normalizedIndex
}

internal fun presetCarouselNearestPage(
    currentPage: Int,
    itemCount: Int,
    targetIndex: Int,
): Int {
    if (itemCount <= 1) return 0
    val currentIndex = Math.floorMod(currentPage, itemCount)
    val normalizedTarget = Math.floorMod(targetIndex, itemCount)
    val forward = Math.floorMod(normalizedTarget - currentIndex, itemCount)
    val backward = forward - itemCount
    val delta = if (abs(backward) < abs(forward)) backward else forward
    return (currentPage.toLong() + delta)
        .coerceIn(0L, (Int.MAX_VALUE - 1).toLong())
        .toInt()
}

internal fun presetCarouselOutwardRotation(pageOffset: Float, maxRotation: Float = 18f): Float =
    pageOffset.coerceIn(-1f, 1f) * maxRotation

internal fun presetCarouselScale(pageOffset: Float): Float =
    1f - abs(pageOffset.coerceIn(-1f, 1f)) * 0.08f

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(8.dp)
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        )
        Text(
            text = detail ?: stringResource(if (ok) R.string.main_status_enabled else R.string.main_status_disabled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .padding(horizontal = MainScreenHorizontalPadding)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

/** 用户在主屏选择的截屏服务启动方式。仅 App 进程内记忆，不持久化（用户每次启动后默认 MediaProjection）。 */
/**
 * 自动检查更新时的全屏 Loading 遮罩：半透明 scrim + 中央 spinner + 「检查更新…」文案。
 * - `clickable` 但 indication=null + 空 onClick 用来吞掉点击事件，防止用户在遮罩期间瞎点底下控件。
 * - 不挡 [AlertDialog]：Dialog 是独立 Window 层，会盖在这个遮罩之上。
 */
@Composable
private fun AutoUpdateCheckOverlay() {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = source, indication = null) { /* 吞点击 */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = stringResource(R.string.update_auto_checking),
                color = Color.White
            )
        }
    }
}

private val MainScreenHorizontalPadding = 16.dp

private enum class StartMode { MEDIA_PROJECTION, SHIZUKU }

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: SettingsRepository,
    galleryTranslationRepository: GalleryTranslationRepository,
    private val shizukuManager: ShizukuManager,
    private val shizukuCapabilities: ShizukuCapabilities,
    private val llamaEngineHolder: LlamaEngineHolder,
    private val paddleModelInstaller: PaddleModelInstaller,
    private val mangaOcrModelInstaller: MangaOcrModelInstaller,
    private val orientationModelInstaller: OrientationModelInstaller,
) : ViewModel() {
    private var sharePromptEntryRecorded = false
    val settings = repo.settings
    val featuredGalleryTask = galleryTranslationRepository.observeFeaturedTask()

    suspend fun currentRegion(): CaptureRegion? = repo.get().captureRegion
    suspend fun clearRegion() {
        repo.update { it.copy(captureRegion = null) }
    }
    suspend fun recordMainScreenEntryForSharePrompt(): Boolean {
        if (sharePromptEntryRecorded) return false
        sharePromptEntryRecorded = true
        return repo.recordMainScreenEntryForSharePrompt()
    }
    suspend fun markSharePromptShown() {
        repo.markSharePromptShown()
    }
    suspend fun hasSeenMainStatusPreset(): Boolean = repo.hasSeenMainStatusPreset()
    suspend fun markMainStatusPresetSeen() {
        repo.markMainStatusPresetSeen()
    }
    suspend fun hasSeenMainPresetCarousel(): Boolean = repo.hasSeenMainPresetCarousel()
    suspend fun markMainPresetCarouselSeen() {
        repo.markMainPresetCarouselSeen()
    }
    suspend fun hasSeenMainCaptureGallery(): Boolean = repo.hasSeenMainCaptureGallery()
    suspend fun markMainCaptureGallerySeen() {
        repo.markMainCaptureGallerySeen()
    }
    fun shizukuAvailability(context: android.content.Context): ShizukuCapabilities.Availability =
        shizukuCapabilities.availability(context)
    suspend fun ensureShizukuReady(): Boolean = shizukuManager.ensureReady()
    internal suspend fun presetModelIssues(
        presets: List<TranslationPreset>,
    ): Map<String, List<TranslationPresetModelIssue>> = withContext(Dispatchers.IO) {
        presets.associate { preset -> preset.id to modelIssuesFor(preset) }
    }
    suspend fun applyTranslationPreset(id: String): Boolean {
        val preset = TranslationPresetCatalog.find(repo.get().translationPresets, id)
            ?: return false
        val canApply = withContext(Dispatchers.IO) {
            translationPresetCanApply(modelIssuesFor(preset))
        }
        if (!canApply) return false

        var applied = false
        repo.update { current ->
            val latestPreset = TranslationPresetCatalog.find(current.translationPresets, id)
                ?: return@update current
            applied = true
            latestPreset.applyTo(current).copy(activeTranslationPresetId = latestPreset.id)
        }
        return applied
    }
    suspend fun saveTranslationPresetAndApply(
        presetToSave: TranslationPreset,
        targetId: String,
    ): Boolean {
        val target = TranslationPresetCatalog.find(repo.get().translationPresets, targetId)
            ?: return false
        val canApply = withContext(Dispatchers.IO) {
            translationPresetCanApply(modelIssuesFor(target))
        }
        if (!canApply) return false

        var applied = false
        repo.update { current ->
            val latestTarget = TranslationPresetCatalog.find(
                current.translationPresets,
                targetId,
            ) ?: return@update current
            val withSavedPreset = current.copy(
                translationPresets = TranslationPresetCatalog.upsertCustom(
                    current.translationPresets,
                    presetToSave,
                ),
            )
            applied = true
            latestTarget.applyTo(withSavedPreset).copy(
                activeTranslationPresetId = latestTarget.id,
            )
        }
        return applied
    }
    private fun modelIssuesFor(preset: TranslationPreset): List<TranslationPresetModelIssue> =
        translationPresetModelIssues(
            preset = preset,
            localLlmDeviceCapable = llamaEngineHolder.isDeviceCapable(),
            llmModelReady = llamaEngineHolder::isModelInstalled,
            paddleModelReady = { version ->
                paddleModelInstaller.checkInstalled(version) != null
            },
            mangaOcrModelReady = mangaOcrModelInstaller.checkInstalled() != null,
            orientationModelReady = orientationModelInstaller.checkFullyInstalled() != null,
        )
    /** 透传 Shizuku binder 状态 flow——UI collect 后状态变化立即重算 Availability。 */
    val shizukuBinderAlive: kotlinx.coroutines.flow.StateFlow<Boolean> = shizukuManager.binderAlive
    /** Shizuku 是否拿到了 shell 特权（已配对）。binder 通但未配对时为 false。 */
    val shizukuShellPrivilegeOk: kotlinx.coroutines.flow.StateFlow<Boolean> = shizukuManager.shellPrivilegeOk
}
