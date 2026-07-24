package com.gameocr.app.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.ui.LanguagePicker
import kotlinx.coroutines.launch

private sealed interface MlKitDownloadState {
    data object Checking : MlKitDownloadState
    data class Missing(val languages: Set<String>) : MlKitDownloadState
    data object Downloading : MlKitDownloadState
    data object Ready : MlKitDownloadState
    data object Unsupported : MlKitDownloadState
    data class Error(val detail: String) : MlKitDownloadState
}

private sealed interface MangaOfflineDownloadState {
    data object Checking : MangaOfflineDownloadState
    data class Ready(val readiness: MangaOfflineModelReadiness) : MangaOfflineDownloadState
    data class Downloading(
        val readiness: MangaOfflineModelReadiness,
        val status: String,
    ) : MangaOfflineDownloadState
    data class Error(
        val readiness: MangaOfflineModelReadiness,
        val detail: String,
    ) : MangaOfflineDownloadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    firstRun: Boolean,
    onFinished: () -> Unit,
    onSkipped: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<OnboardingDraft?>(null) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var showSkipConfirmation by rememberSaveable { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<MlKitDownloadState>(MlKitDownloadState.Checking) }
    var mangaDownloadState by remember {
        mutableStateOf<MangaOfflineDownloadState>(MangaOfflineDownloadState.Checking)
    }

    LaunchedEffect(firstRun) {
        draft = viewModel.loadDraft(firstRun)
    }

    val currentDraft = draft
    if (currentDraft == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val steps = OnboardingPolicy.stepsFor(currentDraft)
    if (stepIndex > steps.lastIndex) stepIndex = steps.lastIndex
    val currentStep = steps[stepIndex]

    LaunchedEffect(currentStep, currentDraft.sourceLang, currentDraft.targetLang) {
        if (currentStep != OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD) return@LaunchedEffect
        if (!OnboardingPolicy.isMlKitPairSupported(
                currentDraft.sourceLang,
                currentDraft.targetLang,
            )
        ) {
            downloadState = MlKitDownloadState.Unsupported
            return@LaunchedEffect
        }
        downloadState = MlKitDownloadState.Checking
        downloadState = runCatching {
            val missing = viewModel.missingMlKitLanguageModels(
                currentDraft.sourceLang,
                currentDraft.targetLang,
            )
            if (missing.isEmpty()) MlKitDownloadState.Ready
            else MlKitDownloadState.Missing(missing)
        }.getOrElse { MlKitDownloadState.Error(it.message ?: it.javaClass.simpleName) }
    }

    LaunchedEffect(currentStep) {
        if (currentStep == OnboardingStep.MANGA_OFFLINE_DOWNLOAD) {
            mangaDownloadState = MangaOfflineDownloadState.Ready(
                viewModel.mangaOfflineModelReadiness()
            )
        }
    }

    fun downloadMangaOfflineModels() {
        val readiness = viewModel.mangaOfflineModelReadiness()
        mangaDownloadState = MangaOfflineDownloadState.Downloading(readiness, "")
        scope.launch {
            runCatching {
                viewModel.downloadMissingMangaOfflineModels { status ->
                    mangaDownloadState =
                        MangaOfflineDownloadState.Downloading(readiness, status)
                }
                viewModel.mangaOfflineModelReadiness()
            }.onSuccess {
                mangaDownloadState = MangaOfflineDownloadState.Ready(it)
            }.onFailure {
                mangaDownloadState = MangaOfflineDownloadState.Error(
                    readiness = viewModel.mangaOfflineModelReadiness(),
                    detail = it.message ?: it.javaClass.simpleName,
                )
            }
        }
    }

    fun goBack() {
        if (stepIndex > 0) stepIndex--
    }
    BackHandler(enabled = stepIndex > 0, onBack = ::goBack)

    if (showSkipConfirmation) {
        AlertDialog(
            onDismissRequest = { showSkipConfirmation = false },
            title = { Text(stringResource(R.string.onboarding_skip_confirm_title)) },
            text = { Text(stringResource(R.string.onboarding_skip_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        showSkipConfirmation = false
                        onSkipped()
                    },
                ) {
                    Text(stringResource(R.string.onboarding_skip_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipConfirmation = false }) {
                    Text(stringResource(R.string.onboarding_skip_continue_action))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                navigationIcon = {
                    if (stepIndex > 0) {
                        IconButton(onClick = ::goBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showSkipConfirmation = true },
                        enabled = !saving,
                    ) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val expanded = maxWidth >= 760.dp
            if (expanded) {
                Row(Modifier.fillMaxSize()) {
                    OnboardingProgressRail(
                        current = stepIndex,
                        total = steps.size,
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight(),
                    )
                    OnboardingPageSurface(
                        modifier = Modifier.weight(1f),
                        step = currentStep,
                        draft = currentDraft,
                        downloadState = downloadState,
                        mangaDownloadState = mangaDownloadState,
                        saving = saving,
                        onDraftChange = { draft = it },
                        onDownload = {
                            downloadState = MlKitDownloadState.Downloading
                            scope.launch {
                                downloadState = runCatching {
                                    viewModel.downloadMlKitLanguagePair(
                                        currentDraft.sourceLang,
                                        currentDraft.targetLang,
                                    )
                                    MlKitDownloadState.Ready
                                }.getOrElse {
                                    MlKitDownloadState.Error(
                                        it.message ?: it.javaClass.simpleName
                                    )
                                }
                            }
                        },
                        onDownloadMangaModels = ::downloadMangaOfflineModels,
                        onNext = {
                            if (currentStep == OnboardingStep.SUMMARY) {
                                saving = true
                                scope.launch {
                                    runCatching { viewModel.save(currentDraft) }
                                        .onSuccess { onFinished() }
                                        .onFailure { saving = false }
                                }
                            } else {
                                stepIndex++
                            }
                        },
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    OnboardingProgressBar(stepIndex, steps.size)
                    OnboardingPageSurface(
                        modifier = Modifier.weight(1f),
                        step = currentStep,
                        draft = currentDraft,
                        downloadState = downloadState,
                        mangaDownloadState = mangaDownloadState,
                        saving = saving,
                        onDraftChange = { draft = it },
                        onDownload = {
                            downloadState = MlKitDownloadState.Downloading
                            scope.launch {
                                downloadState = runCatching {
                                    viewModel.downloadMlKitLanguagePair(
                                        currentDraft.sourceLang,
                                        currentDraft.targetLang,
                                    )
                                    MlKitDownloadState.Ready
                                }.getOrElse {
                                    MlKitDownloadState.Error(
                                        it.message ?: it.javaClass.simpleName
                                    )
                                }
                            }
                        },
                        onDownloadMangaModels = ::downloadMangaOfflineModels,
                        onNext = {
                            if (currentStep == OnboardingStep.SUMMARY) {
                                saving = true
                                scope.launch {
                                    runCatching { viewModel.save(currentDraft) }
                                        .onSuccess { onFinished() }
                                        .onFailure { saving = false }
                                }
                            } else {
                                stepIndex++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingProgressRail(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.onboarding_rail_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.onboarding_rail_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OnboardingProgressBar(current, total)
        }
    }
}

@Composable
private fun OnboardingProgressBar(current: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_progress, current + 1, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (current + 1).toFloat() / total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal data class PersistentScrollbarGeometry(
    val thumbTop: Float,
    val thumbHeight: Float,
)

internal fun persistentScrollbarGeometry(
    scrollValue: Int,
    scrollMaxValue: Int,
    viewportSize: Int,
    trackHeight: Float,
    minimumThumbHeight: Float,
): PersistentScrollbarGeometry? {
    if (scrollMaxValue <= 0 || viewportSize <= 0 || trackHeight <= 0f) return null

    val contentHeight = viewportSize.toFloat() + scrollMaxValue
    val thumbHeight = (trackHeight * viewportSize / contentHeight)
        .coerceAtLeast(minimumThumbHeight.coerceIn(0f, trackHeight))
        .coerceAtMost(trackHeight)
    val thumbTop = (trackHeight - thumbHeight) *
        (scrollValue.toFloat() / scrollMaxValue).coerceIn(0f, 1f)
    return PersistentScrollbarGeometry(thumbTop = thumbTop, thumbHeight = thumbHeight)
}

private fun Modifier.persistentVerticalScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    trackColor: Color,
    thumbColor: Color,
): Modifier = drawWithContent {
    drawContent()
    val verticalInset = 4.dp.toPx()
    val trackHeight = (size.height - verticalInset * 2).coerceAtLeast(0f)
    val geometry = persistentScrollbarGeometry(
        scrollValue = scrollState.value,
        scrollMaxValue = scrollState.maxValue,
        viewportSize = scrollState.viewportSize,
        trackHeight = trackHeight,
        minimumThumbHeight = 40.dp.toPx(),
    ) ?: return@drawWithContent

    val barWidth = 6.dp.toPx().coerceAtMost(size.width)
    val rightInset = 2.dp.toPx()
    val left = size.width - rightInset - barWidth
    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
    drawRoundRect(
        color = trackColor,
        topLeft = Offset(left, verticalInset),
        size = Size(barWidth, trackHeight),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = thumbColor,
        topLeft = Offset(left, verticalInset + geometry.thumbTop),
        size = Size(barWidth, geometry.thumbHeight),
        cornerRadius = radius,
    )
}

@Composable
private fun OnboardingPageSurface(
    step: OnboardingStep,
    draft: OnboardingDraft,
    downloadState: MlKitDownloadState,
    mangaDownloadState: MangaOfflineDownloadState,
    saving: Boolean,
    onDraftChange: (OnboardingDraft) -> Unit,
    onDownload: () -> Unit,
    onDownloadMangaModels: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageScrollState = rememberScrollState()
    val scrollbarTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val scrollbarThumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
    LaunchedEffect(step) {
        pageScrollState.scrollTo(0)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp)
                .persistentVerticalScrollbar(
                    scrollState = pageScrollState,
                    trackColor = scrollbarTrackColor,
                    thumbColor = scrollbarThumbColor,
                )
                .verticalScroll(pageScrollState)
                .padding(
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomePage()
                        OnboardingStep.SOURCE_LANGUAGE -> SourceLanguagePage(
                            draft,
                            onDraftChange,
                        )
                        OnboardingStep.TARGET_LANGUAGE -> TargetLanguagePage(
                            draft,
                            onDraftChange,
                        )
                        OnboardingStep.DISPLAY_MODE -> DisplayModePage(
                            draft,
                            onDraftChange,
                        )
                        OnboardingStep.USAGE -> UsagePage(draft, onDraftChange)
                        OnboardingStep.MANGA_DIRECTION -> MangaDirectionPage(
                            draft,
                            onDraftChange,
                        )
                        OnboardingStep.TRANSLATION_METHOD -> TranslationMethodPage(
                            draft,
                            onDraftChange,
                        )
                        OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD -> MlKitDownloadPage(
                            draft,
                            downloadState,
                            onDownload,
                        )
                        OnboardingStep.MANGA_OFFLINE_DOWNLOAD -> MangaOfflineDownloadPage(
                            mangaDownloadState,
                            onDownloadMangaModels,
                        )
                        OnboardingStep.CLOUD_CONFIG -> CloudConfigPage(
                            draft,
                            onDraftChange,
                        )
                        OnboardingStep.TTS -> TtsPage(draft, onDraftChange)
                        OnboardingStep.SUMMARY -> SummaryPage(draft)
                    }
                    HorizontalDivider()
                    Button(
                        onClick = onNext,
                        enabled = canContinue(step, draft, downloadState) && !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                stringResource(
                                    when (step) {
                                        OnboardingStep.WELCOME ->
                                            R.string.onboarding_welcome_action
                                        OnboardingStep.SUMMARY ->
                                            R.string.onboarding_finish
                                        else ->
                                            R.string.onboarding_next
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_open_source_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.onboarding_open_source_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.onboarding_open_source_service_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun SourceLanguagePage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.Default.Language,
        title = stringResource(R.string.onboarding_source_title),
        body = stringResource(R.string.onboarding_source_body),
    )
    LanguagePicker(
        label = stringResource(R.string.onboarding_source_label),
        currentCode = draft.sourceLang,
        allowAuto = false,
        disabledLanguageCodes = setOf(draft.targetLang),
        disabledStatusLabel = stringResource(R.string.lang_picker_already_target),
        onSelect = { onDraftChange(draft.copy(sourceLang = it)) },
    )
}

@Composable
private fun TargetLanguagePage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.Default.Language,
        title = stringResource(R.string.onboarding_target_title),
        body = stringResource(R.string.onboarding_target_body),
    )
    LanguagePicker(
        label = stringResource(R.string.onboarding_target_label),
        currentCode = draft.targetLang,
        allowAuto = false,
        disabledLanguageCodes = setOf(draft.sourceLang),
        disabledStatusLabel = stringResource(R.string.lang_picker_already_source),
        onSelect = { onDraftChange(draft.copy(targetLang = it)) },
    )
    if (draft.sourceLang == draft.targetLang) {
        Text(
            stringResource(R.string.onboarding_language_same_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DisplayModePage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.Default.ViewInAr,
        title = stringResource(R.string.onboarding_display_title),
        body = stringResource(R.string.onboarding_display_body),
    )
    ChoiceCard(
        selected = draft.displayMode == OnboardingDisplayMode.ADAPTIVE_OVERLAY,
        title = stringResource(R.string.onboarding_display_adaptive),
        description = stringResource(R.string.onboarding_display_adaptive_desc),
        onClick = {
            onDraftChange(draft.copy(displayMode = OnboardingDisplayMode.ADAPTIVE_OVERLAY))
        },
    )
    ChoiceCard(
        selected = draft.displayMode == OnboardingDisplayMode.BELOW_SOURCE,
        title = stringResource(R.string.onboarding_display_below),
        description = stringResource(R.string.onboarding_display_below_desc),
        onClick = {
            onDraftChange(draft.copy(displayMode = OnboardingDisplayMode.BELOW_SOURCE))
        },
    )
    ChoiceCard(
        selected = draft.displayMode == OnboardingDisplayMode.FLOATING_WINDOW,
        title = stringResource(R.string.onboarding_display_floating),
        description = stringResource(R.string.onboarding_display_floating_desc),
        onClick = {
            onDraftChange(draft.copy(displayMode = OnboardingDisplayMode.FLOATING_WINDOW))
        },
    )
}

@Composable
private fun UsagePage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        title = stringResource(R.string.onboarding_usage_title),
        body = stringResource(R.string.onboarding_usage_body),
    )
    ChoiceCard(
        selected = draft.usage == OnboardingUsage.DAILY,
        title = stringResource(R.string.onboarding_usage_daily),
        description = stringResource(R.string.onboarding_usage_daily_desc),
        onClick = { onDraftChange(draft.copy(usage = OnboardingUsage.DAILY)) },
    )
    ChoiceCard(
        selected = draft.usage == OnboardingUsage.MANGA,
        title = stringResource(R.string.onboarding_usage_manga),
        description = stringResource(R.string.onboarding_usage_manga_desc),
        onClick = { onDraftChange(draft.copy(usage = OnboardingUsage.MANGA)) },
    )
    if (draft.usage == OnboardingUsage.MANGA) {
        Text(
            stringResource(R.string.onboarding_usage_manga_applied),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MangaDirectionPage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        title = stringResource(R.string.onboarding_manga_direction_title),
        body = stringResource(R.string.onboarding_manga_direction_body),
    )
    ChoiceCard(
        selected = draft.mangaDirection ==
            OnboardingMangaDirection.FOLLOW_RECOGNITION,
        title = stringResource(R.string.onboarding_direction_auto),
        description = stringResource(R.string.onboarding_direction_auto_desc),
        onClick = {
            onDraftChange(
                draft.copy(
                    mangaDirection = OnboardingMangaDirection.FOLLOW_RECOGNITION
                )
            )
        },
    )
    ChoiceCard(
        selected = draft.mangaDirection ==
            OnboardingMangaDirection.HORIZONTAL_LEFT_TO_RIGHT,
        title = stringResource(R.string.onboarding_direction_horizontal),
        description = stringResource(R.string.onboarding_direction_horizontal_desc),
        onClick = {
            onDraftChange(
                draft.copy(
                    mangaDirection =
                        OnboardingMangaDirection.HORIZONTAL_LEFT_TO_RIGHT
                )
            )
        },
    )
    ChoiceCard(
        selected = draft.mangaDirection ==
            OnboardingMangaDirection.VERTICAL_RIGHT_TO_LEFT,
        title = stringResource(R.string.onboarding_direction_vertical),
        description = stringResource(R.string.onboarding_direction_vertical_desc),
        onClick = {
            onDraftChange(
                draft.copy(
                    mangaDirection = OnboardingMangaDirection.VERTICAL_RIGHT_TO_LEFT
                )
            )
        },
    )
}

@Composable
private fun TranslationMethodPage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.Default.PhoneAndroid,
        title = stringResource(R.string.onboarding_method_title),
        body = stringResource(R.string.onboarding_method_body),
    )
    ChoiceCard(
        selected = draft.translationMethod == OnboardingTranslationMethod.OFFLINE,
        title = stringResource(R.string.onboarding_method_offline),
        description = stringResource(
            if (draft.usage == OnboardingUsage.MANGA) {
                R.string.onboarding_method_offline_manga_desc
            } else {
                R.string.onboarding_method_offline_desc
            }
        ),
        onClick = {
            onDraftChange(
                draft.copy(translationMethod = OnboardingTranslationMethod.OFFLINE)
            )
        },
    )
    ChoiceCard(
        selected = draft.translationMethod == OnboardingTranslationMethod.CLOUD_LLM,
        title = stringResource(R.string.onboarding_method_cloud),
        description = stringResource(R.string.onboarding_method_cloud_desc),
        onClick = {
            onDraftChange(
                draft.copy(translationMethod = OnboardingTranslationMethod.CLOUD_LLM)
            )
        },
    )
    if (
        draft.translationMethod == OnboardingTranslationMethod.OFFLINE &&
        (
            draft.usage == OnboardingUsage.MANGA &&
                !OnboardingPolicy.isSakuraPairSupported(
                    draft.sourceLang,
                    draft.targetLang,
                ) ||
                draft.usage == OnboardingUsage.DAILY &&
                !OnboardingPolicy.isMlKitPairSupported(
                    draft.sourceLang,
                    draft.targetLang,
                )
            )
    ) {
        Text(
            stringResource(
                if (draft.usage == OnboardingUsage.MANGA) {
                    R.string.onboarding_manga_offline_pair_unsupported
                } else {
                    R.string.onboarding_offline_pair_unsupported
                }
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MlKitDownloadPage(
    draft: OnboardingDraft,
    state: MlKitDownloadState,
    onDownload: () -> Unit,
) {
    PageHeading(
        icon = Icons.Default.Download,
        title = stringResource(R.string.onboarding_mlkit_title),
        body = stringResource(
            R.string.onboarding_mlkit_body,
            Languages.nameOf(androidx.compose.ui.platform.LocalContext.current, draft.sourceLang),
            Languages.nameOf(androidx.compose.ui.platform.LocalContext.current, draft.targetLang),
        ),
    )
    when (state) {
        MlKitDownloadState.Checking -> StatusRow(
            loading = true,
            text = stringResource(R.string.onboarding_mlkit_checking),
        )
        is MlKitDownloadState.Missing -> {
            Text(
                stringResource(
                    R.string.onboarding_mlkit_missing,
                    state.languages.sorted().joinToString(", "),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text(
                    stringResource(R.string.onboarding_mlkit_download),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        MlKitDownloadState.Downloading -> StatusRow(
            loading = true,
            text = stringResource(R.string.onboarding_mlkit_downloading),
        )
        MlKitDownloadState.Ready -> StatusRow(
            loading = false,
            text = stringResource(R.string.onboarding_mlkit_ready),
        )
        MlKitDownloadState.Unsupported -> Text(
            stringResource(R.string.onboarding_mlkit_unsupported),
            color = MaterialTheme.colorScheme.error,
        )
        is MlKitDownloadState.Error -> {
            Text(
                stringResource(R.string.onboarding_mlkit_error, state.detail),
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_mlkit_retry))
            }
        }
    }
}

@Composable
private fun MangaOfflineDownloadPage(
    state: MangaOfflineDownloadState,
    onDownload: () -> Unit,
) {
    PageHeading(
        icon = Icons.Default.Download,
        title = stringResource(R.string.onboarding_manga_offline_title),
        body = stringResource(R.string.onboarding_manga_offline_body),
    )
    when (state) {
        MangaOfflineDownloadState.Checking -> StatusRow(
            loading = true,
            text = stringResource(R.string.onboarding_manga_offline_checking),
        )
        else -> {
            val readiness = when (state) {
                is MangaOfflineDownloadState.Ready -> state.readiness
                is MangaOfflineDownloadState.Downloading -> state.readiness
                is MangaOfflineDownloadState.Error -> state.readiness
                MangaOfflineDownloadState.Checking -> return
            }
            ModelRecommendationRow(
                title = stringResource(R.string.onboarding_manga_offline_ocr),
                detail = stringResource(R.string.onboarding_manga_offline_ocr_desc),
                ready = readiness.mangaOcrReady,
            )
            ModelRecommendationRow(
                title = stringResource(R.string.onboarding_manga_offline_sakura),
                detail = stringResource(R.string.onboarding_manga_offline_sakura_desc),
                ready = readiness.sakuraReady,
            )
            when (state) {
                is MangaOfflineDownloadState.Downloading -> StatusRow(
                    loading = true,
                    text = state.status.ifBlank {
                        stringResource(R.string.onboarding_manga_offline_downloading)
                    },
                )
                is MangaOfflineDownloadState.Error -> Text(
                    stringResource(
                        R.string.onboarding_manga_offline_error,
                        state.detail,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                is MangaOfflineDownloadState.Ready -> if (readiness.allReady) {
                    StatusRow(
                        loading = false,
                        text = stringResource(R.string.onboarding_manga_offline_ready),
                    )
                }
                MangaOfflineDownloadState.Checking -> Unit
            }
            if (!readiness.allReady) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = state !is MangaOfflineDownloadState.Downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text(
                        stringResource(R.string.onboarding_manga_offline_download),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    stringResource(R.string.onboarding_manga_offline_optional),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CloudConfigPage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.Default.Cloud,
        title = stringResource(R.string.onboarding_cloud_title),
        body = stringResource(R.string.onboarding_cloud_body),
    )
    CloudProvider.entries.chunked(2).forEach { rowProviders ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rowProviders.forEach { provider ->
                ChoiceChip(
                    label = if (provider == CloudProvider.CUSTOM) {
                        stringResource(R.string.onboarding_cloud_custom)
                    } else {
                        provider.displayName
                    },
                    selected = draft.cloudProvider == provider,
                    onClick = {
                        onDraftChange(OnboardingPolicy.selectCloudProvider(draft, provider))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowProviders.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    OutlinedTextField(
        value = draft.cloudBaseUrl,
        onValueChange = { onDraftChange(draft.copy(cloudBaseUrl = it)) },
        label = { Text(stringResource(R.string.onboarding_cloud_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.cloudApiKey,
        onValueChange = { onDraftChange(draft.copy(cloudApiKey = it)) },
        label = { Text(stringResource(R.string.onboarding_cloud_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.cloudModel,
        onValueChange = { onDraftChange(draft.copy(cloudModel = it)) },
        label = { Text(stringResource(R.string.onboarding_cloud_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OnboardingPolicy.cloudConfigError(draft)?.let { error ->
        Text(
            text = stringResource(
                when (error) {
                    CloudConfigError.BASE_URL_REQUIRED ->
                        R.string.onboarding_cloud_error_url_required
                    CloudConfigError.BASE_URL_INVALID ->
                        R.string.onboarding_cloud_error_url_invalid
                    CloudConfigError.API_KEY_REQUIRED ->
                        R.string.onboarding_cloud_error_api_key
                    CloudConfigError.MODEL_REQUIRED ->
                        R.string.onboarding_cloud_error_model
                }
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TtsPage(
    draft: OnboardingDraft,
    onDraftChange: (OnboardingDraft) -> Unit,
) {
    PageHeading(
        icon = Icons.Default.RecordVoiceOver,
        title = stringResource(R.string.onboarding_tts_title),
        body = stringResource(R.string.onboarding_tts_body),
    )
    OnboardingTtsChoice.entries.forEach { choice ->
        val titleRes = when (choice) {
            OnboardingTtsChoice.DISABLED -> R.string.onboarding_tts_disabled
            OnboardingTtsChoice.SYSTEM -> R.string.onboarding_tts_system
            OnboardingTtsChoice.GENERIC_HTTP -> R.string.onboarding_tts_http
            OnboardingTtsChoice.VOLCENGINE -> R.string.onboarding_tts_volcengine
            OnboardingTtsChoice.MINIMAX -> R.string.onboarding_tts_minimax
            OnboardingTtsChoice.MIMO -> R.string.onboarding_tts_mimo
        }
        val descriptionRes = when (choice) {
            OnboardingTtsChoice.DISABLED -> R.string.onboarding_tts_disabled_desc
            OnboardingTtsChoice.SYSTEM -> R.string.onboarding_tts_system_desc
            OnboardingTtsChoice.GENERIC_HTTP -> R.string.onboarding_tts_http_desc
            OnboardingTtsChoice.VOLCENGINE -> R.string.onboarding_tts_online_desc
            OnboardingTtsChoice.MINIMAX -> R.string.onboarding_tts_online_desc
            OnboardingTtsChoice.MIMO -> R.string.onboarding_tts_online_desc
        }
        ChoiceCard(
            selected = draft.ttsChoice == choice,
            title = stringResource(titleRes),
            description = stringResource(descriptionRes),
            onClick = { onDraftChange(draft.copy(ttsChoice = choice)) },
        )
    }
}

@Composable
private fun SummaryPage(draft: OnboardingDraft) {
    val context = androidx.compose.ui.platform.LocalContext.current
    PageHeading(
        icon = Icons.Default.CheckCircle,
        title = stringResource(R.string.onboarding_summary_title),
        body = stringResource(R.string.onboarding_summary_body),
    )
    SummaryRow(
        stringResource(R.string.onboarding_summary_languages),
        "${Languages.nameOf(context, draft.sourceLang)} → " +
            Languages.nameOf(context, draft.targetLang),
    )
    SummaryRow(
        stringResource(R.string.onboarding_summary_usage),
        stringResource(
            if (draft.usage == OnboardingUsage.MANGA) {
                R.string.onboarding_usage_manga
            } else {
                R.string.onboarding_usage_daily
            }
        ),
    )
    SummaryRow(
        stringResource(R.string.onboarding_summary_display),
        stringResource(
            if (draft.usage == OnboardingUsage.MANGA) {
                R.string.onboarding_display_adaptive
            } else {
                when (draft.displayMode) {
                    OnboardingDisplayMode.ADAPTIVE_OVERLAY ->
                        R.string.onboarding_display_adaptive
                    OnboardingDisplayMode.BELOW_SOURCE ->
                        R.string.onboarding_display_below
                    OnboardingDisplayMode.FLOATING_WINDOW ->
                        R.string.onboarding_display_floating
                }
            }
        ),
    )
    SummaryRow(
        stringResource(R.string.onboarding_summary_translation),
        if (draft.translationMethod == OnboardingTranslationMethod.OFFLINE) {
            stringResource(
                if (draft.usage == OnboardingUsage.MANGA) {
                    R.string.onboarding_summary_manga_offline
                } else {
                    R.string.onboarding_method_offline
                }
            )
        } else {
            draft.cloudProvider.displayName
        },
    )
    SummaryRow(
        stringResource(R.string.onboarding_summary_tts),
        stringResource(
            when (draft.ttsChoice) {
                OnboardingTtsChoice.DISABLED -> R.string.onboarding_tts_disabled
                OnboardingTtsChoice.SYSTEM -> R.string.onboarding_tts_system
                OnboardingTtsChoice.GENERIC_HTTP -> R.string.onboarding_tts_http
                OnboardingTtsChoice.VOLCENGINE -> R.string.onboarding_tts_volcengine
                OnboardingTtsChoice.MINIMAX -> R.string.onboarding_tts_minimax
                OnboardingTtsChoice.MIMO -> R.string.onboarding_tts_mimo
            }
        ),
    )
    if (draft.usage == OnboardingUsage.MANGA) {
        Text(
            stringResource(R.string.onboarding_summary_manga_defaults),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModelRecommendationRow(
    title: String,
    detail: String,
    ready: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (ready) Icons.Default.CheckCircle else Icons.Default.Download,
                contentDescription = null,
                tint = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        if (ready) {
                            R.string.onboarding_model_ready
                        } else {
                            R.string.onboarding_model_not_downloaded
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun PageHeading(icon: ImageVector, title: String, body: String) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(38.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChoiceCard(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(label, maxLines = 1)
    }
}

@Composable
private fun StatusRow(loading: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            textAlign = TextAlign.End,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.6f),
        )
    }
}

private fun canContinue(
    step: OnboardingStep,
    draft: OnboardingDraft,
    downloadState: MlKitDownloadState,
): Boolean = when (step) {
    OnboardingStep.SOURCE_LANGUAGE -> draft.sourceLang.isNotBlank() &&
        draft.sourceLang != Languages.AUTO.code
    OnboardingStep.TARGET_LANGUAGE -> draft.targetLang.isNotBlank() &&
        draft.targetLang != Languages.AUTO.code &&
        draft.targetLang != draft.sourceLang
    OnboardingStep.TRANSLATION_METHOD -> when {
        draft.translationMethod != OnboardingTranslationMethod.OFFLINE -> true
        draft.usage == OnboardingUsage.MANGA ->
            OnboardingPolicy.isSakuraPairSupported(draft.sourceLang, draft.targetLang)
        else -> OnboardingPolicy.isMlKitPairSupported(draft.sourceLang, draft.targetLang)
    }
    OnboardingStep.OFFLINE_LANGUAGE_DOWNLOAD ->
        downloadState == MlKitDownloadState.Ready
    OnboardingStep.CLOUD_CONFIG -> OnboardingPolicy.cloudConfigError(draft) == null
    else -> true
}
