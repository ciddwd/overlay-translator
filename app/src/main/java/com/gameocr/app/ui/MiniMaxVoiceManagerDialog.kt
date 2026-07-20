package com.gameocr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gameocr.app.R
import com.gameocr.app.tts.MiniMaxAudioValidationError
import com.gameocr.app.tts.MiniMaxAudioValidationException
import com.gameocr.app.tts.MiniMaxManagedVoice
import com.gameocr.app.tts.MiniMaxManagedVoiceType
import com.gameocr.app.tts.MiniMaxSystemVoice
import com.gameocr.app.tts.MiniMaxVoiceCloneRequest
import com.gameocr.app.tts.MiniMaxVoiceCreationResult
import com.gameocr.app.tts.MiniMaxVoiceDesignRequest
import com.gameocr.app.tts.MiniMaxVoiceIdValidationError
import com.gameocr.app.tts.MiniMaxVoiceIdValidationException
import com.gameocr.app.tts.canGenerateVoiceDesignPrompt
import com.gameocr.app.tts.miniMaxVoiceIdValidationError
import com.gameocr.app.tts.miniMaxVoicePreviewText
import com.gameocr.app.tts.mergeMiniMaxManagedVoices
import com.gameocr.app.tts.isValidMiniMaxClonePromptText
import com.gameocr.app.tts.searchMiniMaxManagedVoices
import com.gameocr.app.tts.searchMiniMaxSystemVoices
import com.gameocr.app.tts.shouldLoadMiniMaxManagedVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class MiniMaxVoiceManagerPage {
    SEARCH,
    CLONE,
    DESIGN,
}

private data class MiniMaxPendingVoiceUse(
    val voiceId: String,
    val voiceName: String,
    val language: String,
)

private sealed interface MiniMaxPendingCreation {
    data class Clone(val request: MiniMaxVoiceCloneRequest) : MiniMaxPendingCreation
    data class Design(val request: MiniMaxVoiceDesignRequest) : MiniMaxPendingCreation
}

private fun Modifier.miniMaxVerticalScrollIndicator(
    state: ScrollState,
    color: Color,
): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0 || size.height <= 0f) return@drawWithContent
    val width = 3.dp.toPx()
    val minimumHeight = 32.dp.toPx()
    val contentHeight = size.height + state.maxValue
    val height = (size.height * size.height / contentHeight)
        .coerceIn(minimumHeight, size.height)
    val offset = state.value.toFloat() / state.maxValue * (size.height - height)
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width, offset),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2f),
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun miniMaxBringIntoViewOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, imeBottom) {
        if (focused) requester.bringIntoView()
    }
    return Modifier
        .bringIntoViewRequester(requester)
        .onFocusChanged { focused = it.isFocused }
}

@Composable
internal fun MiniMaxVoiceField(
    value: String,
    onValueChange: (String) -> Unit,
    baseUrl: String,
    apiKey: String,
    onLoadManagedVoices: suspend (String, String) -> List<MiniMaxManagedVoice>,
    onCloneVoice: suspend (MiniMaxVoiceCloneRequest) -> MiniMaxVoiceCreationResult,
    onDesignVoice: suspend (MiniMaxVoiceDesignRequest) -> MiniMaxVoiceCreationResult,
    onPlayTrialAudio: suspend (String) -> Unit,
    onGenerateVoiceDescription: suspend (String) -> String,
    onDeleteVoice: suspend (String, String, MiniMaxManagedVoice) -> Unit,
    previewLanguageTag: String,
    onTestTextChange: (String) -> Unit,
    onPreviewVoice: suspend (String, String) -> Unit,
) {
    var showManager by remember { mutableStateOf(false) }
    var pendingCreatedVoices by remember {
        mutableStateOf<List<MiniMaxManagedVoice>>(emptyList())
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.settings_tts_voice_id)) },
        placeholder = { Text("male-qn-qingse") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { showManager = true }) {
                Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.settings_tts_minimax_voice_search),
                )
            }
        },
    )

    if (showManager) {
        MiniMaxVoiceManagerPageLayer(
            selectedVoiceId = value,
            pendingCreatedVoices = pendingCreatedVoices,
            onPendingCreatedVoicesChange = { pendingCreatedVoices = it },
            baseUrl = baseUrl,
            apiKey = apiKey,
            onDismiss = { showManager = false },
            onSelectVoice = onValueChange,
            onLoadManagedVoices = onLoadManagedVoices,
            onCloneVoice = onCloneVoice,
            onDesignVoice = onDesignVoice,
            onPlayTrialAudio = onPlayTrialAudio,
            onGenerateVoiceDescription = onGenerateVoiceDescription,
            onDeleteVoice = onDeleteVoice,
            previewLanguageTag = previewLanguageTag,
            onTestTextChange = onTestTextChange,
            onPreviewVoice = onPreviewVoice,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniMaxVoiceManagerPageLayer(
    selectedVoiceId: String,
    pendingCreatedVoices: List<MiniMaxManagedVoice>,
    onPendingCreatedVoicesChange: (List<MiniMaxManagedVoice>) -> Unit,
    baseUrl: String,
    apiKey: String,
    onDismiss: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onLoadManagedVoices: suspend (String, String) -> List<MiniMaxManagedVoice>,
    onCloneVoice: suspend (MiniMaxVoiceCloneRequest) -> MiniMaxVoiceCreationResult,
    onDesignVoice: suspend (MiniMaxVoiceDesignRequest) -> MiniMaxVoiceCreationResult,
    onPlayTrialAudio: suspend (String) -> Unit,
    onGenerateVoiceDescription: suspend (String) -> String,
    onDeleteVoice: suspend (String, String, MiniMaxManagedVoice) -> Unit,
    previewLanguageTag: String,
    onTestTextChange: (String) -> Unit,
    onPreviewVoice: suspend (String, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(MiniMaxVoiceManagerPage.SEARCH) }
    var managedVoices by remember { mutableStateOf(pendingCreatedVoices) }
    var localPendingVoices by remember { mutableStateOf(pendingCreatedVoices) }
    var accountLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var designTrialAudio by remember { mutableStateOf<String?>(null) }
    var preparingTrialAudio by remember { mutableStateOf(false) }
    var pendingCreation by remember { mutableStateOf<MiniMaxPendingCreation?>(null) }
    var pendingUse by remember { mutableStateOf<MiniMaxPendingVoiceUse?>(null) }
    var pendingDelete by remember { mutableStateOf<MiniMaxManagedVoice?>(null) }

    fun showMessage(text: String) {
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    fun playDesignTrialAudio(audioHex: String) {
        if (preparingTrialAudio) return
        preparingTrialAudio = true
        scope.launch {
            try {
                onPlayTrialAudio(audioHex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(
                    context.getString(
                        R.string.settings_tts_minimax_design_trial_error,
                        miniMaxVoiceOperationErrorMessage(context, error),
                    )
                )
            } finally {
                preparingTrialAudio = false
            }
        }
    }

    fun applyLoadedManagedVoices(remoteVoices: List<MiniMaxManagedVoice>) {
        localPendingVoices = localPendingVoices.filterNot { pending ->
            remoteVoices.any { remote ->
                remote.type == pending.type && remote.voiceId == pending.voiceId
            }
        }
        onPendingCreatedVoicesChange(localPendingVoices)
        managedVoices = mergeMiniMaxManagedVoices(remoteVoices, localPendingVoices)
        accountLoaded = true
    }

    fun applyCreatedVoice(result: MiniMaxVoiceCreationResult) {
        managedVoices = upsertMiniMaxManagedVoice(managedVoices, result.voice)
        localPendingVoices = upsertMiniMaxManagedVoice(localPendingVoices, result.voice)
        onPendingCreatedVoicesChange(localPendingVoices)
        onSelectVoice(result.voice.voiceId)
    }

    fun runOperation(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(
                    context.getString(
                        R.string.settings_tts_minimax_voice_operation_error,
                        miniMaxVoiceOperationErrorMessage(context, error),
                    )
                )
            } finally {
                busy = false
            }
        }
    }

    fun createVoice(creation: MiniMaxPendingCreation) {
        runOperation {
            when (creation) {
                is MiniMaxPendingCreation.Clone -> {
                    val result = onCloneVoice(creation.request)
                    applyCreatedVoice(result)
                    showMessage(context.getString(R.string.settings_tts_minimax_voice_created))
                }
                is MiniMaxPendingCreation.Design -> {
                    val result = onDesignVoice(creation.request)
                    applyCreatedVoice(result)
                    val trialAudio = result.previewAudio?.takeIf(String::isNotBlank)
                    if (trialAudio == null) {
                        showMessage(
                            context.getString(R.string.settings_tts_minimax_voice_created_no_trial)
                        )
                    } else {
                        designTrialAudio = trialAudio
                        showMessage(context.getString(R.string.settings_tts_minimax_voice_created))
                    }
                }
            }
        }
    }

    LaunchedEffect(baseUrl, apiKey) {
        accountLoaded = false
        if (shouldLoadMiniMaxManagedVoices(apiKey)) {
            runOperation {
                applyLoadedManagedVoices(onLoadManagedVoices(baseUrl, apiKey))
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .safeDrawingPadding(),
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_tts_minimax_voice_manager_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SecondaryTabRow(
                    selectedTabIndex = page.ordinal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MiniMaxVoiceManagerPage.entries.forEach { option ->
                        Tab(
                            selected = page == option,
                            onClick = {
                                page = option
                            },
                            text = {
                                Text(
                                    stringResource(
                                        when (option) {
                                            MiniMaxVoiceManagerPage.SEARCH ->
                                                R.string.settings_tts_minimax_voice_tab_search
                                            MiniMaxVoiceManagerPage.CLONE ->
                                                R.string.settings_tts_minimax_voice_tab_clone
                                            MiniMaxVoiceManagerPage.DESIGN ->
                                                R.string.settings_tts_minimax_voice_tab_design
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        when (page) {
                            MiniMaxVoiceManagerPage.SEARCH -> MiniMaxVoiceSearchPage(
                                managedVoices = managedVoices,
                                accountLoaded = accountLoaded,
                                apiKeyPresent = apiKey.isNotBlank(),
                                busy = busy,
                                onRefresh = {
                                    runOperation {
                                        applyLoadedManagedVoices(
                                            onLoadManagedVoices(baseUrl, apiKey)
                                        )
                                    }
                                },
                                onUse = { voiceId, voiceName, language ->
                                    pendingUse = MiniMaxPendingVoiceUse(
                                        voiceId = voiceId,
                                        voiceName = voiceName,
                                        language = language,
                                    )
                                },
                                onPreview = { voiceId, language ->
                                    val previewText = miniMaxVoicePreviewText(
                                        language,
                                        previewLanguageTag,
                                    )
                                    onTestTextChange(previewText)
                                    runOperation { onPreviewVoice(voiceId, previewText) }
                                },
                                onDelete = { pendingDelete = it },
                            )
                            MiniMaxVoiceManagerPage.CLONE -> MiniMaxVoiceClonePage(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                busy = busy,
                                onCreate = { request ->
                                    pendingCreation = MiniMaxPendingCreation.Clone(request)
                                },
                            )
                            MiniMaxVoiceManagerPage.DESIGN -> MiniMaxVoiceDesignPage(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                busy = busy,
                                trialAudioAvailable = designTrialAudio != null,
                                preparingTrialAudio = preparingTrialAudio,
                                onPlayTrialAudio = {
                                    designTrialAudio?.let(::playDesignTrialAudio)
                                },
                                onGenerateDescription = onGenerateVoiceDescription,
                                onCreate = { request ->
                                    pendingCreation = MiniMaxPendingCreation.Design(request)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingCreation?.let { creation ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingCreation = null },
            title = { Text(stringResource(R.string.settings_tts_minimax_create_confirm_title)) },
            text = { Text(stringResource(R.string.settings_tts_minimax_create_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingCreation = null
                        createVoice(creation)
                    },
                ) {
                    Text(stringResource(R.string.settings_tts_minimax_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCreation = null }, enabled = !busy) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            },
        )
    }

    pendingUse?.let { voice ->
        AlertDialog(
            onDismissRequest = { pendingUse = null },
            title = { Text(stringResource(R.string.settings_tts_minimax_voice_use_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_tts_minimax_voice_use_message,
                        voice.voiceName,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUse = null
                        onTestTextChange(
                            miniMaxVoicePreviewText(voice.language, previewLanguageTag)
                        )
                        onSelectVoice(voice.voiceId)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.settings_tts_minimax_voice_use))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUse = null }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            },
        )
    }

    pendingDelete?.let { voice ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(stringResource(R.string.settings_tts_minimax_voice_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_tts_minimax_voice_delete_message,
                        voice.voiceId,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingDelete = null
                        runOperation {
                            onDeleteVoice(baseUrl, apiKey, voice)
                            managedVoices = managedVoices.filterNot { it.voiceId == voice.voiceId }
                            localPendingVoices = localPendingVoices.filterNot {
                                it.type == voice.type && it.voiceId == voice.voiceId
                            }
                            onPendingCreatedVoicesChange(localPendingVoices)
                            if (selectedVoiceId == voice.voiceId) onSelectVoice("")
                            showMessage(
                                context.getString(
                                    R.string.settings_tts_minimax_voice_deleted,
                                    voice.voiceId,
                                )
                            )
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.settings_tts_minimax_voice_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, enabled = !busy) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniMaxVoiceSearchPage(
    managedVoices: List<MiniMaxManagedVoice>,
    accountLoaded: Boolean,
    apiKeyPresent: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onUse: (String, String, String) -> Unit,
    onPreview: (String, String) -> Unit,
    onDelete: (MiniMaxManagedVoice) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val systemResults = remember(query) { searchMiniMaxSystemVoices(query) }
    val managedResults = remember(query, managedVoices) {
        searchMiniMaxManagedVoices(query, managedVoices)
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.settings_tts_minimax_voice_search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
        } else {
            null
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(
                R.string.settings_tts_minimax_voice_results,
                systemResults.size + managedResults.size,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        IconButton(onClick = onRefresh, enabled = apiKeyPresent && !busy) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.settings_tts_minimax_account_refresh),
                )
            }
        }
    }
    if (!apiKeyPresent) {
        Text(
            stringResource(R.string.settings_tts_minimax_api_key_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        stringResource(R.string.settings_tts_minimax_voice_select_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.settings_tts_minimax_voice_preview_cost_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    LazyColumn(modifier = Modifier.weight(1f)) {
        if (managedResults.isNotEmpty() || accountLoaded) {
            stickyHeader(key = "account-title") {
                MiniMaxVoiceSectionHeader(
                    text = stringResource(R.string.settings_tts_minimax_account_voices)
                )
            }
            if (managedResults.isEmpty()) {
                item("account-empty") {
                    Text(
                        stringResource(R.string.settings_tts_minimax_account_empty),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(managedResults, key = { "account:${it.type.apiId}:${it.voiceId}" }) { voice ->
                MiniMaxManagedVoiceRow(
                    voice = voice,
                    previewEnabled = apiKeyPresent && !busy,
                    actionsEnabled = !busy,
                    onUse = onUse,
                    onPreview = onPreview,
                    onDelete = onDelete,
                )
            }
        }
        if (systemResults.isNotEmpty()) {
            stickyHeader(key = "system-title") {
                MiniMaxVoiceSectionHeader(
                    text = stringResource(R.string.settings_tts_system_voice)
                )
            }
            items(systemResults, key = { "system:${it.voiceId}" }) { voice ->
                MiniMaxSystemVoiceRow(
                    voice = voice,
                    previewEnabled = apiKeyPresent && !busy,
                    actionsEnabled = !busy,
                    onUse = onUse,
                    onPreview = onPreview,
                )
            }
        }
        if (systemResults.isEmpty() && managedResults.isEmpty()) {
            item("no-results") {
                Text(
                    stringResource(R.string.settings_tts_minimax_voice_no_results),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun MiniMaxVoiceSectionHeader(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MiniMaxSystemVoiceRow(
    voice: MiniMaxSystemVoice,
    previewEnabled: Boolean,
    actionsEnabled: Boolean,
    onUse: (String, String, String) -> Unit,
    onPreview: (String, String) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = actionsEnabled) {
            onUse(voice.voiceId, voice.name, voice.language)
        },
        headlineContent = { Text(voice.name) },
        supportingContent = {
            Column {
                Text(voice.language)
                Text(
                    voice.voiceId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            MiniMaxVoicePreviewButton(
                voiceId = voice.voiceId,
                language = voice.language,
                enabled = previewEnabled,
                onPreview = onPreview,
            )
        },
    )
    HorizontalDivider()
}

@Composable
private fun MiniMaxManagedVoiceRow(
    voice: MiniMaxManagedVoice,
    previewEnabled: Boolean,
    actionsEnabled: Boolean,
    onUse: (String, String, String) -> Unit,
    onPreview: (String, String) -> Unit,
    onDelete: (MiniMaxManagedVoice) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = actionsEnabled) {
            onUse(voice.voiceId, voice.voiceId, "")
        },
        headlineContent = { Text(voice.voiceId) },
        supportingContent = {
            Column {
                Text(
                    stringResource(
                        if (voice.type == MiniMaxManagedVoiceType.CLONING) {
                            R.string.settings_tts_minimax_voice_type_cloned
                        } else {
                            R.string.settings_tts_minimax_voice_type_designed
                        }
                    ) + voice.createdTime.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                )
                voice.description.firstOrNull()?.let { description ->
                    Text(
                        description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            Row {
                MiniMaxVoicePreviewButton(
                    voiceId = voice.voiceId,
                    language = "",
                    enabled = previewEnabled,
                    onPreview = onPreview,
                )
                IconButton(
                    onClick = { onDelete(voice) },
                    enabled = actionsEnabled,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.settings_tts_minimax_voice_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun MiniMaxVoicePreviewButton(
    voiceId: String,
    language: String,
    enabled: Boolean,
    onPreview: (String, String) -> Unit,
) {
    IconButton(
        onClick = { onPreview(voiceId, language) },
        enabled = enabled,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = stringResource(R.string.settings_tts_minimax_voice_preview),
        )
    }
}

@Composable
private fun MiniMaxVoiceClonePage(
    baseUrl: String,
    apiKey: String,
    busy: Boolean,
    onCreate: (MiniMaxVoiceCloneRequest) -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scrollIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var voiceId by remember { mutableStateOf("") }
    var promptUri by remember { mutableStateOf<Uri?>(null) }
    var promptText by remember { mutableStateOf("") }
    var validationText by remember { mutableStateOf("") }
    var noiseReduction by remember { mutableStateOf(false) }
    var volumeNormalization by remember { mutableStateOf(false) }
    val sourceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        sourceUri = uri?.also { context.persistAudioPermission(it) }
    }
    val promptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        promptUri = uri?.also { context.persistAudioPermission(it) }
    }
    val voiceIdError = voiceId.takeIf(String::isNotBlank)?.let(::miniMaxVoiceIdValidationError)
    val promptPairValid = ((promptUri == null && promptText.isBlank()) ||
        (promptUri != null && promptText.isNotBlank())) && isValidMiniMaxClonePromptText(promptText)
    val canCreate = !busy && apiKey.isNotBlank() && sourceUri != null &&
        voiceIdError == null && voiceId.isNotBlank() && promptPairValid && validationText.length <= 200

    Column(
        modifier = Modifier
            .fillMaxSize()
            .miniMaxVerticalScrollIndicator(scrollState, scrollIndicatorColor)
            .verticalScroll(scrollState)
            .padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_tts_minimax_clone_source), fontWeight = FontWeight.SemiBold)
        AudioPickerRow(
            uri = sourceUri,
            buttonLabel = stringResource(R.string.settings_tts_minimax_clone_source_select),
            onSelect = { sourceLauncher.launch(MINIMAX_AUDIO_MIME_TYPES) },
            onClear = { sourceUri = null },
        )
        Text(
            stringResource(R.string.settings_tts_minimax_clone_source_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = voiceId,
            onValueChange = { voiceId = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(miniMaxBringIntoViewOnFocus()),
            singleLine = true,
            label = { Text(stringResource(R.string.settings_tts_minimax_clone_voice_id)) },
            supportingText = {
                Text(
                    voiceIdError?.let { miniMaxVoiceIdErrorMessage(context, it) }
                        ?: stringResource(R.string.settings_tts_minimax_clone_voice_id_hint)
                )
            },
            isError = voiceIdError != null,
        )
        Text(
            stringResource(R.string.settings_tts_minimax_clone_prompt_audio),
            fontWeight = FontWeight.SemiBold,
        )
        AudioPickerRow(
            uri = promptUri,
            buttonLabel = stringResource(R.string.settings_tts_minimax_clone_prompt_select),
            onSelect = { promptLauncher.launch(MINIMAX_AUDIO_MIME_TYPES) },
            onClear = {
                promptUri = null
                promptText = ""
            },
        )
        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(miniMaxBringIntoViewOnFocus()),
            label = { Text(stringResource(R.string.settings_tts_minimax_clone_prompt_text)) },
            enabled = promptUri != null,
            singleLine = true,
            isError = promptUri != null &&
                (promptText.isBlank() || !isValidMiniMaxClonePromptText(promptText)),
            supportingText = if (promptUri != null && promptText.isNotBlank() &&
                !isValidMiniMaxClonePromptText(promptText)
            ) {
                { Text(stringResource(R.string.settings_tts_minimax_clone_prompt_punctuation)) }
            } else {
                null
            },
        )
        Text(
            stringResource(R.string.settings_tts_minimax_clone_prompt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = validationText,
            onValueChange = { validationText = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(miniMaxBringIntoViewOnFocus()),
            label = { Text(stringResource(R.string.settings_tts_minimax_clone_validation_text)) },
            minLines = 2,
            maxLines = 3,
            isError = validationText.length > 200,
        )
        SwitchRow(
            label = stringResource(R.string.settings_tts_minimax_clone_noise_reduction),
            checked = noiseReduction,
            onChange = { noiseReduction = it },
        )
        SwitchRow(
            label = stringResource(R.string.settings_tts_minimax_clone_volume_normalization),
            checked = volumeNormalization,
            onChange = { volumeNormalization = it },
        )
        Button(
            enabled = canCreate,
            onClick = {
                onCreate(
                    MiniMaxVoiceCloneRequest(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        sourceAudioUri = requireNotNull(sourceUri),
                        voiceId = voiceId,
                        promptAudioUri = promptUri,
                        promptText = promptText,
                        textValidation = validationText,
                        needNoiseReduction = noiseReduction,
                        needVolumeNormalization = volumeNormalization,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    if (busy) R.string.settings_tts_minimax_creating
                    else R.string.settings_tts_minimax_create
                ),
                modifier = Modifier.padding(start = if (busy) 8.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun MiniMaxVoiceDesignPage(
    baseUrl: String,
    apiKey: String,
    busy: Boolean,
    trialAudioAvailable: Boolean,
    preparingTrialAudio: Boolean,
    onPlayTrialAudio: () -> Unit,
    onGenerateDescription: suspend (String) -> String,
    onCreate: (MiniMaxVoiceDesignRequest) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val scrollIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    var prompt by remember { mutableStateOf("") }
    var previewText by remember { mutableStateOf("") }
    var customVoiceId by remember { mutableStateOf("") }
    var generatingDescription by remember { mutableStateOf(false) }
    var generationError by remember { mutableStateOf<String?>(null) }
    val canCreate = !busy && !generatingDescription && apiKey.isNotBlank() && prompt.isNotBlank() &&
        previewText.isNotBlank() && previewText.length <= 500

    Column(
        modifier = Modifier
            .fillMaxSize()
            .miniMaxVerticalScrollIndicator(scrollState, scrollIndicatorColor)
            .verticalScroll(scrollState)
            .padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                generationError = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(miniMaxBringIntoViewOnFocus()),
            label = { Text(stringResource(R.string.settings_tts_minimax_design_prompt)) },
            placeholder = { Text(stringResource(R.string.settings_tts_minimax_design_prompt_hint)) },
            minLines = 3,
            maxLines = 5,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = {
                    if (!canGenerateVoiceDesignPrompt(prompt, generatingDescription)) {
                        return@OutlinedButton
                    }
                    generatingDescription = true
                    generationError = null
                    scope.launch {
                        try {
                            prompt = onGenerateDescription(prompt)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            generationError = context.getString(
                                R.string.settings_tts_minimax_design_ai_error,
                                error.message ?: error.javaClass.simpleName,
                            )
                        } finally {
                            generatingDescription = false
                        }
                    }
                },
                enabled = !busy && canGenerateVoiceDesignPrompt(prompt, generatingDescription),
            ) {
                if (generatingDescription) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                }
                Text(
                    stringResource(
                        if (generatingDescription) {
                            R.string.settings_tts_minimax_design_ai_generating
                        } else {
                            R.string.settings_tts_minimax_design_ai_generate
                        }
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            SettingHelpTooltip(
                text = stringResource(R.string.settings_tts_minimax_design_ai_hint)
            )
        }
        generationError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = previewText,
            onValueChange = { previewText = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(miniMaxBringIntoViewOnFocus()),
            label = { Text(stringResource(R.string.settings_tts_minimax_design_preview_text)) },
            supportingText = { Text(stringResource(R.string.settings_tts_minimax_design_preview_hint)) },
            minLines = 2,
            maxLines = 4,
            isError = previewText.length > 500,
        )
        OutlinedTextField(
            value = customVoiceId,
            onValueChange = { customVoiceId = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(miniMaxBringIntoViewOnFocus()),
            singleLine = true,
            label = { Text(stringResource(R.string.settings_tts_minimax_design_custom_id)) },
        )
        Button(
            enabled = canCreate,
            onClick = {
                onCreate(
                    MiniMaxVoiceDesignRequest(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        prompt = prompt,
                        previewText = previewText,
                        customVoiceId = customVoiceId,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    if (busy) R.string.settings_tts_minimax_creating
                    else R.string.settings_tts_minimax_create
                ),
                modifier = Modifier.padding(start = if (busy) 8.dp else 0.dp),
            )
        }
        if (trialAudioAvailable) {
            OutlinedButton(
                onClick = onPlayTrialAudio,
                enabled = !busy && !preparingTrialAudio,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (preparingTrialAudio) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
                Text(
                    stringResource(R.string.settings_tts_minimax_design_trial_play),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                stringResource(R.string.settings_tts_minimax_design_trial_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioPickerRow(
    uri: Uri?,
    buttonLabel: String,
    onSelect: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.AudioFile, contentDescription = null)
            Text(
                uri?.let { context.displayNameForUri(it) } ?: buttonLabel,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (uri != null) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
    }
}

private fun Context.persistAudioPermission(uri: Uri) {
    runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun Context.displayNameForUri(uri: Uri): String {
    var cursor: android.database.Cursor? = null
    return try {
        cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )
        val current = cursor
        if (current?.moveToFirst() == true) {
            val index = current.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) current.getString(index) else uri.lastPathSegment.orEmpty()
        } else {
            uri.lastPathSegment.orEmpty()
        }
    } finally {
        cursor?.close()
    }
}

private fun miniMaxVoiceOperationErrorMessage(context: Context, error: Throwable): String = when (error) {
    is MiniMaxAudioValidationException -> context.getString(
        when (error.reason) {
            MiniMaxAudioValidationError.UNSUPPORTED_FORMAT ->
                R.string.settings_tts_minimax_audio_unsupported
            MiniMaxAudioValidationError.FILE_TOO_LARGE ->
                R.string.settings_tts_minimax_audio_too_large
            MiniMaxAudioValidationError.CLONE_TOO_SHORT ->
                R.string.settings_tts_minimax_clone_audio_too_short
            MiniMaxAudioValidationError.CLONE_TOO_LONG ->
                R.string.settings_tts_minimax_clone_audio_too_long
            MiniMaxAudioValidationError.PROMPT_TOO_LONG ->
                R.string.settings_tts_minimax_prompt_audio_too_long
        }
    )
    is MiniMaxVoiceIdValidationException -> miniMaxVoiceIdErrorMessage(context, error.reason)
    else -> error.message ?: error.javaClass.simpleName
}

private fun miniMaxVoiceIdErrorMessage(
    context: Context,
    error: MiniMaxVoiceIdValidationError,
): String = context.getString(
    when (error) {
        MiniMaxVoiceIdValidationError.TOO_SHORT ->
            R.string.settings_tts_minimax_voice_id_too_short
        MiniMaxVoiceIdValidationError.TOO_LONG ->
            R.string.settings_tts_minimax_voice_id_too_long
        MiniMaxVoiceIdValidationError.INVALID_FIRST_CHARACTER ->
            R.string.settings_tts_minimax_voice_id_first
        MiniMaxVoiceIdValidationError.INVALID_CHARACTER ->
            R.string.settings_tts_minimax_voice_id_chars
        MiniMaxVoiceIdValidationError.INVALID_LAST_CHARACTER ->
            R.string.settings_tts_minimax_voice_id_last
    }
)

private fun upsertMiniMaxManagedVoice(
    voices: List<MiniMaxManagedVoice>,
    voice: MiniMaxManagedVoice,
): List<MiniMaxManagedVoice> = listOf(voice) + voices.filterNot {
    it.type == voice.type && it.voiceId == voice.voiceId
}

private val MINIMAX_AUDIO_MIME_TYPES = arrayOf(
    "audio/mpeg",
    "audio/mp4",
    "audio/x-m4a",
    "audio/wav",
    "audio/x-wav",
)
