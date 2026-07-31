package com.gameocr.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.gallery.GalleryImageDecoder
import com.gameocr.app.gallery.GalleryExportProgress
import com.gameocr.app.gallery.GalleryExportRenderMode
import com.gameocr.app.gallery.GalleryItemStatus
import com.gameocr.app.gallery.GalleryTaskStatus
import com.gameocr.app.gallery.GalleryTranslationExporter
import com.gameocr.app.gallery.GalleryTranslationItemEntity
import com.gameocr.app.gallery.GalleryTranslationManager
import com.gameocr.app.gallery.GalleryTranslationRepository
import com.gameocr.app.gallery.GalleryTranslationTaskEntity
import com.gameocr.app.gallery.GalleryTranslationWorkPolicy
import com.gameocr.app.gallery.GalleryTranslatedPreviewStore
import com.gameocr.app.gallery.galleryCanExport
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryTranslationConfirmScreen(
    selectedUris: List<String>,
    onSelectionChanged: (List<String>) -> Unit,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: GalleryTranslationViewModel = hiltViewModel(),
    presetViewModel: MainViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState(initial = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showPresetSwitcher by rememberSaveable { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(
            GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK
        )
    ) { uris ->
        if (uris.isNotEmpty()) {
            onSelectionChanged(
                GalleryTranslationWorkPolicy.mergeSelection(
                    current = selectedUris,
                    additions = uris.map(Uri::toString),
                )
            )
        }
    }
    BackHandler(enabled = !creating, onBack = onBack)
    previewIndex?.let { initialPage ->
        GalleryImagePreviewDialog(
            sources = selectedUris.mapIndexed { index, uri ->
                GalleryPreviewSource(
                    sourceUri = uri,
                    localPath = "",
                    displayName = context.getString(
                        R.string.gallery_preview_selected_name,
                        index + 1,
                    ),
                )
            },
            initialPage = initialPage,
            imageDecoder = viewModel.imageDecoder,
            onDismiss = { previewIndex = null },
        )
    }
    if (showCreateDialog) {
        CatalystAlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = {
                Text(stringResource(R.string.gallery_confirm_create_dialog_title))
            },
            text = {
                Text(
                    stringResource(
                        R.string.gallery_confirm_create_dialog_message,
                        selectedUris.size,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        creating = true
                        errorMessage = ""
                        scope.launch {
                            runCatching { viewModel.createAndEnqueue(selectedUris) }
                                .onSuccess(onCreated)
                                .onFailure {
                                    errorMessage = it.message ?: it.javaClass.simpleName
                                    creating = false
                                }
                        }
                    }
                ) {
                    Text(stringResource(R.string.gallery_confirm_create_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_confirm_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !creating) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.gallery_confirm_settings),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            settings?.let { current ->
                                Text(
                                    stringResource(
                                        R.string.gallery_confirm_language,
                                        Languages.nameOf(context, current.sourceLang),
                                        Languages.nameOf(context, current.targetLang),
                                    )
                                )
                                Text(
                                    stringResource(
                                        R.string.gallery_confirm_ocr,
                                        stringResource(ocrEngineLabelRes(current.ocrEngine)),
                                    )
                                )
                                Text(
                                    stringResource(
                                        R.string.gallery_confirm_translator,
                                        stringResource(
                                            translatorEngineLabelRes(current.translatorEngine)
                                        ),
                                    )
                                )
                            } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Column {
                                HorizontalDivider()
                                AnimatedVisibility(
                                    visible = showPresetSwitcher,
                                    enter = expandVertically(
                                        animationSpec = tween(
                                            durationMillis = 220,
                                            easing = FastOutSlowInEasing,
                                        ),
                                        expandFrom = Alignment.Top,
                                    ),
                                    exit = shrinkVertically(
                                        animationSpec = tween(
                                            durationMillis = 220,
                                            easing = FastOutSlowInEasing,
                                        ),
                                        shrinkTowards = Alignment.Top,
                                    ),
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        GalleryPresetSwitcher(
                                            settings = settings,
                                            viewModel = presetViewModel,
                                            snackbarHostState = snackbarHostState,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember {
                                                MutableInteractionSource()
                                            },
                                            indication = null,
                                            enabled = !creating,
                                            role = Role.Button,
                                            onClick = {
                                                showPresetSwitcher = !showPresetSwitcher
                                            },
                                        )
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = if (showPresetSwitcher) {
                                            Icons.Default.KeyboardArrowUp
                                        } else {
                                            Icons.Default.KeyboardArrowDown
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = stringResource(
                                            if (showPresetSwitcher) {
                                                R.string.gallery_confirm_hide_presets
                                            } else {
                                                R.string.gallery_confirm_show_presets
                                            }
                                        ),
                                        modifier = Modifier.padding(start = 2.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.gallery_confirm_count, selectedUris.size),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedUris.isEmpty()) {
                            Text(
                                stringResource(R.string.gallery_confirm_empty_selection),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val spacing = 8.dp
                            val columnCount = galleryThumbnailColumnCount(maxWidth.value)
                            val itemWidth = galleryThumbnailWidthDp(
                                availableWidthDp = maxWidth.value,
                                columnCount = columnCount,
                            ).dp
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing),
                                verticalArrangement = Arrangement.spacedBy(spacing),
                                maxItemsInEachRow = columnCount,
                            ) {
                                selectedUris.forEachIndexed { index, uri ->
                                    key(uri) {
                                        GallerySelectedThumbnail(
                                            uriString = uri,
                                            index = index,
                                            enabled = !creating,
                                            imageDecoder = viewModel.imageDecoder,
                                            modifier = Modifier.width(itemWidth),
                                            onPreview = { previewIndex = index },
                                            onRemove = {
                                                onSelectionChanged(
                                                    GalleryTranslationWorkPolicy.removeSelection(
                                                        current = selectedUris,
                                                        uriString = uri,
                                                    )
                                                )
                                            },
                                        )
                                    }
                                }
                                if (
                                    selectedUris.size <
                                    GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK
                                ) {
                                    GalleryAddImageTile(
                                        modifier = Modifier.width(itemWidth),
                                        enabled = !creating,
                                        onClick = {
                                            picker.launch(
                                                PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (errorMessage.isNotBlank()) {
                    item {
                        Text(
                            stringResource(R.string.gallery_confirm_error, errorMessage),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = selectedUris.isNotEmpty() && settings != null && !creating,
                onClick = { showCreateDialog = true },
            ) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(R.string.gallery_confirm_creating),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                } else {
                    Text(stringResource(R.string.gallery_confirm_create))
                }
            }
        }
    }
}

@Composable
private fun GalleryPresetSwitcher(
    settings: Settings?,
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val unsavedPresetName = stringResource(R.string.settings_translation_preset_unsaved_name)
    val plans = remember(settings, unsavedPresetName) {
        settings?.let { presetCarouselPlans(it, unsavedPresetName) }
    }
    val presets = plans?.presets.orEmpty()
    var modelIssues by remember {
        mutableStateOf<Map<String, List<TranslationPresetModelIssue>>?>(null)
    }
    var pendingPresetSwitch by remember { mutableStateOf<TranslationPreset?>(null) }
    var pendingSaveBeforePresetSwitch by remember { mutableStateOf<TranslationPreset?>(null) }
    var pendingPresetSaveName by rememberSaveable { mutableStateOf("") }
    val presetNotReadyMessage =
        stringResource(R.string.main_preset_models_not_ready_message)

    LaunchedEffect(presets) {
        modelIssues = viewModel.presetModelIssues(presets)
    }

    val applyPresetNow: (TranslationPreset) -> Unit = { preset ->
        scope.launch {
            if (!viewModel.applyTranslationPreset(preset.id)) {
                modelIssues = viewModel.presetModelIssues(presets)
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
                                modelIssues = viewModel.presetModelIssues(presets)
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

    PresetCarousel(
        presets = presets,
        activePresetId = plans?.currentPresetId.orEmpty(),
        modelIssuesByPreset = modelIssues,
        onPresetSelected = { preset ->
            if (shouldConfirmUnsavedPresetSwitch(
                    currentPresetId = plans?.currentPresetId.orEmpty(),
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
    )
}

internal fun galleryThumbnailColumnCount(
    availableWidthDp: Float,
    minThumbnailWidthDp: Float = 96f,
    spacingDp: Float = 8f,
): Int {
    if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 1
    val minimum = minThumbnailWidthDp.takeIf { it.isFinite() && it > 0f } ?: 96f
    val spacing = spacingDp.takeIf { it.isFinite() && it >= 0f } ?: 8f
    return floor((availableWidthDp + spacing) / (minimum + spacing))
        .toInt()
        .coerceAtLeast(1)
}

internal fun galleryThumbnailWidthDp(
    availableWidthDp: Float,
    columnCount: Int,
    spacingDp: Float = 8f,
): Float {
    if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 0f
    val columns = columnCount.coerceAtLeast(1)
    val spacing = spacingDp.takeIf { it.isFinite() && it >= 0f } ?: 8f
    return ((availableWidthDp - spacing * (columns - 1)) / columns).coerceAtLeast(0f)
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun GallerySelectedThumbnail(
    uriString: String,
    index: Int,
    enabled: Boolean,
    imageDecoder: GalleryImageDecoder,
    modifier: Modifier = Modifier,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = uriString,
    ) {
        value = withContext(Dispatchers.IO) {
            imageDecoder.decodeThumbnail(uriString, localPath = "")
        }
    }
    Card(
        onClick = onPreview,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize()) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(
                        R.string.gallery_confirm_remove_photo,
                        index + 1,
                    ),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun GalleryAddImageTile(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.aspectRatio(1f),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val inset = strokeWidth / 2f
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, inset),
                        size = Size(
                            width = size.width - strokeWidth,
                            height = size.height - strokeWidth,
                        ),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(
                            width = strokeWidth,
                            pathEffect = PathEffect.dashPathEffect(
                                intervals = floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                            ),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.gallery_confirm_add_photos),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
fun GalleryTranslationTasksScreen(
    onBack: () -> Unit,
    onImagesSelected: (List<String>) -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: GalleryTranslationViewModel = hiltViewModel(),
) {
    val tasks by viewModel.tasks.collectAsState(initial = emptyList())
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(
            GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK
        )
    ) { uris ->
        if (uris.isNotEmpty()) onImagesSelected(uris.map(Uri::toString))
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_tasks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Text(
                            stringResource(R.string.gallery_tasks_new),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.gallery_tasks_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasks, key = GalleryTranslationTaskEntity::id) { task ->
                    GalleryTaskCard(task = task, onOpen = { onOpenTask(task.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryTranslationTaskDetailScreen(
    taskId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: GalleryTranslationViewModel = hiltViewModel(),
) {
    val taskFlow = remember(taskId) { viewModel.observeTask(taskId) }
    val itemFlow = remember(taskId) { viewModel.observeItems(taskId) }
    val task by taskFlow.collectAsState(initial = null)
    val items by itemFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var previewItemId by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableStateOf<GalleryExportProgress?>(null) }
    var resultFilterIndex by rememberSaveable(taskId) { mutableIntStateOf(0) }
    val resultFilter = GalleryResultFilter.entries
        .getOrElse(resultFilterIndex) { GalleryResultFilter.ALL }
    val filteredItems = items.filter { item ->
        galleryResultFilterMatches(resultFilter, item.status)
    }
    val previewItems = filteredItems.filterNot { item ->
        galleryResultThumbnailShowsProcessing(item.status)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null && exportProgress == null) {
            scope.launch {
                exportProgress = GalleryExportProgress(
                    completed = 0,
                    total = task?.successCount ?: 0,
                    displayName = "",
                )
                try {
                    val result = viewModel.exportTask(
                        taskId = taskId,
                        treeUri = treeUri,
                        onProgress = { progress -> exportProgress = progress },
                    )
                    val message = when {
                        result.total == 0 -> context.getString(R.string.gallery_export_empty)
                        result.failed == 0 -> context.getString(
                            R.string.gallery_export_success,
                            result.exported,
                        )
                        else -> context.getString(
                            R.string.gallery_export_partial,
                            result.exported,
                            result.failed,
                        )
                    }
                    snackbarHostState.showSnackbar(message)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.gallery_export_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    )
                } finally {
                    exportProgress = null
                }
            }
        }
    }
    BackHandler(onBack = onBack)
    previewItemId?.let { selectedId ->
        val initialPage = previewItems.indexOfFirst { item -> item.id == selectedId }
        if (initialPage >= 0) {
            GalleryImagePreviewDialog(
                sources = previewItems.map { item ->
                    GalleryPreviewSource(
                        sourceUri = item.sourceUri,
                        localPath = item.localPath,
                        displayName = item.displayName,
                        resultItem = item,
                    )
                },
                initialPage = initialPage,
                imageDecoder = viewModel.imageDecoder,
                resultPreviewLoader = viewModel::loadResultPreview,
                onDismiss = { previewItemId = null },
            )
        }
    }

    if (showDeleteDialog) {
        CatalystAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.gallery_task_delete_title)) },
            text = { Text(stringResource(R.string.gallery_task_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            viewModel.delete(taskId)
                            onDeleted()
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.gallery_task_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (showCancelDialog) {
        CatalystAlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.gallery_task_cancel_title)) },
            text = { Text(stringResource(R.string.gallery_task_cancel_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        scope.launch { viewModel.cancel(taskId) }
                    }
                ) {
                    Text(
                        stringResource(R.string.gallery_task_cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val currentTask = task
    val exportRenderMode = currentTask?.let(viewModel::exportRenderModeForTask)
    val currentExportProgress = exportProgress
    val canExport = currentTask != null &&
        exportRenderMode != null &&
        galleryCanExport(currentTask.status, currentTask.successCount, exportRenderMode)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_task_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionsExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(
                                    R.string.gallery_task_more_actions
                                ),
                            )
                        }
                        DropdownMenu(
                            expanded = actionsExpanded,
                            onDismissRequest = { actionsExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    when {
                                        exportRenderMode ==
                                            GalleryExportRenderMode.UNSUPPORTED_FLOATING -> {
                                            Text(
                                                stringResource(
                                                    R.string.gallery_export_floating_unavailable
                                                )
                                            )
                                        }
                                        currentExportProgress != null -> {
                                            Text(
                                                stringResource(
                                                    R.string.gallery_exporting,
                                                    currentExportProgress.completed,
                                                    currentExportProgress.total,
                                                )
                                            )
                                        }
                                        else -> {
                                            Text(stringResource(R.string.gallery_export_action))
                                        }
                                    }
                                },
                                onClick = {
                                    actionsExpanded = false
                                    exportLauncher.launch(null)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                },
                                enabled = canExport && currentExportProgress == null,
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.gallery_task_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    actionsExpanded = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (currentTask == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.gallery_task_missing))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    GalleryTaskSummary(
                        task = currentTask,
                        onCancel = { showCancelDialog = true },
                        onRetry = {
                            scope.launch { viewModel.retryFailed(currentTask.id) }
                        },
                    )
                }
            }
            stickyHeader(key = "gallery-result-tabs") {
                GalleryResultTabs(
                    selected = resultFilter,
                    items = items,
                    onSelected = { selected ->
                        resultFilterIndex = selected.ordinal
                    },
                )
            }
            items(filteredItems, key = GalleryTranslationItemEntity::id) { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    GalleryResultItem(
                        item = item,
                        thumbnailLoader = viewModel::loadResultThumbnail,
                        onPreview = { previewItemId = item.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryTaskCard(
    task: GalleryTranslationTaskEntity,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT,
                    ).format(Date(task.createdAtMs)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                GalleryStatusText(task.status)
            }
            GalleryTaskSettingsSummary(task)
            GalleryTaskProgressSummary(task)
        }
    }
}

@Composable
private fun GalleryTaskSettingsSummary(
    task: GalleryTranslationTaskEntity,
) {
    val context = LocalContext.current
    val ocrEngine = remember(task.ocrEngine) {
        runCatching { OcrEngineKind.valueOf(task.ocrEngine) }.getOrNull()
    }
    val translatorEngine = remember(task.translatorEngine) {
        runCatching { TranslatorEngine.valueOf(task.translatorEngine) }.getOrNull()
    }
    val ocrLabel = ocrEngine?.let { stringResource(ocrEngineLabelRes(it)) }
        ?: task.ocrEngine
    val translatorLabel = translatorEngine?.let {
        stringResource(translatorEngineLabelRes(it))
    } ?: task.translatorEngine
    Text(
        stringResource(
            R.string.gallery_task_settings_summary,
            ocrLabel,
            translatorLabel,
            Languages.nameOf(context, task.sourceLang),
            Languages.nameOf(context, task.targetLang),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun GalleryTaskSummary(
    task: GalleryTranslationTaskEntity,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val active = task.status in activeTaskStatuses
    val canRetry = task.failedCount > 0 && !active
    val nowMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = task.id,
        key2 = task.startedAtMs,
        key3 = task.finishedAtMs,
    ) {
        while (task.startedAtMs != null && task.finishedAtMs == null) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val elapsedSeconds = galleryTaskElapsedSeconds(
        startedAtMs = task.startedAtMs,
        finishedAtMs = task.finishedAtMs,
        nowMs = nowMs,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT,
                    ).format(Date(task.createdAtMs)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(horizontalAlignment = Alignment.End) {
                    GalleryStatusText(task.status)
                    elapsedSeconds?.let { seconds ->
                        Text(
                            stringResource(
                                R.string.gallery_task_duration,
                                DateUtils.formatElapsedTime(seconds),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            GalleryTaskSettingsSummary(task)
            GalleryTaskProgressSummary(task, showOutcomes = false)
            if (active && task.totalCount > 0) {
                if (task.currentItemName.isNotBlank()) {
                    Text(
                        task.currentItemName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active || canRetry) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (active) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Text(
                                stringResource(R.string.gallery_task_cancel),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    if (canRetry) {
                        FilledTonalButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(
                                stringResource(R.string.gallery_task_retry),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTaskProgressSummary(
    task: GalleryTranslationTaskEntity,
    showOutcomes: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.gallery_task_progress_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.gallery_task_progress_value,
                task.completedCount,
                task.totalCount,
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (task.totalCount > 0 && task.status in activeTaskStatuses) {
        LinearProgressIndicator(
            progress = { task.completedCount.toFloat() / task.totalCount },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showOutcomes) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(
                    R.string.gallery_task_success_count,
                    task.successCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(
                    R.string.gallery_task_failed_count,
                    task.failedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (task.failedCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun GalleryResultTabs(
    selected: GalleryResultFilter,
    items: List<GalleryTranslationItemEntity>,
    onSelected: (GalleryResultFilter) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        SecondaryTabRow(
            selectedTabIndex = selected.ordinal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GalleryResultFilter.entries.forEach { filter ->
                val count = items.count { item ->
                    galleryResultFilterMatches(filter, item.status)
                }
                Tab(
                    selected = selected == filter,
                    onClick = { onSelected(filter) },
                    text = {
                        Text(
                            stringResource(
                                when (filter) {
                                    GalleryResultFilter.ALL ->
                                        R.string.gallery_task_all_count
                                    GalleryResultFilter.SUCCEEDED ->
                                        R.string.gallery_task_success_count
                                    GalleryResultFilter.FAILED ->
                                        R.string.gallery_task_failed_count
                                },
                                count,
                            )
                        )
                    },
                )
            }
        }
    }
}

internal enum class GalleryResultFilter {
    ALL,
    SUCCEEDED,
    FAILED,
}

internal fun galleryResultFilterMatches(
    filter: GalleryResultFilter,
    status: GalleryItemStatus,
): Boolean = when (filter) {
    GalleryResultFilter.ALL -> true
    GalleryResultFilter.SUCCEEDED -> status == GalleryItemStatus.SUCCEEDED
    GalleryResultFilter.FAILED -> status == GalleryItemStatus.FAILED
}

@Composable
private fun GalleryResultItem(
    item: GalleryTranslationItemEntity,
    thumbnailLoader: suspend (GalleryTranslationItemEntity) -> android.graphics.Bitmap?,
    onPreview: () -> Unit,
) {
    val thumbnailRatio = galleryResultThumbnailRatio(
        processedWidth = item.processedWidth,
        processedHeight = item.processedHeight,
    )
    val textPaneHeight = galleryResultTextPaneHeightDp(thumbnailRatio).dp
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GalleryResultThumbnail(
                    item = item,
                    aspectRatio = thumbnailRatio,
                    thumbnailLoader = thumbnailLoader,
                    onPreview = onPreview,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (item.status == GalleryItemStatus.SUCCEEDED) {
                                Modifier.height(textPaneHeight)
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            item.displayName,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        GalleryItemStatusText(item.status)
                    }
                    if (item.status == GalleryItemStatus.FAILED) {
                        Text(
                            stringResource(R.string.gallery_item_error, item.errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (item.status == GalleryItemStatus.SUCCEEDED) {
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            GalleryResultTextSection(
                                title = stringResource(R.string.gallery_item_source),
                                text = item.sourceText,
                                modifier = Modifier.weight(1f),
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            GalleryResultTextSection(
                                title = stringResource(R.string.gallery_item_translation),
                                text = item.translatedText.ifBlank {
                                    stringResource(R.string.gallery_item_no_translation)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryResultTextSection(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun galleryTaskElapsedSeconds(
    startedAtMs: Long?,
    finishedAtMs: Long?,
    nowMs: Long,
): Long? {
    val started = startedAtMs ?: return null
    val ended = finishedAtMs ?: nowMs
    return (ended - started).coerceAtLeast(0L) / 1_000L
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun GalleryResultThumbnail(
    item: GalleryTranslationItemEntity,
    aspectRatio: Float,
    thumbnailLoader: suspend (GalleryTranslationItemEntity) -> android.graphics.Bitmap?,
    onPreview: () -> Unit,
) {
    val showProcessingPlaceholder = galleryResultThumbnailShowsProcessing(item.status)
    val thumbnailShape = RoundedCornerShape(4.dp)
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = item.id,
        key2 = item.updatedAtMs,
    ) {
        if (!showProcessingPlaceholder) {
            value = withContext(Dispatchers.IO) {
                thumbnailLoader(item)
            }
        }
    }
    Box(
        modifier = Modifier
            .width(112.dp)
            .aspectRatio(aspectRatio)
            .clip(thumbnailShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = thumbnailShape,
            )
            .clickable(
                enabled = !showProcessingPlaceholder,
                onClick = onPreview,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (showProcessingPlaceholder) {
            Text(
                stringResource(R.string.gallery_item_processing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}

internal fun galleryResultThumbnailShowsProcessing(
    status: GalleryItemStatus,
): Boolean = when (status) {
    GalleryItemStatus.QUEUED,
    GalleryItemStatus.RUNNING,
    -> true

    GalleryItemStatus.SUCCEEDED,
    GalleryItemStatus.FAILED,
    GalleryItemStatus.CANCELED,
    -> false
}

private data class GalleryPreviewSource(
    val sourceUri: String,
    val localPath: String,
    val displayName: String,
    val resultItem: GalleryTranslationItemEntity? = null,
)

private data class GalleryPreviewLoadState(
    val loading: Boolean = true,
    val bitmap: android.graphics.Bitmap? = null,
)

@Composable
private fun GalleryImagePreviewDialog(
    sources: List<GalleryPreviewSource>,
    initialPage: Int,
    imageDecoder: GalleryImageDecoder,
    resultPreviewLoader: (suspend (GalleryTranslationItemEntity) -> android.graphics.Bitmap?)? = null,
    onDismiss: () -> Unit,
) {
    if (sources.isEmpty()) return
    val safeInitialPage = galleryPreviewInitialPage(initialPage, sources.size)
    val pagerState = rememberPagerState(initialPage = safeInitialPage) { sources.size }
    val currentSource = sources[pagerState.currentPage]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .navigationBarsPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> "${sources[page].sourceUri}#$page" },
            ) { page ->
                GalleryImagePreviewPage(
                    source = sources[page],
                    imageDecoder = imageDecoder,
                    resultPreviewLoader = resultPreviewLoader,
                )
            }
            Text(
                text = currentSource.displayName,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 16.dp, end = 64.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.gallery_preview_close),
                    tint = Color.White,
                )
            }
        }
    }
}

@SuppressLint("ProduceStateDoesNotAssignValue")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryImagePreviewPage(
    source: GalleryPreviewSource,
    imageDecoder: GalleryImageDecoder,
    resultPreviewLoader: (suspend (GalleryTranslationItemEntity) -> android.graphics.Bitmap?)?,
) {
    val previewState by produceState(
        initialValue = GalleryPreviewLoadState(),
        key1 = source,
    ) {
        value = withContext(Dispatchers.IO) {
            val resultItem = source.resultItem
            val resultLoader = resultPreviewLoader
            GalleryPreviewLoadState(
                loading = false,
                bitmap = if (resultItem != null && resultLoader != null) {
                    resultLoader(resultItem)
                } else {
                    imageDecoder.decodePreview(
                        sourceUri = source.sourceUri,
                        localPath = source.localPath,
                    )
                },
            )
        }
    }
    val previewBitmap = previewState.bitmap
    DisposableEffect(previewBitmap) {
        onDispose {
            previewBitmap?.recycle()
        }
    }
    var scale by remember(source.sourceUri, source.localPath) { mutableStateOf(1f) }
    var offset by remember(source.sourceUri, source.localPath) {
        mutableStateOf(Offset.Zero)
    }
    var viewportSize by remember(source.sourceUri, source.localPath) {
        mutableStateOf(IntSize.Zero)
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = galleryPreviewScale(scale, zoomChange)
        val panLimit = galleryPreviewPanLimit(
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat(),
            imageWidth = previewBitmap?.width?.toFloat() ?: 0f,
            imageHeight = previewBitmap?.height?.toFloat() ?: 0f,
            scale = nextScale,
        )
        val nextOffset = if (nextScale == GALLERY_PREVIEW_MIN_SCALE) {
            Offset.Zero
        } else {
            offset + panChange
        }
        scale = nextScale
        offset = Offset(
            x = nextOffset.x.coerceIn(-panLimit.x, panLimit.x),
            y = nextOffset.y.coerceIn(-panLimit.y, panLimit.y),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .onSizeChanged { viewportSize = it }
            .transformable(
                state = transformState,
                canPan = { scale > GALLERY_PREVIEW_MIN_SCALE },
                lockRotationOnZoomPan = true,
            ),
    ) {
        when {
            previewBitmap != null -> Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = source.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            previewState.loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            else -> Text(
                stringResource(R.string.gallery_preview_failed),
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private const val GALLERY_PREVIEW_MIN_SCALE = 1f
private const val GALLERY_PREVIEW_MAX_SCALE = 5f

internal fun galleryPreviewScale(
    currentScale: Float,
    zoomChange: Float,
): Float = (currentScale * zoomChange).coerceIn(
    GALLERY_PREVIEW_MIN_SCALE,
    GALLERY_PREVIEW_MAX_SCALE,
)

internal fun galleryPreviewPanLimit(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    scale: Float,
): Offset {
    if (
        viewportWidth <= 0f ||
        viewportHeight <= 0f ||
        imageWidth <= 0f ||
        imageHeight <= 0f
    ) {
        return Offset.Zero
    }
    val fitScale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val scaledWidth = imageWidth * fitScale * scale
    val scaledHeight = imageHeight * fitScale * scale
    return Offset(
        x = ((scaledWidth - viewportWidth) / 2f).coerceAtLeast(0f),
        y = ((scaledHeight - viewportHeight) / 2f).coerceAtLeast(0f),
    )
}

internal fun galleryPreviewInitialPage(
    requestedPage: Int,
    pageCount: Int,
): Int = if (pageCount <= 0) {
    0
} else {
    requestedPage.coerceIn(0, pageCount - 1)
}

@Composable
private fun GalleryStatusText(status: GalleryTaskStatus) {
    val color = when (status) {
        GalleryTaskStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
        GalleryTaskStatus.PARTIAL,
        GalleryTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        GalleryTaskStatus.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        stringResource(status.labelRes()),
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun GalleryItemStatusText(status: GalleryItemStatus) {
    val taskStatus = when (status) {
        GalleryItemStatus.QUEUED -> GalleryTaskStatus.QUEUED
        GalleryItemStatus.RUNNING -> GalleryTaskStatus.RUNNING
        GalleryItemStatus.SUCCEEDED -> GalleryTaskStatus.SUCCEEDED
        GalleryItemStatus.FAILED -> GalleryTaskStatus.FAILED
        GalleryItemStatus.CANCELED -> GalleryTaskStatus.CANCELED
    }
    GalleryStatusText(taskStatus)
}

private fun GalleryTaskStatus.labelRes(): Int = when (this) {
    GalleryTaskStatus.QUEUED -> R.string.gallery_status_queued
    GalleryTaskStatus.RUNNING -> R.string.gallery_status_running
    GalleryTaskStatus.WAITING_RETRY -> R.string.gallery_status_waiting_retry
    GalleryTaskStatus.PARTIAL -> R.string.gallery_status_partial
    GalleryTaskStatus.SUCCEEDED -> R.string.gallery_status_succeeded
    GalleryTaskStatus.FAILED -> R.string.gallery_status_failed
    GalleryTaskStatus.CANCELED -> R.string.gallery_status_canceled
}

private val activeTaskStatuses = setOf(
    GalleryTaskStatus.QUEUED,
    GalleryTaskStatus.RUNNING,
    GalleryTaskStatus.WAITING_RETRY,
)

@HiltViewModel
class GalleryTranslationViewModel @Inject constructor(
    private val repository: GalleryTranslationRepository,
    private val manager: GalleryTranslationManager,
    private val exporter: GalleryTranslationExporter,
    private val translatedPreviewStore: GalleryTranslatedPreviewStore,
    settingsRepository: SettingsRepository,
    val imageDecoder: GalleryImageDecoder,
) : ViewModel() {
    val settings = settingsRepository.settings
    val tasks = repository.observeTasks()

    fun observeTask(taskId: String): Flow<GalleryTranslationTaskEntity?> =
        repository.observeTask(taskId)

    fun observeItems(taskId: String): Flow<List<GalleryTranslationItemEntity>> =
        repository.observeItems(taskId)

    suspend fun createAndEnqueue(uriStrings: List<String>): String {
        val task = repository.createTask(uriStrings)
        manager.enqueue(task)
        return task.id
    }

    suspend fun cancel(taskId: String) = manager.cancel(taskId)

    suspend fun retryFailed(taskId: String): Boolean = manager.retryFailed(taskId)

    suspend fun delete(taskId: String) = manager.delete(taskId)

    suspend fun exportTask(
        taskId: String,
        treeUri: Uri,
        onProgress: (GalleryExportProgress) -> Unit,
    ) = exporter.exportTask(taskId, treeUri, onProgress)

    internal fun exportRenderModeForTask(task: GalleryTranslationTaskEntity): GalleryExportRenderMode =
        repository.exportRenderModeForTask(task)

    suspend fun loadResultThumbnail(item: GalleryTranslationItemEntity): android.graphics.Bitmap? {
        val task = repository.getTask(item.taskId)
        val translated = task?.let {
            translatedPreviewStore.loadTranslatedThumbnail(
                item = item,
                settings = repository.settingsForTask(it),
            )
        }
        return translated ?: imageDecoder.decodeThumbnail(item.sourceUri, item.localPath)
    }

    suspend fun loadResultPreview(item: GalleryTranslationItemEntity): android.graphics.Bitmap? {
        val task = repository.getTask(item.taskId)
        val translated = task?.let {
            translatedPreviewStore.loadTranslatedPreview(
                item = item,
                settings = repository.settingsForTask(it),
            )
        }
        return translated ?: imageDecoder.decodePreview(item.sourceUri, item.localPath)
    }

}

internal fun galleryResultThumbnailRatio(
    processedWidth: Int,
    processedHeight: Int,
): Float = if (processedWidth > 0 && processedHeight > 0) {
    (processedWidth.toFloat() / processedHeight).coerceIn(0.45f, 2.2f)
} else {
    1f
}

internal fun galleryResultTextPaneHeightDp(thumbnailRatio: Float): Float =
    (112f / thumbnailRatio).coerceAtLeast(112f)
