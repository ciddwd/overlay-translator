package com.gameocr.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.gameocr.app.R
import com.gameocr.app.appcontext.ForegroundApp
import com.gameocr.app.appcontext.SELECTABLE_APP_ICON_SIZE_DP
import com.gameocr.app.appcontext.SelectableApp
import com.gameocr.app.appcontext.SelectableAppPolicy
import com.gameocr.app.data.Languages
import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import kotlinx.coroutines.launch

private data class PendingGlossaryConflict(
    val pending: GlossaryTermEntity,
    val existing: GlossaryTermEntity,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(
    onBack: () -> Unit,
    viewModel: GlossaryViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    val terms by viewModel.terms.collectAsState()
    var currentApp by remember { mutableStateOf<ForegroundApp?>(null) }
    var defaultLanguages by remember { mutableStateOf("auto" to "zh-CN") }
    var selectableApps by remember { mutableStateOf<List<SelectableApp>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<GlossaryTermEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var listFilter by remember { mutableStateOf(GlossaryListFilter()) }
    var pendingDelete by remember { mutableStateOf<GlossaryTermEntity?>(null) }
    var pendingConflict by remember { mutableStateOf<PendingGlossaryConflict?>(null) }
    var saveInProgress by remember { mutableStateOf(false) }

    val categoryLabels = mapOf(
        GlossaryTermCategory.PERSON to stringResource(R.string.glossary_category_person),
        GlossaryTermCategory.PLACE to stringResource(R.string.glossary_category_place),
        GlossaryTermCategory.ORGANIZATION to stringResource(R.string.glossary_category_organization),
        GlossaryTermCategory.TERM to stringResource(R.string.glossary_category_term),
    )
    val globalScopeLabel = stringResource(R.string.glossary_scope_global)
    val visibleTerms = remember(terms, listFilter, categoryLabels, globalScopeLabel) {
        GlossaryListFilterPolicy.filter(
            terms = terms,
            filter = listFilter,
            categoryLabels = categoryLabels,
            globalScopeLabel = globalScopeLabel,
        )
    }

    LaunchedEffect(Unit) {
        currentApp = viewModel.currentApp()
        defaultLanguages = viewModel.defaultLanguages()
        selectableApps = runCatching { viewModel.selectableApps() }.getOrDefault(emptyList())
        appsLoading = false
    }
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.glossary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showFilter = true }) {
                        Icon(
                            Icons.Default.Search,
                            stringResource(R.string.glossary_filter),
                            tint = if (listFilter.isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null
                showEditor = true
            }) {
                Icon(Icons.Default.Add, stringResource(R.string.glossary_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visibleTerms.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (terms.isEmpty()) R.string.glossary_empty else R.string.glossary_filter_empty
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(visibleTerms, key = GlossaryTermEntity::id) { term ->
                GlossaryTermCard(
                    term = term,
                    onEdit = {
                        editing = term
                        showEditor = true
                    },
                    onDelete = { pendingDelete = term },
                )
            }
        }
    }

    if (showEditor) {
        GlossaryTermEditor(
            existing = editing,
            currentApp = currentApp,
            selectableApps = selectableApps,
            appsLoading = appsLoading,
            defaultSourceLang = defaultLanguages.first,
            defaultTargetLang = defaultLanguages.second,
            saving = saveInProgress,
            onDismiss = { showEditor = false },
            onSave = { term ->
                if (saveInProgress) return@GlossaryTermEditor
                saveInProgress = true
                scope.launch {
                    try {
                        val conflict = viewModel.findConflict(term)
                        if (conflict == null) {
                            viewModel.upsert(term)
                            showEditor = false
                        } else {
                            pendingConflict = PendingGlossaryConflict(term, conflict)
                        }
                    } finally {
                        saveInProgress = false
                    }
                }
            },
        )
    }

    if (showFilter) {
        GlossaryFilterDialog(
            terms = terms,
            initial = listFilter,
            categoryLabels = categoryLabels,
            globalScopeLabel = globalScopeLabel,
            onDismiss = { showFilter = false },
            onApply = {
                listFilter = it
                showFilter = false
            },
        )
    }

    pendingDelete?.let { term ->
        GlossaryConfirmationDialog(
            title = stringResource(R.string.glossary_delete_confirm_title),
            message = stringResource(
                R.string.glossary_delete_confirm_message,
                term.sourceTerm,
                term.targetTerm,
            ),
            confirmLabel = stringResource(R.string.glossary_delete),
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch { viewModel.delete(term.id) }
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
                saveInProgress = true
                scope.launch {
                    try {
                        viewModel.overwriteConflict(conflict.pending)
                        showEditor = false
                    } finally {
                        saveInProgress = false
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlossaryFilterDialog(
    terms: List<GlossaryTermEntity>,
    initial: GlossaryListFilter,
    categoryLabels: Map<GlossaryTermCategory, String>,
    globalScopeLabel: String,
    onDismiss: () -> Unit,
    onApply: (GlossaryListFilter) -> Unit,
) {
    var query by remember(initial) { mutableStateOf(initial.query) }
    var categories by remember(initial) { mutableStateOf(initial.categories) }
    var status by remember(initial) { mutableStateOf(initial.status) }
    val draft = GlossaryListFilter(query = query, categories = categories, status = status)
    val resultCount = remember(terms, draft, categoryLabels, globalScopeLabel) {
        GlossaryListFilterPolicy.filter(terms, draft, categoryLabels, globalScopeLabel).size
    }
    val baseColors = MaterialTheme.colorScheme
    val zinc = glossaryEditorZincPalette(baseColors.background.luminance() < 0.5f)
    val dialogColors = baseColors.copy(
        background = zinc.surface,
        surface = zinc.surface,
        surfaceVariant = zinc.mutedSurface,
        surfaceContainer = zinc.surface,
        surfaceContainerHigh = zinc.mutedSurface,
        surfaceContainerHighest = zinc.border,
        outline = zinc.outline,
        outlineVariant = zinc.border,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MaterialTheme(colorScheme = dialogColors) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(16.dp),
                shape = RoundedCornerShape(8.dp),
                color = zinc.surface,
                border = BorderStroke(1.dp, zinc.border),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.heightIn(max = 640.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.glossary_filter_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.settings_color_cancel))
                        }
                    }
                    HorizontalDivider(color = zinc.border)
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.glossary_filter_search_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            Icons.Default.Close,
                                            stringResource(R.string.glossary_app_picker_clear_search),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                        )
                        Text(
                            stringResource(R.string.glossary_filter_status),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EngineChip(
                                status,
                                GlossaryStatusFilter.ALL,
                                stringResource(R.string.glossary_filter_all_statuses),
                            ) { status = it }
                            EngineChip(
                                status,
                                GlossaryStatusFilter.ENABLED,
                                stringResource(R.string.glossary_status_enabled),
                            ) { status = it }
                            EngineChip(
                                status,
                                GlossaryStatusFilter.DISABLED,
                                stringResource(R.string.glossary_status_disabled),
                            ) { status = it }
                        }
                        Text(
                            stringResource(R.string.glossary_category),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = categories.isEmpty(),
                                onClick = { categories = emptySet() },
                                label = { Text(stringResource(R.string.glossary_filter_all_categories)) },
                            )
                            GlossaryTermCategory.entries.forEach { category ->
                                FilterChip(
                                    selected = category in categories,
                                    onClick = {
                                        categories = if (category in categories) {
                                            categories - category
                                        } else {
                                            categories + category
                                        }
                                    },
                                    label = { Text(categoryLabels.getValue(category)) },
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.glossary_filter_result_count, resultCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = zinc.border)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                query = ""
                                categories = emptySet()
                                status = GlossaryStatusFilter.ALL
                            },
                        ) { Text(stringResource(R.string.glossary_filter_reset)) }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_color_cancel))
                        }
                        Button(onClick = { onApply(draft) }) {
                            Text(stringResource(R.string.glossary_filter_apply))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlossaryConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val baseColors = MaterialTheme.colorScheme
    val zinc = glossaryEditorZincPalette(baseColors.background.luminance() < 0.5f)
    val dialogColors = baseColors.copy(
        background = zinc.surface,
        surface = zinc.surface,
        surfaceVariant = zinc.mutedSurface,
        surfaceContainer = zinc.surface,
        surfaceContainerHigh = zinc.mutedSurface,
        surfaceContainerHighest = zinc.border,
        outline = zinc.outline,
        outlineVariant = zinc.border,
    )

    Dialog(onDismissRequest = onDismiss) {
        MaterialTheme(colorScheme = dialogColors) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = zinc.surface,
                border = BorderStroke(1.dp, zinc.border),
                shadowElevation = 8.dp,
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    HorizontalDivider(color = zinc.border)
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp),
                    )
                    HorizontalDivider(color = zinc.border)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_color_cancel))
                        }
                        if (destructive) {
                            DestructiveTextButton(label = confirmLabel, onClick = onConfirm)
                        } else {
                            Button(onClick = onConfirm) { Text(confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlossaryTermCard(
    term: GlossaryTermEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scopeLabel = term.appLabel.ifBlank { stringResource(R.string.glossary_scope_global) }
    val categoryLabel = glossaryCategoryLabel(term.category)
    val sourceLanguage = Languages.nameOf(context, term.sourceLang)
    val targetLanguage = Languages.nameOf(context, term.targetLang)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${term.sourceTerm} -> ${term.targetTerm}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$scopeLabel | $categoryLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = if (term.enabled) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (term.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(
                            if (term.enabled) R.string.glossary_status_enabled
                            else R.string.glossary_status_disabled
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (term.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "$sourceLanguage -> $targetLanguage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, stringResource(R.string.glossary_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.glossary_delete))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlossaryTermEditor(
    existing: GlossaryTermEntity?,
    currentApp: ForegroundApp?,
    selectableApps: List<SelectableApp>,
    appsLoading: Boolean,
    defaultSourceLang: String,
    defaultTargetLang: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (GlossaryTermEntity) -> Unit,
) {
    var sourceTerm by remember(existing) { mutableStateOf(existing?.sourceTerm.orEmpty()) }
    var targetTerm by remember(existing) { mutableStateOf(existing?.targetTerm.orEmpty()) }
    var sourceLang by remember(existing) { mutableStateOf(existing?.sourceLang ?: defaultSourceLang) }
    var targetLang by remember(existing) { mutableStateOf(existing?.targetLang ?: defaultTargetLang) }
    val currentScopeApp = remember(currentApp) {
        currentApp?.let { SelectableApp(packageName = it.packageName, displayName = it.displayName) }
    }
    val initialScope = remember(existing, currentScopeApp) {
        GlossaryScopePolicy.initialSelection(
            scopePackage = existing?.scopePackage.orEmpty(),
            appLabel = existing?.appLabel.orEmpty(),
            currentApp = currentScopeApp,
        )
    }
    var scopeMode by remember(initialScope) { mutableStateOf(initialScope.mode) }
    var selectedApp by remember(initialScope) { mutableStateOf(initialScope.selectedApp) }
    var showAppPicker by remember { mutableStateOf(false) }
    var category by remember(existing) { mutableStateOf(existing?.category ?: GlossaryTermCategory.TERM) }
    var caseSensitive by remember(existing) { mutableStateOf(existing?.caseSensitive == true) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled != false) }
    val scopedApp = GlossaryScopePolicy.scopedApp(scopeMode, currentScopeApp, selectedApp)
    val canSave = sourceTerm.isNotBlank() && targetTerm.isNotBlank() &&
        GlossaryScopePolicy.isValid(scopeMode, currentScopeApp, selectedApp)

    val baseColors = MaterialTheme.colorScheme
    val zinc = glossaryEditorZincPalette(baseColors.background.luminance() < 0.5f)
    val editorColors = baseColors.copy(
        background = zinc.surface,
        surface = zinc.surface,
        surfaceVariant = zinc.mutedSurface,
        surfaceContainer = zinc.surface,
        surfaceContainerHigh = zinc.mutedSurface,
        surfaceContainerHighest = zinc.border,
        outline = zinc.outline,
        outlineVariant = zinc.border,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MaterialTheme(colorScheme = editorColors) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(8.dp),
                color = zinc.surface,
                border = BorderStroke(1.dp, zinc.border),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.heightIn(max = 640.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (existing == null) R.string.glossary_add else R.string.glossary_edit
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.settings_color_cancel))
                        }
                    }
                    HorizontalDivider(color = zinc.border)
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                LanguagePicker(
                    label = stringResource(R.string.glossary_source_language),
                    currentCode = sourceLang,
                    onSelect = { sourceLang = it },
                )
                LanguagePicker(
                    label = stringResource(R.string.glossary_target_language),
                    currentCode = targetLang,
                    onSelect = { targetLang = it },
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
                    ) { scopeMode = it }
                    EngineChip(
                        scopeMode,
                        GlossaryScopeMode.CURRENT_APP,
                        currentApp?.displayName ?: stringResource(R.string.glossary_current_app_unknown),
                        enabled = currentApp != null,
                    ) { scopeMode = it }
                    EngineChip(
                        scopeMode,
                        GlossaryScopeMode.SELECTED_APP,
                        stringResource(R.string.glossary_scope_select_app),
                    ) {
                        scopeMode = it
                        showAppPicker = true
                    }
                }
                if (scopeMode == GlossaryScopeMode.SELECTED_APP && selectedApp != null) {
                    Surface(
                        onClick = { showAppPicker = true },
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
                                Text(
                                    text = selectedApp!!.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = selectedApp!!.packageName,
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
                    GlossaryTermCategory.entries.forEach { option ->
                        EngineChip(category, option, glossaryCategoryLabel(option)) { category = it }
                    }
                }
                SwitchRow(stringResource(R.string.glossary_case_sensitive), caseSensitive) {
                    caseSensitive = it
                }
                SwitchRow(stringResource(R.string.glossary_enabled), enabled) { enabled = it }
            }
                    HorizontalDivider(color = zinc.border)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_color_cancel))
                        }
                        Button(
                            enabled = canSave && !saving,
                            onClick = {
                                val base = existing ?: GlossaryTermEntity(
                                    sourceLang = sourceLang,
                                    targetLang = targetLang,
                                    sourceTerm = sourceTerm,
                                    targetTerm = targetTerm,
                                )
                                onSave(base.copy(
                                    scopePackage = scopedApp?.packageName.orEmpty(),
                                    appLabel = scopedApp?.displayName.orEmpty(),
                                    sourceLang = sourceLang,
                                    targetLang = targetLang,
                                    sourceTerm = sourceTerm,
                                    targetTerm = targetTerm,
                                    category = category,
                                    caseSensitive = caseSensitive,
                                    enabled = enabled,
                                ))
                            },
                        ) { Text(stringResource(R.string.settings_save)) }
                    }
                }
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
}

@Composable
private fun GlossaryAppPickerDialog(
    apps: List<SelectableApp>,
    isLoading: Boolean,
    selectedPackage: String?,
    onDismiss: () -> Unit,
    onSelect: (SelectableApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredApps = remember(apps, query) { SelectableAppPolicy.filter(apps, query) }
    val baseColors = MaterialTheme.colorScheme
    val zinc = glossaryEditorZincPalette(baseColors.background.luminance() < 0.5f)
    val pickerColors = baseColors.copy(
        background = zinc.surface,
        surface = zinc.surface,
        surfaceVariant = zinc.mutedSurface,
        surfaceContainer = zinc.surface,
        surfaceContainerHigh = zinc.mutedSurface,
        surfaceContainerHighest = zinc.border,
        outline = zinc.outline,
        outlineVariant = zinc.border,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MaterialTheme(colorScheme = pickerColors) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = 640.dp)
                    .padding(16.dp),
                shape = RoundedCornerShape(8.dp),
                color = zinc.surface,
                border = BorderStroke(1.dp, zinc.border),
                shadowElevation = 8.dp,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.glossary_app_picker_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.settings_color_cancel))
                        }
                    }
                    HorizontalDivider(color = zinc.border)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        placeholder = { Text(stringResource(R.string.glossary_app_picker_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.glossary_app_picker_clear_search))
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                    )
                    HorizontalDivider(color = zinc.border)
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        when {
                            isLoading -> item {
                                PickerMessage(stringResource(R.string.glossary_app_picker_loading))
                            }
                            filteredApps.isEmpty() -> item {
                                PickerMessage(stringResource(R.string.glossary_app_picker_empty))
                            }
                            else -> items(filteredApps, key = SelectableApp::packageName) { app ->
                                Surface(
                                    onClick = { onSelect(app) },
                                    color = if (app.packageName == selectedPackage) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val icon = app.icon
                                        if (icon != null) {
                                            Image(
                                                bitmap = remember(icon) { icon.asImageBitmap() },
                                                contentDescription = null,
                                                modifier = Modifier.size(SELECTABLE_APP_ICON_SIZE_DP.dp),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier.size(SELECTABLE_APP_ICON_SIZE_DP.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Apps,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(24.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier
                                                .padding(start = 12.dp)
                                                .weight(1f),
                                        ) {
                                            Text(
                                                text = app.displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = app.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (app.packageName == selectedPackage) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class GlossaryEditorZincPalette(
    val surface: Color,
    val mutedSurface: Color,
    val border: Color,
    val outline: Color,
)

private fun glossaryEditorZincPalette(dark: Boolean): GlossaryEditorZincPalette = if (dark) {
    GlossaryEditorZincPalette(
        surface = Color(0xFF18181B),
        mutedSurface = Color(0xFF27272A),
        border = Color(0xFF3F3F46),
        outline = Color(0xFF71717A),
    )
} else {
    GlossaryEditorZincPalette(
        surface = Color(0xFFFAFAFA),
        mutedSurface = Color(0xFFF4F4F5),
        border = Color(0xFFE4E4E7),
        outline = Color(0xFFA1A1AA),
    )
}

@Composable
private fun glossaryCategoryLabel(category: GlossaryTermCategory): String = when (category) {
    GlossaryTermCategory.PERSON -> stringResource(R.string.glossary_category_person)
    GlossaryTermCategory.PLACE -> stringResource(R.string.glossary_category_place)
    GlossaryTermCategory.ORGANIZATION -> stringResource(R.string.glossary_category_organization)
    GlossaryTermCategory.TERM -> stringResource(R.string.glossary_category_term)
}
