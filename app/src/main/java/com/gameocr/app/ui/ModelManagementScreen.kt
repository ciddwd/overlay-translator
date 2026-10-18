package com.gameocr.app.ui

import android.app.ActivityManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import com.gameocr.app.BuildConfig
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.llm.LocalLlmThreadPolicy
import com.gameocr.app.translate.GenericLocalPromptPolicy
import com.gameocr.app.translate.ManagedLocalModelRequestPolicy
import com.gameocr.app.translate.OfficialPromptTemplate
import com.gameocr.app.util.CpuThreadPolicy
import kotlin.math.roundToInt
import com.gameocr.app.data.Settings
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelDownloadType
import com.gameocr.app.download.ModelDownloadWorker
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.llm.LocalModelConfig
import com.gameocr.app.llm.LocalModelEntry
import com.gameocr.app.llm.LocalModelMemoryPolicy
import com.gameocr.app.llm.LocalLlmSamplingPolicy
import java.util.UUID
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelManagementScreen(
    onBack: () -> Unit,
    onModelSelected: (Settings) -> Unit = {},
    /** Opens directly on this model's settings; leaving them then returns to the caller, not the list. */
    initialDetailsId: String? = null,
    viewModel: ModelManagementViewModel = hiltViewModel(),
) {
    val models by viewModel.models.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val loadedId by viewModel.loadedModelId.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val queued by viewModel.queuedDownloads.collectAsState()
    val queuedIds by viewModel.queuedDownloadIds.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var detailsId by rememberSaveable { mutableStateOf(initialDetailsId) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCancelDownload by remember { mutableStateOf<UUID?>(null) }
    var pendingNetworkDownload by remember { mutableStateOf<Pair<LlmModelKind, ModelDownloadNetworkWarning>?>(null) }
    val notificationPermissionGate = rememberModelDownloadNotificationPermissionGate()
    val importSheet by viewModel.importSheet.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(message ?: context.getString(R.string.gallery_status_failed))
        }
    }
    val selectedId = selectedLocalModelId(settings)
    val activeDownloads = remember(downloads) { modelManagerDownloads(downloads) }
    val detail = models.firstOrNull { it.id == detailsId }
    LaunchedEffect(models, detailsId) {
        if (detailsId != null && detail == null && models.isNotEmpty()) detailsId = null
    }

    fun requestDownload(kind: LlmModelKind) {
        val warning = modelDownloadNetworkWarningFor(currentModelDownloadNetworkKind(context))
        if (warning == null) notificationPermissionGate { viewModel.download(kind) }
        else pendingNetworkDownload = kind to warning
    }

    val trial by viewModel.trial.collectAsState()

    if (detail != null) {
        ModelDetailScreen(
            entry = detail,
            settings = settings,
            busy = busy || importing || detail.kind?.let { it in queued || it in activeDownloads } == true,
            operationBusy = busy,
            onBack = { if (initialDetailsId != null) onBack() else detailsId = null },
            snackbar = snackbar,
            onSave = { config, saved -> viewModel.save(detail, config, saved) },
            onTrySample = { config -> viewModel.trySample(detail, config) },
            loadDefaults = { viewModel.defaultConfig(detail.id) },
            onDelete = { pendingDeleteId = detail.id },
        )
    } else {
        BackHandler(onBack = onBack)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.model_manager_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                        }
                    },
                )
            },
            bottomBar = {
                ModelBottomBar {
                    Button(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        enabled = !importing && !busy && viewModel.deviceCapable,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_import_button))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!viewModel.deviceCapable) {
                    item(key = "unsupported") {
                        Text(stringResource(R.string.err_llm_device_unsupported), color = MaterialTheme.colorScheme.error)
                    }
                }
                models.firstOrNull { it.id == loadedId }?.let { loaded ->
                    item(key = "loaded") {
                        LoadedModelBanner(loaded, busy = busy, onRelease = viewModel::unload)
                    }
                }
                // Grouped by what the user can change; installed models first within each group.
                listOf(
                    Triple("specialized", R.string.model_group_specialized, R.string.model_group_specialized_hint) to
                        models.filter { it.specialized },
                    Triple("general", R.string.model_group_general, R.string.model_group_general_hint) to
                        models.filterNot { it.specialized },
                ).forEach { (group, entries) ->
                    if (entries.isEmpty()) return@forEach
                    val (key, title, hint) = group
                    item(key = "$key-title") { ModelGroupHeading(stringResource(title), stringResource(hint)) }
                    items(entries.sortedByDescending { it.installed }, key = { it.id }) { entry ->
                        val kind = entry.kind
                        val workId = kind?.takeUnless { entry.installed }?.let { activeDownloads[it]?.id ?: queuedIds[it] }
                        ManagedModelCard(
                            entry = entry,
                            selected = entry.id == selectedId,
                            busy = busy || importing,
                            useEnabled = viewModel.deviceCapable,
                            download = kind?.takeUnless { entry.installed }?.let { activeDownloads[it] },
                            downloadId = workId,
                            queued = !entry.installed && kind in queued,
                            onUse = { viewModel.select(entry, onModelSelected) },
                            onTrySample = { viewModel.trySample(entry, entry.config) },
                            onDetails = { detailsId = entry.id },
                            onDownload = { kind?.let(::requestDownload) },
                            onCancel = { workId?.let { pendingCancelDownload = it } },
                        )
                    }
                }
            }
        }
    }

    trial?.let { state ->
        // Editable: the default is the last real text (or a dialogue sample); any text can be retried.
        var trialText by remember(state.entryId, state.source) { mutableStateOf(state.source) }
        CatalystAlertDialog(
            onDismissRequest = { if (!state.running) viewModel.dismissTrial() },
            title = { Text(stringResource(R.string.model_trial_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.model_trial_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = trialText, onValueChange = { trialText = it },
                        label = { Text(stringResource(R.string.model_trial_source)) },
                        enabled = !state.running, minLines = 2, maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        state.running -> Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.model_trial_running))
                        }
                        state.translation != null -> Text(state.translation, style = MaterialTheme.typography.bodyLarge)
                        else -> Text(state.error ?: stringResource(R.string.model_trial_failed),
                            color = MaterialTheme.colorScheme.error)
                    }
                    if (!state.running) state.elapsedMs?.let { elapsed ->
                        Text(stringResource(R.string.model_trial_elapsed, elapsed / 1000.0),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !state.running, onClick = viewModel::dismissTrial) {
                    Text(stringResource(R.string.model_trial_close))
                }
            },
            confirmButton = {
                TextButton(enabled = !state.running && !busy && trialText.isNotBlank(),
                    onClick = { viewModel.retryTrial(trialText) }) {
                    Text(stringResource(R.string.model_trial_retry))
                }
            },
        )
    }

    importSheet?.let { state ->
        ModelImportSheet(
            state = state,
            trialPassed = state.entry != null && trial?.let { it.entryId == state.entry.id && it.translation != null } == true,
            busy = busy,
            onTry = { state.entry?.let { entry -> viewModel.trySample(entry, entry.config) } },
            onCancel = viewModel::discardImport,
            onFinish = { name -> viewModel.finishImport(name, onModelSelected) },
        )
    }

    models.firstOrNull { it.id == pendingDeleteId }?.let { entry ->
        val deletionBlocked = busy || importing || entry.kind?.let { it in queued || it in activeDownloads } == true
        CatalystAlertDialog(
            onDismissRequest = { if (!busy) pendingDeleteId = null },
            title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.name, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_model_delete_confirm_message))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            },
            confirmButton = {
                TextButton(enabled = !deletionBlocked, onClick = {
                    viewModel.delete(entry) {
                        pendingDeleteId = null
                        if (detailsId == entry.id) {
                            // Opened straight from settings: there is no list behind this page.
                            if (initialDetailsId != null) onBack() else detailsId = null
                        }
                    }
                }) { Text(stringResource(R.string.settings_model_delete_confirm_yes), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    pendingCancelDownload?.let { workId ->
        CatalystAlertDialog(
            onDismissRequest = { pendingCancelDownload = null },
            title = { Text(stringResource(R.string.model_download_cancel_confirm_title)) },
            dismissButton = { TextButton(onClick = { pendingCancelDownload = null }) { Text(stringResource(R.string.settings_model_delete_confirm_no)) } },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelDownload(workId)
                    pendingCancelDownload = null
                }) { Text(stringResource(R.string.model_download_cancel_confirm_ok)) }
            },
        )
    }
    pendingNetworkDownload?.let { (kind, warning) ->
        CatalystAlertDialog(
            onDismissRequest = { pendingNetworkDownload = null },
            title = { Text(stringResource(R.string.settings_model_download_network_warning_title)) },
            text = {
                Text(stringResource(
                    if (warning == ModelDownloadNetworkWarning.CELLULAR) R.string.settings_model_download_network_warning_cellular_format
                    else R.string.settings_model_download_network_warning_unknown_format,
                    kind.displayName,
                ))
            },
            dismissButton = { TextButton(onClick = { pendingNetworkDownload = null }) { Text(stringResource(R.string.settings_model_download_network_warning_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    pendingNetworkDownload = null
                    notificationPermissionGate { viewModel.download(kind) }
                }) { Text(stringResource(R.string.settings_model_download_network_warning_continue)) }
            },
        )
    }
}

/** Pinned action row shared by the list and detail pages. */
@Composable
internal fun ModelBottomBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
private fun LoadedModelBanner(entry: LocalModelEntry, busy: Boolean, onRelease: () -> Unit) {
    val context = LocalContext.current
    val config = entry.config.normalized()
    val bytes = remember(entry.id, entry.sizeBytes, entry.memoryMetadata, config) {
        LocalModelMemoryPolicy.estimate(entry, config).conservativeTotalBytes
    }
    val size = Formatter.formatFileSize(context, bytes)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.model_loaded_banner), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                Text(modelManagerDisplayName(entry), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (config.idleUnloadMinutes > 0) stringResource(R.string.model_loaded_summary, size, config.idleUnloadMinutes)
                    else stringResource(R.string.model_loaded_summary_no_idle, size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
            OutlinedButton(onClick = onRelease, enabled = !busy, modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Text(stringResource(R.string.model_release_memory))
            }
        }
    }
}

@Composable
private fun ModelGroupHeading(title: String, hint: String) {
    Column(Modifier.padding(start = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun modelDescription(entry: LocalModelEntry): String = when (entry.kind) {
    LlmModelKind.SAKURA_1_5B_Q4 -> stringResource(R.string.model_desc_sakura)
    LlmModelKind.HY_MT2_1_8B_Q4_K_M -> stringResource(R.string.model_desc_hy_mt2)
    LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M,
    LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M -> stringResource(R.string.model_desc_index)
    LlmModelKind.QWEN3_0_6B_Q8_0 -> stringResource(R.string.model_desc_qwen3)
    null -> stringResource(if (entry.config.translationPrompt.isNotBlank()) R.string.model_desc_imported_prompt else R.string.model_desc_imported)
    else -> ""
}

@Composable
private fun ManagedModelCard(
    entry: LocalModelEntry,
    selected: Boolean,
    busy: Boolean,
    useEnabled: Boolean,
    download: WorkInfo?,
    downloadId: UUID?,
    queued: Boolean,
    onUse: () -> Unit,
    onTrySample: () -> Unit,
    onDetails: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val size = Formatter.formatFileSize(context, entry.sizeBytes)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top) {
                    Text(modelManagerDisplayName(entry), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(size, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                modelDescription(entry).takeIf(String::isNotBlank)?.let { description ->
                    Text(description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        entry.installed && selected -> Surface(shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.align(Alignment.CenterVertically)) {
                            Text(stringResource(R.string.model_in_use), style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        entry.installed -> Button(onClick = onUse, enabled = !busy && useEnabled,
                            modifier = Modifier.height(36.dp).align(Alignment.CenterVertically), shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)) {
                            Text(stringResource(R.string.model_manager_use))
                        }
                        downloadId != null -> OutlinedButton(onClick = onCancel, modifier = Modifier.height(36.dp).align(Alignment.CenterVertically),
                            shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text(stringResource(R.string.model_download_cancel))
                        }
                        entry.kind != null -> OutlinedButton(onClick = onDownload, enabled = !busy && useEnabled && !queued,
                            modifier = Modifier.height(36.dp).align(Alignment.CenterVertically), shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Text(if (queued) stringResource(R.string.settings_paddle_btn_processing)
                                else stringResource(R.string.model_download_size, size))
                        }
                    }
                    IconButton(
                        onClick = onTrySample,
                        enabled = !busy && useEnabled && entry.installed,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(Icons.Outlined.Science, contentDescription = stringResource(R.string.model_trial_title))
                    }
                    IconButton(onClick = onDetails, enabled = !busy) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.main_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            download?.let { info ->
                val active = info.state == WorkInfo.State.RUNNING &&
                    info.progress.getBoolean(ModelDownloadWorker.KEY_ACTIVE, true)
                if (active) {
                    ModelDownloadProgressLine(
                        downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0L),
                        total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, -1L),
                        bytesPerSecond = info.progress.getLong(ModelDownloadWorker.KEY_SPEED, 0L),
                        active = true,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                } else {
                    Text(stringResource(R.string.model_download_waiting), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelTabs(tabs: List<ModelDetailTab>, selected: ModelDetailTab, onSelect: (ModelDetailTab) -> Unit) {
    if (tabs.isEmpty()) return
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    SecondaryTabRow(selectedTabIndex = selectedIndex) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(tab.label)) },
            )
        }
    }
}

@Composable
private fun ModelSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    hint: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    ModelSliderRow(
        label = label,
        value = value.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        step = step.toFloat(),
        hint = hint,
        enabled = enabled,
        valueText = value.toString(),
    ) { onChange(it.roundToInt()) }
}

@Composable
private fun ModelSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    hint: String,
    enabled: Boolean,
    valueText: String = modelSamplingValueLabel(value),
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { raw -> onChange(modelSliderValue(raw, range, step)) },
            valueRange = range,
            enabled = enabled,
        )
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun modelSliderValue(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float =
    ((raw / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)

internal fun modelSamplingValueLabel(value: Float): String =
    String.format(Locale.ROOT, "%.2f", value).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelDetailScreen(
    entry: LocalModelEntry,
    settings: Settings,
    busy: Boolean,
    operationBusy: Boolean,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    onSave: (LocalModelConfig, () -> Unit) -> Unit,
    onTrySample: (LocalModelConfig) -> Unit,
    loadDefaults: suspend () -> LocalModelConfig,
    onDelete: () -> Unit,
) {
    var draft by rememberSaveable(entry.id, entry.config.fingerprint(), stateSaver = ModelConfigDraft.saver) {
        mutableStateOf(ModelConfigDraft.from(entry.config))
    }
    var defaults by remember(entry.id) { mutableStateOf<LocalModelConfig?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    var confirmUnlock by remember { mutableStateOf(false) }
    var confirmRestoreOfficial by remember { mutableStateOf(false) }
    // Follows the draft, so unlocking shows the prompt tab before saving.
    val locked = entry.specialized && !draft.unlocked
    val tabs = remember(locked) { ModelDetailTab.forModel(locked) }
    var selectedTab by rememberSaveable(entry.id) { mutableStateOf(tabs.first()) }
    LaunchedEffect(tabs) { if (selectedTab !in tabs) selectedTab = tabs.first() }
    val config = draft.parse()
    val changed = config?.normalized() != entry.config.normalized()
    val memoryEstimate = remember(entry.sizeBytes, entry.memoryMetadata, config) {
        config?.let { LocalModelMemoryPolicy.estimate(entry, it) }
    }
    val context = LocalContext.current
    val availableMemory = remember {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        memory.availMem
    }
    val cores = remember { CpuThreadPolicy.availableProcessors().coerceIn(1, 64) }
    val automaticThreads = remember(cores) {
        LocalLlmThreadPolicy.configured(cores, BuildConfig.LOCAL_LLM_GENERATION_THREADS, null)
    }
    val samplingDefaults = remember(entry.kind) {
        LocalLlmSamplingPolicy.forModel(entry.kind ?: LlmModelKind.QWEN3_0_6B_Q8_0, imported = entry.kind == null)
    }
    LaunchedEffect(entry.id) { defaults = runCatching { loadDefaults() }.getOrNull() }
    fun requestBack() {
        if (operationBusy) return
        if (changed) confirmBack = true else onBack()
    }
    fun resetTab() {
        val reset = defaults?.let { ModelConfigDraft.from(it) } ?: return
        draft = when (selectedTab) {
            ModelDetailTab.PROMPT -> draft.copy(prompt = reset.prompt, includeContext = reset.includeContext)
            ModelDetailTab.GENERATION -> draft.copy(context = reset.context, output = reset.output,
                temperature = reset.temperature, topP = reset.topP, topK = reset.topK, repeatPenalty = reset.repeatPenalty)
            ModelDetailTab.LOADING -> reset.copy(context = draft.context, output = draft.output, prompt = draft.prompt,
                temperature = draft.temperature, topP = draft.topP, topK = draft.topK, repeatPenalty = draft.repeatPenalty,
                includeContext = draft.includeContext)
            ModelDetailTab.INFO -> draft
        }
    }
    BackHandler { requestBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(modelManagerDisplayName(entry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(when {
                            entry.specialized && draft.unlocked -> R.string.model_subtitle_specialized_unlocked
                            entry.specialized -> R.string.model_subtitle_specialized
                            entry.kind == null -> R.string.model_subtitle_imported
                            else -> R.string.model_subtitle_general
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (entry.installed || entry.kind == null) {
                        IconButton(onClick = onDelete, enabled = !busy) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.llm_delete_button),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectedTab != ModelDetailTab.INFO) ModelBottomBar {
                if (selectedTab == ModelDetailTab.PROMPT) {
                    OutlinedButton(onClick = { config?.let(onTrySample) }, enabled = !busy && config != null && entry.installed,
                        modifier = Modifier.height(52.dp), shape = RoundedCornerShape(26.dp)) {
                        Text(stringResource(R.string.model_try_sample))
                    }
                } else {
                    OutlinedButton(onClick = ::resetTab, enabled = !busy && defaults != null,
                        modifier = Modifier.height(52.dp), shape = RoundedCornerShape(26.dp)) {
                        Text(stringResource(R.string.settings_dictionary_prompt_reset))
                    }
                }
                Button(onClick = { config?.let { onSave(it, {}) } }, enabled = !busy && config != null && changed,
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) {
                    Text(stringResource(R.string.settings_save_btn))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ModelTabs(tabs, selectedTab) { selectedTab = it }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (selectedTab) {
                    // Unlocked translation model: the official template stays read-only; only extra requirements are edited.
                    ModelDetailTab.PROMPT -> if (entry.specialized && entry.kind != null) {
                        item(key = "official-template") {
                            val template = remember(entry.kind, settings.sourceLang, settings.targetLang) {
                                OfficialPromptTemplate.render(entry.kind, settings.sourceLang, settings.targetLang,
                                    "{" + context.getString(R.string.model_prompt_insert_text) + "}")
                            }
                            SectionCard(title = null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.model_official_template), style = MaterialTheme.typography.titleSmall)
                                }
                                template?.let {
                                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                        Text(it, Modifier.fillMaxWidth().padding(12.dp),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item(key = "extra-requirements") {
                            SectionCard(title = null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.model_extra_requirements), Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall)
                                    TextButton(enabled = !busy && draft.prompt.isNotEmpty(), onClick = { draft = draft.copy(prompt = "") }) {
                                        Text(stringResource(R.string.model_extra_clear))
                                    }
                                }
                                OutlinedTextField(
                                    value = draft.prompt, onValueChange = { draft = draft.copy(prompt = it) },
                                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = !busy,
                                )
                                Text(stringResource(R.string.model_extra_requirements_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        item(key = "prompt") {
                            SectionCard(title = null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.model_manager_translation_prompt), Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall)
                                    TextButton(enabled = !busy && defaults != null, onClick = ::resetTab) {
                                        Text(stringResource(R.string.settings_dictionary_prompt_reset))
                                    }
                                }
                                OutlinedTextField(
                                    value = draft.prompt, onValueChange = { draft = draft.copy(prompt = it) },
                                    modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12, enabled = !busy,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(R.string.model_prompt_insert_text to "{text}",
                                        R.string.model_prompt_insert_target to "{target}",
                                        R.string.model_prompt_insert_source to "{source}").forEach { (label, variable) ->
                                        OutlinedButton(enabled = !busy, onClick = {
                                            val current = draft.prompt.trimEnd()
                                            draft = draft.copy(prompt = if (current.isEmpty()) variable else current + "\n" + variable)
                                        }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp)) {
                                            Text(stringResource(R.string.model_insert_variable, stringResource(label)),
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                                Text(stringResource(R.string.model_prompt_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = "include-context") {
                            SectionCard(title = null) {
                                SwitchRow(stringResource(R.string.model_include_context), draft.includeContext, enabled = !busy,
                                    helpText = stringResource(R.string.model_include_context_hint)) {
                                    draft = draft.copy(includeContext = it)
                                }
                            }
                        }
                        item(key = "preview") {
                            val sourceLang = ModelTrialPolicy.settings(settings, entry).sourceLang
                            val sample = ModelTrialPolicy.sample(sourceLang)
                            val preview = remember(draft.prompt, sample, settings.targetLang, sourceLang) {
                                val requestSettings = settings.copy(sourceLang = sourceLang,
                                    runtimeTranslationPromptContext = com.gameocr.app.data.RuntimeTranslationPromptContext(),
                                    runtimeLocalModelConfig = LocalModelConfig(translationPrompt = draft.prompt))
                                ManagedLocalModelRequestPolicy.withInstructions(
                                    GenericLocalPromptPolicy.build(sample, requestSettings), requestSettings, sample)
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.model_prompt_preview, sample, Languages.nameOf(context, settings.targetLang)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 10, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    ModelDetailTab.GENERATION -> {
                        if (locked) item(key = "locked") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(stringResource(R.string.model_locked_title), style = MaterialTheme.typography.titleSmall)
                                            Text(stringResource(R.string.model_locked_hint), style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    // Turning it on asks for confirmation first; see the dialog below.
                                    SwitchRow(stringResource(R.string.model_unlock_title), checked = false, enabled = !busy,
                                        helpText = stringResource(R.string.model_unlock_hint)) { if (it) confirmUnlock = true }
                                }
                            }
                        } else if (entry.specialized) item(key = "unlocked") {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(stringResource(R.string.model_unlocked_title), style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(R.string.model_unlocked_hint), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(enabled = !busy, onClick = { confirmRestoreOfficial = true }) {
                                        Text(stringResource(R.string.model_restore_official))
                                    }
                                }
                            }
                        }
                        item(key = "lengths") {
                            val contextTokens = draft.context.toIntOrNull() ?: 2048
                            SectionCard(title = null) {
                                ModelSliderRow(modelParameterLabel(stringResource(R.string.settings_llm_context_size, 0)),
                                    contextTokens, 512..4096, 512, stringResource(R.string.model_context_reinit_hint), !busy) { value ->
                                    val output = (draft.output.toIntOrNull() ?: 256).coerceAtMost(value - 8)
                                    draft = draft.copy(context = value.toString(), output = output.toString())
                                }
                                ModelSliderRow(modelParameterLabel(stringResource(R.string.settings_llm_max_new_tokens, 0)),
                                    draft.output.toIntOrNull() ?: 256, 16..minOf(4096, contextTokens - 8), 16,
                                    stringResource(R.string.model_output_hint), !busy) { value ->
                                    draft = draft.copy(output = value.toString())
                                }
                            }
                        }
                        item(key = "sampling") {
                            SectionCard(title = null) {
                                val samplingEnabled = !busy && !locked
                                ModelSliderRow(
                                    modelParameterLabel(stringResource(R.string.settings_openai_temperature_format, 0f)),
                                    if (locked) samplingDefaults.temperature else draft.temperature.toFloatOrNull()?.takeIf(Float::isFinite) ?: samplingDefaults.temperature,
                                    0f..2f, 0.01f, stringResource(R.string.model_temperature_hint), samplingEnabled,
                                ) { draft = draft.copy(temperature = modelSamplingValueLabel(it)) }
                                ModelSliderRow(
                                    modelParameterLabel(stringResource(R.string.settings_openai_top_p_format, 0f)),
                                    if (locked) samplingDefaults.topP else draft.topP.toFloatOrNull()?.takeIf(Float::isFinite) ?: samplingDefaults.topP,
                                    0.01f..1f, 0.01f, stringResource(R.string.model_top_p_hint), samplingEnabled,
                                ) { draft = draft.copy(topP = modelSamplingValueLabel(it)) }
                                ModelSliderRow(
                                    "top_k", if (locked) samplingDefaults.topK else draft.topK.toIntOrNull() ?: samplingDefaults.topK,
                                    0..200, 1, stringResource(R.string.model_top_k_hint), samplingEnabled,
                                ) { draft = draft.copy(topK = it.toString()) }
                                ModelSliderRow(
                                    "repeat_penalty",
                                    if (locked) samplingDefaults.repeatPenalty else draft.repeatPenalty.toFloatOrNull()?.takeIf(Float::isFinite) ?: samplingDefaults.repeatPenalty,
                                    0.5f..2f, 0.01f, stringResource(R.string.model_repeat_penalty_hint), samplingEnabled,
                                ) { draft = draft.copy(repeatPenalty = modelSamplingValueLabel(it)) }
                            }
                        }
                    }
                    ModelDetailTab.LOADING -> {
                        item(key = "memory") {
                            SectionCard(title = null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.model_memory_estimate_title), Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall)
                                    memoryEstimate?.let {
                                        Text(Formatter.formatFileSize(context, it.conservativeTotalBytes),
                                            style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                                memoryEstimate?.let { estimate ->
                                    val total = estimate.conservativeTotalBytes.coerceAtLeast(1L).toFloat()
                                    val weights = (estimate.weightsBytes / total).coerceIn(0f, 1f)
                                    val kv = (estimate.kvBytes / total).coerceIn(0f, 1f - weights)
                                    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        if (weights > 0f) Box(Modifier.weight(weights).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                                        if (kv > 0f) Box(Modifier.weight(kv).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                                        if (1f - weights - kv > 0f) Spacer(Modifier.weight(1f - weights - kv))
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        MemoryLegend(MaterialTheme.colorScheme.primary,
                                            stringResource(R.string.model_memory_weights, Formatter.formatFileSize(context, estimate.weightsBytes)))
                                        MemoryLegend(MaterialTheme.colorScheme.outline,
                                            stringResource(R.string.model_memory_kv, Formatter.formatFileSize(context, estimate.kvBytes)))
                                    }
                                }
                                if (availableMemory > 0) Text(stringResource(R.string.model_memory_available,
                                    Formatter.formatFileSize(context, availableMemory)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = "memory-options") {
                            SectionCard(title = null) {
                                val idle = draft.idleUnloadMinutes.toIntOrNull() ?: 5
                                SettingsOptionDropdown(
                                    label = stringResource(R.string.model_memory_idle), value = idle,
                                    options = (IDLE_MINUTE_OPTIONS + idle).distinct().sorted(),
                                    optionLabel = { minutes -> if (minutes == 0) stringResource(R.string.model_idle_never)
                                        else stringResource(R.string.model_idle_minutes, minutes) },
                                    optionEnabled = { !busy }, compactRow = true,
                                    onValueChange = { draft = draft.copy(idleUnloadMinutes = it.toString()) },
                                )
                                SwitchRow(stringResource(R.string.model_memory_mmap), draft.useMmap, enabled = !busy,
                                    helpText = stringResource(R.string.model_mmap_hint)) {
                                    draft = draft.copy(useMmap = it)
                                }
                                SwitchRow(stringResource(R.string.model_memory_mlock), draft.useMlock, enabled = !busy) {
                                    draft = draft.copy(useMlock = it)
                                }
                            }
                        }
                        item(key = "initialization") {
                            val threadOptions = listOf("") + (1..cores).map(Int::toString)
                            val contextTokens = draft.context.toIntOrNull() ?: 2048
                            val batch = draft.batchTokens.toIntOrNull() ?: 512
                            SectionCard(title = stringResource(R.string.model_advanced_init)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SettingsOptionDropdown(stringResource(R.string.model_init_generation_threads), draft.generationThreads,
                                        threadOptions, { if (it.isBlank()) stringResource(R.string.model_threads_auto, automaticThreads.generationThreads) else it },
                                        { !busy }, modifier = Modifier.weight(1f)) { draft = draft.copy(generationThreads = it) }
                                    SettingsOptionDropdown(stringResource(R.string.model_init_prompt_threads), draft.promptThreads,
                                        threadOptions, { if (it.isBlank()) stringResource(R.string.model_threads_auto, automaticThreads.promptThreads) else it },
                                        { !busy }, modifier = Modifier.weight(1f)) { draft = draft.copy(promptThreads = it) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SettingsOptionDropdown(stringResource(R.string.model_init_batch), batch,
                                        (BATCH_OPTIONS.filter { it <= contextTokens } + batch).distinct().sorted(), { it.toString() },
                                        { !busy && it <= contextTokens }, modifier = Modifier.weight(1f)) { value ->
                                        val micro = (draft.microBatchTokens.toIntOrNull() ?: value).coerceAtMost(value)
                                        draft = draft.copy(batchTokens = value.toString(), microBatchTokens = micro.toString())
                                    }
                                    val micro = draft.microBatchTokens.toIntOrNull() ?: 512
                                    SettingsOptionDropdown(stringResource(R.string.model_init_micro_batch), micro,
                                        (BATCH_OPTIONS.filter { it <= batch } + micro).distinct().sorted(), { it.toString() },
                                        { !busy && it <= batch }, modifier = Modifier.weight(1f)) { draft = draft.copy(microBatchTokens = it.toString()) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SettingsOptionDropdown(stringResource(R.string.model_init_cache_k), draft.cacheTypeK,
                                        ModelConfigDraft.CACHE_TYPES, { it }, { !busy }, modifier = Modifier.weight(1f)) {
                                        draft = draft.copy(cacheTypeK = it)
                                    }
                                    SettingsOptionDropdown(stringResource(R.string.model_init_cache_v), draft.cacheTypeV,
                                        ModelConfigDraft.CACHE_TYPES, { it }, { !busy && draft.canSelectCacheV(it) },
                                        modifier = Modifier.weight(1f)) { draft = draft.copy(cacheTypeV = it) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SettingsOptionDropdown(stringResource(R.string.model_init_flash), draft.flashAttention,
                                        ModelConfigDraft.FLASH_ATTENTION_OPTIONS, { mode -> stringResource(when (mode) {
                                            "on" -> R.string.main_status_enabled
                                            "off" -> R.string.main_status_disabled
                                            else -> R.string.settings_image_detail_auto
                                        }) }, { !busy }, modifier = Modifier.weight(1f)) { draft = draft.withFlashAttention(it) }
                                    val gpu = draft.gpuLayers.toIntOrNull() ?: 0
                                    SettingsOptionDropdown(stringResource(R.string.model_init_gpu_layers), gpu,
                                        (GPU_LAYER_OPTIONS + gpu).distinct(), { layers -> when (layers) {
                                            0 -> stringResource(R.string.model_gpu_cpu_only)
                                            -1 -> stringResource(R.string.model_gpu_all)
                                            else -> layers.toString()
                                        } }, { !busy }, modifier = Modifier.weight(1f)) { draft = draft.copy(gpuLayers = it.toString()) }
                                }
                                Text(stringResource(R.string.model_init_experimental_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ModelDetailTab.INFO -> item(key = "info") {
                        SectionCard(title = stringResource(R.string.model_manager_information)) {
                            Text(entry.file?.name ?: entry.kind?.fileName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Text(listOf(entry.architecture, entry.quantization, Formatter.formatFileSize(context, entry.sizeBytes))
                                .filter { it.isNotBlank() }.joinToString("  "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (confirmUnlock) {
        CatalystAlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text(stringResource(R.string.model_unlock_title)) },
            text = { Text(stringResource(R.string.model_unlock_confirm_message)) },
            dismissButton = {
                TextButton(onClick = { confirmUnlock = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(unlocked = true)
                    confirmUnlock = false
                }) { Text(stringResource(R.string.model_unlock_confirm)) }
            },
        )
    }
    if (confirmRestoreOfficial) {
        CatalystAlertDialog(
            onDismissRequest = { confirmRestoreOfficial = false },
            title = { Text(stringResource(R.string.model_restore_official_confirm_title)) },
            dismissButton = {
                TextButton(onClick = { confirmRestoreOfficial = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    draft = draft.restoreOfficialOverrides()
                    confirmRestoreOfficial = false
                }) { Text(stringResource(R.string.model_restore_official_confirm)) }
            },
        )
    }
    if (confirmBack) {
        CatalystAlertDialog(
            onDismissRequest = { confirmBack = false },
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_unsaved_msg)) },
            dismissButton = {
                TextButton(onClick = { confirmBack = false; onBack() }) { Text(stringResource(R.string.settings_unsaved_discard)) }
                TextButton(onClick = { confirmBack = false }) { Text(stringResource(R.string.settings_unsaved_keep_editing)) }
            },
            confirmButton = {
                TextButton(enabled = config != null && !busy, onClick = {
                    config?.let { onSave(it) { confirmBack = false; onBack() } }
                }) { Text(stringResource(R.string.settings_unsaved_save)) }
            },
        )
    }
}

private val IDLE_MINUTE_OPTIONS = listOf(0, 1, 3, 5, 10, 30, 60)
private val BATCH_OPTIONS = listOf(32, 64, 128, 256, 512, 1024, 2048)
private val GPU_LAYER_OPTIONS = listOf(0, 8, 16, 32, -1)

@Composable
private fun MemoryLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A locked translation model has no prompt tab; unlocking adds it for extra requirements. */
internal enum class ModelDetailTab(val label: Int) {
    PROMPT(R.string.model_tab_prompt),
    GENERATION(R.string.model_tab_generation),
    LOADING(R.string.model_tab_loading),
    INFO(R.string.model_tab_info);

    companion object {
        fun forModel(locked: Boolean): List<ModelDetailTab> =
            if (locked) listOf(GENERATION, LOADING, INFO) else entries.toList()
    }
}

@Composable
private fun ModelNumericField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    valid: Boolean,
    busy: Boolean,
    integer: Boolean = false,
    placeholder: String? = null,
    signed: Boolean = false,
) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, isError = !valid,
        placeholder = { if (placeholder != null) Text(placeholder) },
        // The placeholder only shows while focused; keep the default visible at all times.
        supportingText = placeholder?.let { default -> { Text(stringResource(R.string.model_param_default_hint, default)) } },
        keyboardOptions = KeyboardOptions(keyboardType = when {
            signed -> KeyboardType.Ascii
            integer -> KeyboardType.Number
            else -> KeyboardType.Decimal
        }))
}

/** Pure draft parsing makes blank defaults, malformed values, and finite/range boundaries testable. */
internal fun ModelConfigDraft.restoreOfficialOverrides(): ModelConfigDraft =
    copy(unlocked = false, prompt = "", temperature = "", topP = "", topK = "", repeatPenalty = "")

internal data class ModelConfigDraft(
    val context: String,
    val output: String,
    val prompt: String,
    val temperature: String,
    val topP: String,
    val topK: String,
    val repeatPenalty: String,
    val generationThreads: String = "",
    val promptThreads: String = "",
    val batchTokens: String = "512",
    val microBatchTokens: String = "512",
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val flashAttention: String = "auto",
    val gpuLayers: String = "0",
    val idleUnloadMinutes: String = "5",
    val includeContext: Boolean = true,
    val unlocked: Boolean = false,
) {
    fun validContext() = context.toIntOrNull()?.let { it in 512..4096 } == true
    fun validOutput() = output.toIntOrNull()?.let { it in 16..minOf(4096, (context.toIntOrNull() ?: 0) - 8) } == true
    fun validTemperature() = optionalFloatValid(temperature, 0f..2f)
    fun validTopP() = optionalFloatValid(topP, 0.01f..1f)
    fun validTopK() = topK.isBlank() || topK.toIntOrNull()?.let { it in 0..200 } == true
    fun validRepeatPenalty() = optionalFloatValid(repeatPenalty, 0.5f..2f)
    fun validGenerationThreads() = optionalIntegerValid(generationThreads, 1..64)
    fun validPromptThreads() = optionalIntegerValid(promptThreads, 1..64)
    fun validBatchTokens() = batchTokens.toIntOrNull()?.let { it in 32..2048 && it <= (context.toIntOrNull() ?: 0) } == true
    fun validMicroBatchTokens() = microBatchTokens.toIntOrNull()?.let { it in 32..2048 && it <= (batchTokens.toIntOrNull() ?: 0) } == true
    fun validGpuLayers() = gpuLayers.toIntOrNull()?.let { it in -1..999 } == true
    fun validIdleUnloadMinutes() = idleUnloadMinutes.toIntOrNull()?.let { it in 0..60 } == true
    fun gpuEnabled() = gpuLayers.toIntOrNull() != 0
    fun withGpuEnabled(enabled: Boolean) = copy(gpuLayers = if (enabled) "-1" else "0")
    fun canSelectCacheV(value: String) = value in CACHE_TYPES && (flashAttention != "off" || value == "f16")
    fun withFlashAttention(mode: String) = copy(flashAttention = mode, cacheTypeV = if (mode == "off") "f16" else cacheTypeV)

    fun parse(): LocalModelConfig? {
        if (!validContext() || !validOutput() || !validTemperature() || !validTopP() || !validTopK() || !validRepeatPenalty() ||
            !validGenerationThreads() || !validPromptThreads() || !validBatchTokens() || !validMicroBatchTokens() ||
            !validGpuLayers() || !validIdleUnloadMinutes() || cacheTypeK !in CACHE_TYPES || cacheTypeV !in CACHE_TYPES ||
            flashAttention !in FLASH_ATTENTION_OPTIONS || (flashAttention == "off" && cacheTypeV != "f16")) return null
        return LocalModelConfig(
            contextTokens = context.toInt(), maxNewTokens = output.toInt(), translationPrompt = prompt,
            temperature = temperature.takeIf { it.isNotBlank() }?.toFloat(), topP = topP.takeIf { it.isNotBlank() }?.toFloat(),
            topK = topK.takeIf { it.isNotBlank() }?.toInt(), repeatPenalty = repeatPenalty.takeIf { it.isNotBlank() }?.toFloat(),
            generationThreads = generationThreads.takeIf { it.isNotBlank() }?.toInt(),
            promptThreads = promptThreads.takeIf { it.isNotBlank() }?.toInt(),
            batchTokens = batchTokens.toInt(), microBatchTokens = microBatchTokens.toInt(),
            useMmap = useMmap, useMlock = useMlock, cacheTypeK = cacheTypeK, cacheTypeV = cacheTypeV,
            flashAttention = flashAttention, gpuLayers = gpuLayers.toInt(), idleUnloadMinutes = idleUnloadMinutes.toInt(),
            includeContext = includeContext, unlocked = unlocked,
        )
    }

    fun serialize(): List<String> = listOf(context, output, prompt, temperature, topP, topK, repeatPenalty,
        generationThreads, promptThreads, batchTokens, microBatchTokens, useMmap.toString(), useMlock.toString(),
        cacheTypeK, cacheTypeV, flashAttention, gpuLayers, idleUnloadMinutes, includeContext.toString(), unlocked.toString())

    companion object {
        val CACHE_TYPES = listOf("f16", "q8_0", "q4_0")
        val FLASH_ATTENTION_OPTIONS = listOf("auto", "on", "off")
        val saver = listSaver<ModelConfigDraft, String>(
            save = { it.serialize() },
            restore = { restore(it) },
        )
        fun restore(values: List<String>): ModelConfigDraft? {
            // 18 or 19 values: state saved before the context or unlock options existed.
            if (values.size !in 18..20) return null
            val includeContext = values.getOrNull(18)?.let { it.toBooleanStrictOrNull() ?: return null } ?: true
            val unlocked = values.getOrNull(19)?.let { it.toBooleanStrictOrNull() ?: return null } ?: false
            val mmap = values[11].toBooleanStrictOrNull() ?: return null
            val mlock = values[12].toBooleanStrictOrNull() ?: return null
            return ModelConfigDraft(values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                values[7], values[8], values[9], values[10], mmap, mlock, values[13], values[14], values[15], values[16], values[17],
                includeContext, unlocked)
        }
        fun from(config: LocalModelConfig) = ModelConfigDraft(
            config.contextTokens.toString(), config.maxNewTokens.toString(), config.translationPrompt,
            config.temperature?.toString().orEmpty(), config.topP?.toString().orEmpty(),
            config.topK?.toString().orEmpty(), config.repeatPenalty?.toString().orEmpty(),
            config.generationThreads?.toString().orEmpty(), config.promptThreads?.toString().orEmpty(),
            config.batchTokens.toString(), config.microBatchTokens.toString(), config.useMmap, config.useMlock,
            config.cacheTypeK, config.cacheTypeV, config.flashAttention, config.gpuLayers.toString(), config.idleUnloadMinutes.toString(),
            config.includeContext, config.unlocked,
        )

        private fun optionalIntegerValid(raw: String, range: IntRange): Boolean =
            raw.isBlank() || raw.toIntOrNull()?.let { it in range } == true

        private fun optionalFloatValid(raw: String, range: ClosedFloatingPointRange<Float>): Boolean =
            raw.isBlank() || raw.toFloatOrNull()?.let { it.isFinite() && it in range } == true
    }
}

internal fun modelParameterLabel(formatted: String): String = formatted.substringBefore(':').substringBefore('：')

/** Quantization has its own metadata line; leave user-owned imported names untouched. */
internal fun modelManagerDisplayName(entry: LocalModelEntry): String =
    if (entry.kind != null) entry.name.substringBefore(" (") else entry.name

internal fun modelManagerDownloads(infos: List<WorkInfo>): Map<LlmModelKind, WorkInfo> = buildMap {
    infos.filterNot { it.state.isFinished }.forEach { info ->
        val specs = ModelDownloadSpec.decodeAll(info.progress.getStringArray(ModelDownloadWorker.KEY_SPECS).orEmpty()).orEmpty()
        specs.filter { it.type == ModelDownloadType.LLM }.forEach { spec ->
            LlmModelKind.entries.firstOrNull { it.name == spec.variant }?.let { put(it, info) }
        }
    }
}
