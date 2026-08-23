package com.gameocr.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.appcontext.ForegroundApp
import com.gameocr.app.appcontext.SelectableApp
import com.gameocr.app.glossary.GLOSSARY_IMPORT_MAX_FILE_BYTES
import com.gameocr.app.glossary.GlossaryImportCommitResult
import com.gameocr.app.glossary.GlossaryImportConflictPolicy
import com.gameocr.app.glossary.GlossaryImportDocument
import com.gameocr.app.glossary.GlossaryImportFormatException
import com.gameocr.app.glossary.GlossaryImportJson
import com.gameocr.app.glossary.GlossaryImportOptions
import com.gameocr.app.glossary.GlossaryImportPreview
import com.gameocr.app.glossary.GlossaryImportPreviewPolicy
import com.gameocr.app.glossary.GlossaryImportPreviewRow
import com.gameocr.app.glossary.GlossaryImportRowStatus
import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class GlossaryAddConflict(
    val pending: GlossaryTermEntity,
    val existing: GlossaryTermEntity,
)

internal object GlossaryImportPreviewStatusPolicy {
    fun showsTrailingStatus(status: GlossaryImportRowStatus): Boolean =
        status != GlossaryImportRowStatus.READY
}

internal enum class GlossaryImportPreviewFilter {
    ALL,
    IMPORTABLE,
    OVERWRITE,
    SKIPPED,
    PROBLEM,
}

internal object GlossaryImportPreviewFilterPolicy {
    fun matches(
        filter: GlossaryImportPreviewFilter,
        row: GlossaryImportPreviewRow,
    ): Boolean = when (filter) {
        GlossaryImportPreviewFilter.ALL -> true
        GlossaryImportPreviewFilter.IMPORTABLE -> row.willImport
        GlossaryImportPreviewFilter.OVERWRITE ->
            row.status == GlossaryImportRowStatus.WILL_OVERWRITE
        GlossaryImportPreviewFilter.SKIPPED ->
            row.status == GlossaryImportRowStatus.WILL_SKIP ||
                row.status == GlossaryImportRowStatus.DUPLICATE ||
                row.status == GlossaryImportRowStatus.DESELECTED
        GlossaryImportPreviewFilter.PROBLEM ->
            row.status == GlossaryImportRowStatus.FILE_CONFLICT ||
                row.status == GlossaryImportRowStatus.EMPTY_SOURCE ||
                row.status == GlossaryImportRowStatus.EMPTY_TARGET ||
                row.status == GlossaryImportRowStatus.SOURCE_TOO_LONG ||
                row.status == GlossaryImportRowStatus.TARGET_TOO_LONG
    }

    fun rows(
        preview: GlossaryImportPreview,
        filter: GlossaryImportPreviewFilter,
    ): List<GlossaryImportPreviewRow> = preview.rows.filter { matches(filter, it) }

    fun count(
        preview: GlossaryImportPreview,
        filter: GlossaryImportPreviewFilter,
    ): Int = preview.rows.count { matches(filter, it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlossaryAddScreen(
    currentApp: ForegroundApp?,
    selectableApps: List<SelectableApp>,
    appsLoading: Boolean,
    defaultSourceLang: String,
    defaultTargetLang: String,
    viewModel: GlossaryViewModel,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.glossary_add_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        GlossarySingleAddPane(
            modifier = Modifier.fillMaxSize().padding(padding),
            currentApp = currentApp,
            selectableApps = selectableApps,
            appsLoading = appsLoading,
            defaultSourceLang = defaultSourceLang,
            defaultTargetLang = defaultTargetLang,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onSaved = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlossaryImportScreen(
    existingTerms: List<GlossaryTermEntity>,
    currentApp: ForegroundApp?,
    selectableApps: List<SelectableApp>,
    appsLoading: Boolean,
    defaultSourceLang: String,
    defaultTargetLang: String,
    viewModel: GlossaryViewModel,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.glossary_import_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        GlossaryBatchImportPane(
            modifier = Modifier.fillMaxSize().padding(padding),
            existingTerms = existingTerms,
            currentApp = currentApp,
            selectableApps = selectableApps,
            appsLoading = appsLoading,
            defaultSourceLang = defaultSourceLang,
            defaultTargetLang = defaultTargetLang,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
        )
    }
}

@Composable
private fun GlossarySingleAddPane(
    modifier: Modifier = Modifier,
    currentApp: ForegroundApp?,
    selectableApps: List<SelectableApp>,
    appsLoading: Boolean,
    defaultSourceLang: String,
    defaultTargetLang: String,
    viewModel: GlossaryViewModel,
    snackbarHostState: SnackbarHostState,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceTerm by remember { mutableStateOf("") }
    var targetTerm by remember { mutableStateOf("") }
    var options by rememberGlossaryImportOptions(
        currentApp = currentApp,
        defaultSourceLang = defaultSourceLang,
        defaultTargetLang = defaultTargetLang,
    )
    var selectedApp by remember { mutableStateOf<SelectableApp?>(null) }
    var scopeMode by remember { mutableStateOf(GlossaryScopeMode.GLOBAL) }
    var showAppPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pendingConflict by remember { mutableStateOf<GlossaryAddConflict?>(null) }
    val currentScopeApp = remember(currentApp) { currentApp?.toSelectableApp() }
    val scopedApp = GlossaryScopePolicy.scopedApp(scopeMode, currentScopeApp, selectedApp)
    val canSave = sourceTerm.isNotBlank() && targetTerm.isNotBlank() &&
        GlossaryScopePolicy.isValid(scopeMode, currentScopeApp, selectedApp)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = sourceTerm,
                        onValueChange = { sourceTerm = it },
                        label = { Text(stringResource(R.string.glossary_source_term)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = targetTerm,
                        onValueChange = { targetTerm = it },
                        label = { Text(stringResource(R.string.glossary_target_term)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
        item {
            GlossaryImportOptionsEditor(
                title = stringResource(R.string.glossary_term_settings),
                options = options,
                onOptionsChange = { options = it },
                currentApp = currentScopeApp,
                selectedApp = selectedApp,
                scopeMode = scopeMode,
                onScopeModeChange = { mode ->
                    scopeMode = mode
                    if (mode == GlossaryScopeMode.SELECTED_APP) showAppPicker = true
                },
                onSelectApp = { showAppPicker = true },
                showConflictPolicy = false,
            )
        }
        item {
            Button(
                enabled = canSave && !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val term = options.toTerm(sourceTerm, targetTerm, scopedApp)
                    saving = true
                    scope.launch {
                        runCatching {
                            val conflict = viewModel.findConflict(term)
                            if (conflict == null) {
                                viewModel.upsert(term)
                                onSaved()
                            } else {
                                pendingConflict = GlossaryAddConflict(term, conflict)
                            }
                        }.onFailure { error ->
                            snackbarHostState.showSnackbar(
                                error.message ?: context.getString(R.string.glossary_save_failed)
                            )
                        }
                        saving = false
                    }
                },
            ) {
                Text(stringResource(R.string.settings_save))
            }
        }
    }

    if (showAppPicker) {
        GlossaryAppPickerDialog(
            apps = selectableApps,
            isLoading = appsLoading,
            selectedPackage = selectedApp?.packageName,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                selectedApp = app
                scopeMode = GlossaryScopeMode.SELECTED_APP
                showAppPicker = false
            },
        )
    }
    pendingConflict?.let { conflict ->
        GlossaryConfirmationDialog(
            title = stringResource(R.string.glossary_duplicate_title),
            message = stringResource(
                R.string.glossary_duplicate_message,
                conflict.existing.sourceTerm,
                conflict.existing.targetTerm,
                conflict.pending.targetTerm,
            ),
            confirmLabel = stringResource(R.string.glossary_duplicate_overwrite),
            destructive = false,
            onDismiss = { pendingConflict = null },
            onConfirm = {
                pendingConflict = null
                saving = true
                scope.launch {
                    runCatching { viewModel.overwriteConflict(conflict.pending) }
                        .onSuccess { onSaved() }
                        .onFailure { error ->
                            snackbarHostState.showSnackbar(
                                error.message ?: context.getString(R.string.glossary_save_failed)
                            )
                        }
                    saving = false
                }
            },
        )
    }
}

@Composable
private fun GlossaryBatchImportPane(
    modifier: Modifier = Modifier,
    existingTerms: List<GlossaryTermEntity>,
    currentApp: ForegroundApp?,
    selectableApps: List<SelectableApp>,
    appsLoading: Boolean,
    defaultSourceLang: String,
    defaultTargetLang: String,
    viewModel: GlossaryViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<GlossaryImportDocument?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedPreviewFilter by remember { mutableStateOf(GlossaryImportPreviewFilter.ALL) }
    var options by rememberGlossaryImportOptions(
        currentApp = currentApp,
        defaultSourceLang = defaultSourceLang,
        defaultTargetLang = defaultTargetLang,
    )
    var selectedApp by remember { mutableStateOf<SelectableApp?>(null) }
    var scopeMode by remember { mutableStateOf(GlossaryScopeMode.GLOBAL) }
    var showAppPicker by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val currentScopeApp = remember(currentApp) { currentApp?.toSelectableApp() }
    val scopedApp = GlossaryScopePolicy.scopedApp(scopeMode, currentScopeApp, selectedApp)
    val effectiveOptions = options.copy(
        scopePackage = scopedApp?.packageName.orEmpty(),
        appLabel = scopedApp?.displayName.orEmpty(),
    )
    val preview = document?.let { parsed ->
        remember(parsed, existingTerms, effectiveOptions, selectedIndices) {
            GlossaryImportPreviewPolicy.preview(
                document = parsed,
                existingTerms = existingTerms,
                options = effectiveOptions,
                selectedIndices = selectedIndices,
            )
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readGlossaryImportFile(context, uri) }
                .onSuccess { loaded ->
                    fileName = loaded.first
                    document = loaded.second
                    selectedIndices = GlossaryImportPreviewPolicy.defaultSelection(loaded.second)
                    selectedPreviewFilter = GlossaryImportPreviewFilter.ALL
                }
                .onFailure { error ->
                    document = null
                    fileName = null
                    selectedIndices = emptySet()
                    selectedPreviewFilter = GlossaryImportPreviewFilter.ALL
                    snackbarHostState.showSnackbar(
                        if (error is GlossaryImportFormatException) {
                            context.getString(R.string.glossary_import_invalid_file)
                        } else {
                            error.message ?: context.getString(R.string.glossary_import_invalid_file)
                        }
                    )
                }
        }
    }

    val importPreview: (GlossaryImportPreview) -> Unit = { currentPreview ->
        val terms = GlossaryImportPreviewPolicy.buildTerms(
            preview = currentPreview,
            options = effectiveOptions,
        )
        importing = true
        scope.launch {
            runCatching {
                viewModel.importTerms(terms, effectiveOptions.conflictPolicy)
            }.onSuccess { result ->
                snackbarHostState.showSnackbar(context.importResultMessage(result))
                document = null
                fileName = null
                selectedIndices = emptySet()
                selectedPreviewFilter = GlossaryImportPreviewFilter.ALL
            }.onFailure { error ->
                snackbarHostState.showSnackbar(
                    error.message ?: context.getString(R.string.glossary_import_failed)
                )
            }
            importing = false
        }
    }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.glossary_import_file_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = fileName ?: stringResource(R.string.glossary_import_file_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { picker.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Text(
                            text = stringResource(R.string.glossary_import_choose_file),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.glossary_import_format_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            GlossaryImportOptionsEditor(
                title = stringResource(R.string.glossary_term_settings),
                options = options,
                onOptionsChange = { options = it },
                currentApp = currentScopeApp,
                selectedApp = selectedApp,
                scopeMode = scopeMode,
                onScopeModeChange = { mode ->
                    scopeMode = mode
                    if (mode == GlossaryScopeMode.SELECTED_APP) showAppPicker = true
                },
                onSelectApp = { showAppPicker = true },
                showConflictPolicy = true,
            )
        }
        if (document != null) {
            preview?.let { currentPreview ->
                item {
                    GlossaryImportPreviewTabs(
                        preview = currentPreview,
                        selectedFilter = selectedPreviewFilter,
                        onFilterSelected = { selectedPreviewFilter = it },
                    )
                }
                if (currentPreview.hasFileConflicts) {
                    item {
                        Text(
                            text = stringResource(R.string.glossary_import_resolve_conflicts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val filteredRows = GlossaryImportPreviewFilterPolicy.rows(
                    preview = currentPreview,
                    filter = selectedPreviewFilter,
                )
                if (filteredRows.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.glossary_import_filter_empty),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(filteredRows, key = { row -> row.entry.index }) { row ->
                    GlossaryImportPreviewCard(
                        row = row,
                        onSelectedChange = { checked ->
                            selectedIndices = if (checked) {
                                selectedIndices + row.entry.index
                            } else {
                                selectedIndices - row.entry.index
                            }
                        },
                    )
                }
            }
        }
        }
        preview?.let { currentPreview ->
            GlossaryImportBottomAction(
                preview = currentPreview,
                enabled = currentPreview.canImport && !importing &&
                    GlossaryScopePolicy.isValid(scopeMode, currentScopeApp, selectedApp),
                onImport = { importPreview(currentPreview) },
            )
        }
    }

    if (showAppPicker) {
        GlossaryAppPickerDialog(
            apps = selectableApps,
            isLoading = appsLoading,
            selectedPackage = selectedApp?.packageName,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                selectedApp = app
                scopeMode = GlossaryScopeMode.SELECTED_APP
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun GlossaryImportBottomAction(
    preview: GlossaryImportPreview,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Button(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            enabled = enabled,
            onClick = onImport,
        ) {
            Text(
                stringResource(
                    R.string.glossary_import_action_count,
                    preview.importableCount,
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlossaryImportOptionsEditor(
    title: String,
    options: GlossaryImportOptions,
    onOptionsChange: (GlossaryImportOptions) -> Unit,
    currentApp: SelectableApp?,
    selectedApp: SelectableApp?,
    scopeMode: GlossaryScopeMode,
    onScopeModeChange: (GlossaryScopeMode) -> Unit,
    onSelectApp: () -> Unit,
    showConflictPolicy: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            LanguagePicker(
                label = stringResource(R.string.glossary_source_language),
                currentCode = options.sourceLang,
                onSelect = { onOptionsChange(options.copy(sourceLang = it)) },
            )
            LanguagePicker(
                label = stringResource(R.string.glossary_target_language),
                currentCode = options.targetLang,
                onSelect = { onOptionsChange(options.copy(targetLang = it)) },
            )
            Text(stringResource(R.string.glossary_scope), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EngineChip(
                    scopeMode,
                    GlossaryScopeMode.GLOBAL,
                    stringResource(R.string.glossary_scope_global),
                    onSelect = onScopeModeChange,
                )
                EngineChip(
                    scopeMode,
                    GlossaryScopeMode.CURRENT_APP,
                    currentApp?.displayName ?: stringResource(R.string.glossary_current_app_unknown),
                    enabled = currentApp != null,
                    onSelect = onScopeModeChange,
                )
                EngineChip(
                    scopeMode,
                    GlossaryScopeMode.SELECTED_APP,
                    stringResource(R.string.glossary_scope_select_app),
                    onSelect = onScopeModeChange,
                )
            }
            if (scopeMode == GlossaryScopeMode.SELECTED_APP && selectedApp != null) {
                Surface(
                    onClick = onSelectApp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedApp.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                selectedApp.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Default.Edit, stringResource(R.string.glossary_scope_select_app))
                    }
                }
            }
            Text(stringResource(R.string.glossary_category), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlossaryTermCategory.entries
                    .filterNot { it == GlossaryTermCategory.PRESERVE_SOURCE }
                    .forEach { category ->
                        EngineChip(
                            options.category,
                            category,
                            glossaryImportCategoryLabel(category),
                        ) { onOptionsChange(options.copy(category = it)) }
                    }
            }
            SwitchRow(stringResource(R.string.glossary_case_sensitive), options.caseSensitive) {
                onOptionsChange(options.copy(caseSensitive = it))
            }
            SwitchRow(stringResource(R.string.glossary_enabled), options.enabled) {
                onOptionsChange(options.copy(enabled = it))
            }
            if (showConflictPolicy) {
                Text(
                    stringResource(R.string.glossary_import_existing_terms),
                    style = MaterialTheme.typography.labelLarge,
                )
                GlossaryImportConflictPolicyGroup(
                    selected = options.conflictPolicy,
                    onSelected = { onOptionsChange(options.copy(conflictPolicy = it)) },
                )
            }
        }
    }
}

@Composable
private fun GlossaryImportConflictPolicyGroup(
    selected: GlossaryImportConflictPolicy,
    onSelected: (GlossaryImportConflictPolicy) -> Unit,
) {
    val options = listOf(
        GlossaryImportConflictPolicy.SKIP to R.string.glossary_import_conflict_skip,
        GlossaryImportConflictPolicy.OVERWRITE to R.string.glossary_import_conflict_overwrite,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (policy, labelRes) ->
            SegmentedButton(
                selected = selected == policy,
                onClick = { onSelected(policy) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun GlossaryImportPreviewTabs(
    preview: GlossaryImportPreview,
    selectedFilter: GlossaryImportPreviewFilter,
    onFilterSelected: (GlossaryImportPreviewFilter) -> Unit,
) {
    val tabs = listOf(
        GlossaryImportPreviewFilter.ALL to R.string.glossary_import_filter_all,
        GlossaryImportPreviewFilter.IMPORTABLE to R.string.glossary_import_filter_importable,
        GlossaryImportPreviewFilter.OVERWRITE to R.string.glossary_import_filter_overwrite,
        GlossaryImportPreviewFilter.SKIPPED to R.string.glossary_import_filter_skipped,
        GlossaryImportPreviewFilter.PROBLEM to R.string.glossary_import_filter_problem,
    )
    ScrollableTabRow(
        selectedTabIndex = tabs.indexOfFirst { it.first == selectedFilter },
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
    ) {
        tabs.forEach { (filter, labelRes) ->
            Tab(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                text = {
                    Text(
                        text = stringResource(
                            labelRes,
                            GlossaryImportPreviewFilterPolicy.count(preview, filter),
                        ),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun GlossaryImportPreviewCard(
    row: GlossaryImportPreviewRow,
    onSelectedChange: (Boolean) -> Unit,
) {
    val statusColor = glossaryImportStatusColor(row.status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.selected,
                onCheckedChange = if (row.selectable) onSelectedChange else null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = row.entry.source.ifBlank { stringResource(R.string.glossary_import_empty_value) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "→ " + row.entry.target.ifBlank {
                        stringResource(R.string.glossary_import_empty_value)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                row.existingTarget?.let { existing ->
                    if (existing.trim() != row.entry.target.trim()) {
                        Text(
                            text = stringResource(R.string.glossary_import_existing_value, existing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (GlossaryImportPreviewStatusPolicy.showsTrailingStatus(row.status)) {
                Icon(
                    imageVector = when (row.status) {
                        GlossaryImportRowStatus.READY,
                        GlossaryImportRowStatus.WILL_OVERWRITE -> Icons.Default.CheckCircle
                        GlossaryImportRowStatus.WILL_SKIP,
                        GlossaryImportRowStatus.DUPLICATE,
                        GlossaryImportRowStatus.DESELECTED -> Icons.Default.Warning
                        else -> Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Text(
                    text = glossaryImportStatusLabel(row.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
        }
    }
}

@Composable
private fun rememberGlossaryImportOptions(
    currentApp: ForegroundApp?,
    defaultSourceLang: String,
    defaultTargetLang: String,
) = remember(currentApp, defaultSourceLang, defaultTargetLang) {
    mutableStateOf(
        GlossaryImportOptions(
            scopePackage = "",
            appLabel = "",
            sourceLang = defaultSourceLang,
            targetLang = defaultTargetLang,
            category = GlossaryTermCategory.TERM,
            caseSensitive = false,
            enabled = true,
            conflictPolicy = GlossaryImportConflictPolicy.SKIP,
        )
    )
}

private fun GlossaryImportOptions.toTerm(
    source: String,
    target: String,
    scopedApp: SelectableApp?,
) = GlossaryTermEntity(
    scopePackage = scopedApp?.packageName.orEmpty(),
    appLabel = scopedApp?.displayName.orEmpty(),
    sourceLang = sourceLang,
    targetLang = targetLang,
    sourceTerm = source,
    targetTerm = target,
    category = category,
    caseSensitive = caseSensitive,
    enabled = enabled,
)

private fun ForegroundApp.toSelectableApp() = SelectableApp(
    packageName = packageName,
    displayName = displayName,
)

@Composable
private fun glossaryImportCategoryLabel(category: GlossaryTermCategory): String = stringResource(
    when (category) {
        GlossaryTermCategory.PERSON -> R.string.glossary_category_person
        GlossaryTermCategory.PLACE -> R.string.glossary_category_place
        GlossaryTermCategory.ORGANIZATION -> R.string.glossary_category_organization
        GlossaryTermCategory.TERM -> R.string.glossary_category_term
        GlossaryTermCategory.PRESERVE_SOURCE -> R.string.source_preservation_category
    }
)

@Composable
private fun glossaryImportStatusLabel(status: GlossaryImportRowStatus): String = stringResource(
    when (status) {
        GlossaryImportRowStatus.READY -> R.string.glossary_import_status_ready
        GlossaryImportRowStatus.WILL_OVERWRITE -> R.string.glossary_import_status_overwrite
        GlossaryImportRowStatus.WILL_SKIP -> R.string.glossary_import_status_skip
        GlossaryImportRowStatus.DUPLICATE -> R.string.glossary_import_status_duplicate
        GlossaryImportRowStatus.FILE_CONFLICT -> R.string.glossary_import_status_file_conflict
        GlossaryImportRowStatus.DESELECTED -> R.string.glossary_import_status_deselected
        GlossaryImportRowStatus.EMPTY_SOURCE -> R.string.glossary_import_status_empty_source
        GlossaryImportRowStatus.EMPTY_TARGET -> R.string.glossary_import_status_empty_target
        GlossaryImportRowStatus.SOURCE_TOO_LONG,
        GlossaryImportRowStatus.TARGET_TOO_LONG -> R.string.glossary_import_status_too_long
    }
)

@Composable
private fun glossaryImportStatusColor(status: GlossaryImportRowStatus): Color = when (status) {
    GlossaryImportRowStatus.READY -> MaterialTheme.colorScheme.primary
    GlossaryImportRowStatus.WILL_OVERWRITE -> MaterialTheme.colorScheme.tertiary
    GlossaryImportRowStatus.WILL_SKIP,
    GlossaryImportRowStatus.DUPLICATE,
    GlossaryImportRowStatus.DESELECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

private suspend fun readGlossaryImportFile(
    context: Context,
    uri: Uri,
): Pair<String, GlossaryImportDocument> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val declaredLength = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: -1L
    if (declaredLength > GLOSSARY_IMPORT_MAX_FILE_BYTES) {
        throw IOException(context.getString(R.string.glossary_import_file_too_large))
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > GLOSSARY_IMPORT_MAX_FILE_BYTES) {
                throw IOException(context.getString(R.string.glossary_import_file_too_large))
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw IOException(context.getString(R.string.glossary_import_open_failed))
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty().ifBlank { "glossary.json" }
    name to GlossaryImportJson.parse(bytes.toString(Charsets.UTF_8))
}

private fun Context.importResultMessage(result: GlossaryImportCommitResult): String = getString(
    R.string.glossary_import_success,
    result.inserted,
    result.overwritten,
    result.skipped,
)
