package com.gameocr.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gameocr.app.R
import com.gameocr.app.data.AutoOcrLanguageListPolicy
import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrRoutingPolicy
import com.gameocr.app.data.AutoOcrSettings
import com.gameocr.app.data.Settings
import com.gameocr.app.download.ModelDownloadSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoOcrSettingsScreen(
    settings: Settings,
    loadAvailable: suspend (Settings) -> Set<AutoOcrRoute>,
    availabilityRevision: Any,
    activeModels: Set<ModelDownloadSpec>,
    activeModules: Set<com.gameocr.app.data.OcrEngineKind> = emptySet(),
    downloadBusy: Boolean,
    onDownload: (AutoOcrRoute) -> Unit,
    downloadStatus: @Composable () -> Unit,
    onChange: ((AutoOcrSettings) -> AutoOcrSettings) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val config = settings.autoOcr
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var selectedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var scrollToLanguage by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    var available by remember { mutableStateOf<Set<AutoOcrRoute>?>(null) }
    var resumeRevision by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settings.copy(autoOcr = AutoOcrSettings()), availabilityRevision, selectedLanguage, resumeRevision) {
        available = null
        available = loadAvailable(settings)
    }
    val languages = remember(config) { AutoOcrLanguageListPolicy.visible(config) }
    val addableCodes = remember(config) {
        AutoOcrLanguageListPolicy.addable(config).mapTo(mutableSetOf()) { it.code }
    }
    LaunchedEffect(languages, pendingDeleteLanguage) {
        val code = pendingDeleteLanguage
        if (code != null && (AutoOcrLanguageListPolicy.isDefault(code) || languages.none { it.code == code })) {
            pendingDeleteLanguage = null
        }
    }
    LaunchedEffect(languages, scrollToLanguage, showLanguagePicker) {
        val code = scrollToLanguage
        if (code != null && !showLanguagePicker) {
            val index = languages.indexOfFirst { AutoOcrSettings.languageKey(it.code) == code }
            // The full-width description occupies the first grid item.
            if (index >= 0) gridState.animateScrollToItem(index + 1)
            scrollToLanguage = null
        }
    }
    BackHandler(enabled = !showLanguagePicker && selectedLanguage == null && pendingDeleteLanguage == null, onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_auto_ocr_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "description", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        stringResource(R.string.settings_auto_ocr_description),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    downloadStatus()
                }
            }
            items(languages, key = { it.code }) { language ->
                val current = config.routeFor(language.code)
                AutoOcrLanguageTile(
                    language = language,
                    route = current,
                    removable = !AutoOcrLanguageListPolicy.isDefault(language.code),
                    downloading = autoOcrOptionState(settings, language.code, current, available,
                        activeModels, activeModules) == AutoOcrOptionState.DOWNLOADING,
                    onClick = { selectedLanguage = language.code },
                    onRemove = { pendingDeleteLanguage = language.code },
                )
            }
            if (addableCodes.isNotEmpty()) {
                item(key = "add-language") {
                    AutoOcrAddLanguageTile(onClick = { showLanguagePicker = true })
                }
            }
        }
    }
    pendingDeleteLanguage?.let { code ->
        val language = languages.firstOrNull { it.code == code } ?: return@let
        if (AutoOcrLanguageListPolicy.isDefault(code)) return@let
        CatalystAlertDialog(
            onDismissRequest = { pendingDeleteLanguage = null },
            title = { Text(stringResource(R.string.settings_auto_ocr_delete_language_confirm, stringResource(language.nameRes))) },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLanguage = null }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pendingDeleteLanguage == code) {
                        pendingDeleteLanguage = null
                        onChange { latest -> AutoOcrLanguageListPolicy.remove(latest, code) }
                    }
                }) {
                    Text(stringResource(R.string.model_download_cancel_confirm_ok))
                }
            },
        )
    }
    selectedLanguage?.let { code ->
        val language = languages.firstOrNull { it.code == code } ?: return@let
        val options = AutoOcrRoutingPolicy.candidates
        AutoOcrEngineSheet(
            title = stringResource(if (AutoOcrSettings.languageKey(code) == "zh")
                R.string.settings_ocr_chip_chinese else language.nameRes),
            current = config.routeFor(code),
            options = if (AutoOcrSettings.defaultRoute(code) == null) listOf(null) + options else options,
            state = { route -> autoOcrOptionState(settings, code, route, available, activeModels, activeModules) },
            canSelect = { route, state -> autoOcrCanSelect(settings, code, route, state) },
            downloadBusy = downloadBusy,
            onSelect = select@ { selected ->
                if (!autoOcrCanSelect(settings, code, selected,
                        autoOcrOptionState(settings, code, selected, available, activeModels, activeModules))) return@select
                val key = AutoOcrSettings.languageKey(code)
                onChange { latest ->
                    latest.copy(routes = if (selected == null) latest.routes - key else latest.routes + (key to selected))
                }
                selectedLanguage = null
            },
            onDownload = { route ->
                if (!downloadBusy && autoOcrOptionState(settings, code, route, available, activeModels, activeModules) == AutoOcrOptionState.DOWNLOAD) {
                    selectedLanguage = null
                    onDownload(route)
                }
            },
            onDismiss = { selectedLanguage = null },
        )
    }
    if (showLanguagePicker) {
        LanguagePickerSheet(
            currentCode = "",
            pinned = emptyList(),
            allowAuto = false,
            allowedLanguageCodes = addableCodes,
            onSelect = { code ->
                onChange { latest -> AutoOcrLanguageListPolicy.add(latest, code) }
                scrollToLanguage = AutoOcrSettings.languageKey(code)
                showLanguagePicker = false
            },
            onTogglePin = null,
            onDismiss = { showLanguagePicker = false },
        )
    }
}
