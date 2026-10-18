package com.gameocr.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.gameocr.app.R
import com.gameocr.app.asr.AsrImportResult
import com.gameocr.app.asr.AsrModelId
import com.gameocr.app.data.AsrDisplayPolicy
import com.gameocr.app.data.AsrCloudModel
import com.gameocr.app.data.AsrCloudProvider
import com.gameocr.app.data.AsrEngineKind
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrLanguagePolicy
import com.gameocr.app.data.AsrWhisperVariant
import com.gameocr.app.data.Settings
import com.gameocr.app.download.ModelDownloadProgressText
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelDownloadWorker
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Model status shown on its own line under the selected engine. */
internal sealed interface AsrModelUiState {
    data object Checking : AsrModelUiState
    data object Queued : AsrModelUiState
    data object Importing : AsrModelUiState
    data object NotDownloaded : AsrModelUiState
    data class Downloading(val percent: Int?) : AsrModelUiState
    data object Verifying : AsrModelUiState
    data object Ready : AsrModelUiState
    data object Failed : AsrModelUiState
}

internal object AsrModelUiPolicy {
    /** Background work wins over the static file check, so progress survives leaving the screen. */
    fun state(ready: Boolean, failed: Boolean, downloaded: Long?, total: Long?,
        busy: Boolean = downloaded != null, verifying: Boolean = false,
        importing: Boolean = false, checking: Boolean = false): AsrModelUiState = when {
        importing -> AsrModelUiState.Importing
        busy && verifying -> AsrModelUiState.Verifying
        busy && downloaded != null -> AsrModelUiState.Downloading(
            total?.let { ModelDownloadProgressText.percent(downloaded, it) })
        busy -> AsrModelUiState.Queued
        checking -> AsrModelUiState.Checking
        ready -> AsrModelUiState.Ready
        failed -> AsrModelUiState.Failed
        else -> AsrModelUiState.NotDownloaded
    }
    fun isBusy(state: AsrModelUiState): Boolean = state == AsrModelUiState.Checking ||
        state == AsrModelUiState.Queued || state == AsrModelUiState.Importing ||
        state is AsrModelUiState.Downloading || state == AsrModelUiState.Verifying
    fun canDownload(state: AsrModelUiState): Boolean = state == AsrModelUiState.NotDownloaded || state == AsrModelUiState.Failed

    fun sizeMegabytes(model: AsrModelId): Long = (model.totalBytes + 512 * 1024) / (1024 * 1024)

    /** Same enabled states as the OCR download/import row. */
    fun canImport(state: AsrModelUiState): Boolean = canDownload(state)

    fun canDelete(state: AsrModelUiState): Boolean = state == AsrModelUiState.Ready
}

/**
 * Speech recognition card: same structure as translation-engine settings (engine groups, then the
 * selected engine's configuration). Model choices save immediately; credentials use the parent
 * settings form's Save action. Speech mode on the ball is always
 * available; recording starts only from the ball.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AsrSettingsSection(
    settings: Settings?,
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    requestModelDownload: (label: String, onConfirmed: () -> Unit) -> Unit,
    onSaved: (Settings) -> Unit,
    onCredentialsEdited: ((Settings) -> Settings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val engine = settings?.asrEngine ?: AsrEngineKind.SENSE_VOICE
    val variant = settings?.asrWhisperVariant ?: AsrWhisperVariant.TINY
    val cloudModel = settings?.asrCloudModel ?: AsrCloudModel.PARAFORMER
    val provider = settings?.asrCloudProvider ?: AsrCloudProvider.ALIYUN
    val connections = settings?.asrCloudConnections ?: com.gameocr.app.data.AsrCloudConnections()
    val language = AsrLanguagePolicy.effective(engine, settings?.asrLanguage ?: AsrLanguage.AUTO, cloudModel, provider, connections)
    val model = AsrModelId.forSettings(engine, variant)

    fun save(transform: (Settings) -> Settings) {
        val current = settings ?: return
        val next = transform(current)
        onSaved(next)
        scope.launch {
            try {
                viewModel.saveAsrSettings(transform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onSaved(current)
                snackbarHostState.showSnackbar(error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

    SectionCard(title = stringResource(R.string.settings_asr_title)) {
        Text(
            stringResource(R.string.settings_asr_group_on_device),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf(
                AsrEngineKind.SENSE_VOICE to "SenseVoice",
                AsrEngineKind.ZIPFORMER to "Zipformer",
                AsrEngineKind.WHISPER to "Whisper",
            ).forEach { (option, label) ->
                EngineChip(engine, option, label, enabled = settings != null) { picked ->
                    save { it.copy(asrEngine = picked) }
                }
            }
        }
        Text(
            stringResource(R.string.settings_asr_group_audio_api),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EngineChip(provider, AsrCloudProvider.OPENAI_COMPATIBLE,
                stringResource(R.string.settings_asr_openai), enabled = settings != null,
                selected = engine.isCloud && provider == AsrCloudProvider.OPENAI_COMPATIBLE) { picked ->
                save { it.copy(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = picked) }
            }
        }
        Text(
            stringResource(R.string.settings_asr_group_cloud),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf(
                AsrCloudProvider.ALIYUN to R.string.settings_asr_aliyun,
                AsrCloudProvider.TENCENT to R.string.settings_ocr_tencent,
                AsrCloudProvider.VOLCENGINE to R.string.settings_engine_volc,
            ).forEach { (option, label) ->
                EngineChip(provider, option, stringResource(label), enabled = settings != null,
                    selected = engine.isCloud && provider == option) { picked ->
                    save { it.copy(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = picked) }
                }
            }
        }

        if (model != null) {
            OnDeviceModelSection(
                model = model,
                engine = engine,
                variant = variant,
                settings = settings,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                requestModelDownload = requestModelDownload,
                save = ::save,
            )
        } else {
            CloudEngineSection(settings = settings, save = ::save, onCredentialsEdited = onCredentialsEdited)
        }

        val languageOptions = AsrLanguagePolicy.options(engine, cloudModel, provider, connections)
        if (languageOptions.isEmpty()) {
            Text(stringResource(R.string.settings_asr_language), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.settings_asr_language_zh_en), style = MaterialTheme.typography.bodyMedium)
        } else {
            SettingsOptionDropdown(
                label = stringResource(R.string.settings_asr_language),
                value = language,
                options = languageOptions,
                optionLabel = { asrLanguageLabel(it) },
                onValueChange = { picked -> save { it.copy(asrLanguage = picked) } },
            )
        }
        Text(
            stringResource(
                when (engine) {
                    AsrEngineKind.SENSE_VOICE -> R.string.settings_asr_note_sense_voice
                    AsrEngineKind.ZIPFORMER -> R.string.settings_asr_note_zipformer
                    AsrEngineKind.WHISPER, AsrEngineKind.PARAFORMER_CLOUD -> R.string.settings_asr_note_general
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MaxRecordsRow(settings = settings, save = ::save)
    }
}

@Composable
private fun OnDeviceModelSection(
    model: AsrModelId,
    engine: AsrEngineKind,
    variant: AsrWhisperVariant,
    settings: Settings?,
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    requestModelDownload: (label: String, onConfirmed: () -> Unit) -> Unit,
    save: ((Settings) -> Settings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var readiness by remember { mutableStateOf<Map<AsrModelId, Boolean>>(emptyMap()) }
    var failures by remember { mutableStateOf<Map<AsrModelId, String>>(emptyMap()) }
    var startingModels by remember { mutableStateOf<Set<AsrModelId>>(emptySet()) }
    var checkedWorkIds by remember { mutableStateOf<Map<AsrModelId, UUID?>>(emptyMap()) }
    var workIds by remember { mutableStateOf<Map<AsrModelId, UUID>>(emptyMap()) }
    var importTarget by remember { mutableStateOf<AsrModelId?>(null) }
    var importingModel by remember { mutableStateOf<AsrModelId?>(null) }
    val importing = importingModel == model
    var confirmDelete by remember { mutableStateOf(false) }
    suspend fun refresh(target: AsrModelId) {
        val ready = withContext(Dispatchers.IO) { viewModel.asrModelReady(target) }
        readiness = readiness + (target to ready)
    }

    val workInfos by viewModel.modelDownloadWorkInfos.collectAsState(initial = emptyList())
    val selectedWork = AsrModelWorkUi.latest(workInfos, ModelDownloadSpec.asr(model), workIds[model])
    val active = selectedWork?.takeIf { !it.state.isFinished }
    val ownProgress = AsrModelWorkUi.ownProgress(active, ModelDownloadSpec.asr(model))
    val downloaded = active?.progress?.takeIf { ownProgress && it.keyValueMap.containsKey(ModelDownloadWorker.KEY_DOWNLOADED) }
        ?.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0L)
    val total = active?.progress?.getLong(ModelDownloadWorker.KEY_TOTAL, -1L)?.takeIf { ownProgress && it > 0 }
    val modelState = AsrModelUiPolicy.state(
        ready = readiness[model] == true,
        failed = selectedWork?.state == WorkInfo.State.FAILED || model in failures,
        downloaded = downloaded, total = total,
        busy = active != null || model in startingModels,
        verifying = ownProgress && active?.state == WorkInfo.State.RUNNING && active.progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
        importing = importing,
        checking = readiness[model] == null || (selectedWork?.state == WorkInfo.State.SUCCEEDED && checkedWorkIds[model] != selectedWork.id),
    )
    LaunchedEffect(model, selectedWork?.id, selectedWork?.state) {
        refresh(model)
        checkedWorkIds = checkedWorkIds + (model to selectedWork?.id)
    }
    val workerStatus = active?.progress?.getString(ModelDownloadWorker.KEY_STATUS)?.takeIf { ownProgress && it.isNotBlank() }
    val failureStatus = selectedWork?.takeIf { it.state == WorkInfo.State.FAILED }
        ?.outputData?.getString(ModelDownloadWorker.KEY_STATUS)?.takeIf { it.isNotBlank() }
        ?: failures[model]?.let { stringResource(R.string.model_download_failed_format, it) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = importTarget ?: return@rememberLauncherForActivityResult
        importTarget = null
        importingModel = target
        scope.launch {
            val message = try {
                when (val result = viewModel.importAsrModel(target, uris)) {
                    AsrImportResult.Imported -> resources.getString(R.string.settings_asr_import_done)
                    is AsrImportResult.MissingFile ->
                        resources.getString(R.string.settings_asr_import_missing, result.name)
                    is AsrImportResult.Mismatch, is AsrImportResult.Failed ->
                        resources.getString(R.string.settings_asr_import_mismatch, target.displayName)
                }
            } finally {
                try { refresh(target) } finally {
                    importingModel = null
                    failures = failures - target
                }
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    if (engine == AsrEngineKind.WHISPER) {
        Text(stringResource(R.string.settings_asr_whisper_variant), style = MaterialTheme.typography.labelLarge)
        SettingsChoiceRow(Modifier.fillMaxWidth()) {
            listOf(AsrWhisperVariant.TINY to "tiny", AsrWhisperVariant.BASE to "base").forEach { (option, label) ->
                SettingsChoiceButton(
                    selected = variant == option,
                    enabled = settings != null,
                    onClick = { save { it.copy(asrWhisperVariant = option) } },
                    label = { Text(label) },
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelVersionRow(model.displayName)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (AsrModelUiPolicy.isBusy(modelState)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Box(modifier = Modifier.size(8.dp))
            }
            Text(when (modelState) {
                is AsrModelUiState.Downloading, AsrModelUiState.Queued -> workerStatus ?: asrModelStatusText(modelState, model)
                AsrModelUiState.Failed -> failureStatus ?: asrModelStatusText(modelState, model)
                else -> asrModelStatusText(modelState, model)
            }, style = MaterialTheme.typography.bodyMedium)
        }
        if (active != null && downloaded != null) {
            ModelDownloadProgressLine(
                downloaded = downloaded,
                total = total ?: -1L,
                bytesPerSecond = active.progress.getLong(ModelDownloadWorker.KEY_SPEED, 0L),
                active = active.state == WorkInfo.State.RUNNING && active.progress.getBoolean(ModelDownloadWorker.KEY_ACTIVE, true) && modelState is AsrModelUiState.Downloading,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = settings != null && AsrModelUiPolicy.canDownload(modelState),
                onClick = {
                    val target = model
                    requestModelDownload(target.displayName) {
                        failures = failures - target
                        startingModels = startingModels + target
                        scope.launch {
                            var terminalState: WorkInfo.State? = null
                            try {
                                viewModel.downloadAsrModel(target,
                                    onEnqueued = { id -> workIds = workIds + (target to id) },
                                    onWorkInfo = { terminalState = it.state })
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (terminalState != WorkInfo.State.CANCELLED) failures = failures + (target to error.message.orEmpty())
                            } finally {
                                try { refresh(target) } finally {
                                    startingModels = startingModels - target
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(
                    if (AsrModelUiPolicy.isBusy(modelState)) R.string.settings_paddle_btn_processing
                    else R.string.settings_paddle_btn_auto_download
                ))
            }
            OutlinedButton(
                enabled = settings != null && AsrModelUiPolicy.canImport(modelState),
                onClick = { importTarget = model; importLauncher.launch("*/*") },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.settings_paddle_btn_local_import)) }
        }
        if (active != null) {
            OutlinedButton(onClick = { viewModel.cancelModelDownload(active.id) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_asr_cancel_download))
            }
        }
        OutlinedButton(
            enabled = AsrModelUiPolicy.canDelete(modelState),
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_paddle_btn_delete)) }
        if (confirmDelete) {
            CatalystAlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
                text = { Text(stringResource(R.string.settings_model_delete_confirm_message)) },
                confirmButton = {
                    DestructiveTextButton(label = stringResource(R.string.settings_model_delete_confirm_yes), onClick = {
                        confirmDelete = false
                        val target = model
                        scope.launch {
                            viewModel.deleteAsrModel(target)
                            refresh(target)
                            snackbarHostState.showSnackbar(resources.getString(R.string.settings_asr_deleted))
                        }
                    })
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.settings_model_delete_confirm_no)) }
                },
            )
        }
    }
}

@Composable
private fun CloudEngineSection(settings: Settings?, save: ((Settings) -> Settings) -> Unit,
    onCredentialsEdited: ((Settings) -> Settings) -> Unit) {
    val provider = settings?.asrCloudProvider ?: AsrCloudProvider.ALIYUN
    if (provider != AsrCloudProvider.ALIYUN) {
        key(provider) {
            val connection = settings?.asrCloudConnections ?: com.gameocr.app.data.AsrCloudConnections()
            when (provider) {
                AsrCloudProvider.TENCENT -> {
                    SettingsOptionDropdown(
                        label = stringResource(R.string.settings_asr_cloud_model),
                        value = connection.tencentModel,
                        options = com.gameocr.app.data.TencentAsrModel.entries,
                        optionLabel = { stringResource(when (it) {
                            com.gameocr.app.data.TencentAsrModel.STANDARD -> R.string.asr_model_tencent_standard
                            com.gameocr.app.data.TencentAsrModel.TRANSLATE_LITE -> R.string.asr_model_tencent_translate_lite
                            com.gameocr.app.data.TencentAsrModel.TRANSLATE_STANDARD -> R.string.asr_model_tencent_translate_standard
                            com.gameocr.app.data.TencentAsrModel.GENERAL -> R.string.asr_model_tencent_v2
                            com.gameocr.app.data.TencentAsrModel.SPEAKER -> R.string.asr_model_tencent_v2_speaker
                        }) },
                        onValueChange = { value -> save { it.copy(asrCloudConnections = it.asrCloudConnections.copy(tencentModel = value)) } },
                    )
                    AsrConnectionField(connection.tencentAppId, stringResource(R.string.settings_asr_tencent_app_id)) { value ->
                        onCredentialsEdited { it.copy(asrCloudConnections = it.asrCloudConnections.copy(tencentAppId = value)) }
                    }
                    AsrConnectionField(settings?.tencentSecretId.orEmpty(), stringResource(R.string.settings_tencent_id), secret = true) { value ->
                        onCredentialsEdited { it.copy(tencentSecretId = value) }
                    }
                    AsrConnectionField(settings?.tencentSecretKey.orEmpty(), stringResource(R.string.settings_tencent_key), secret = true) { value ->
                        onCredentialsEdited { it.copy(tencentSecretKey = value) }
                    }
                }
                AsrCloudProvider.VOLCENGINE -> {
                    SettingsOptionDropdown(
                        label = stringResource(R.string.settings_asr_cloud_model),
                        value = connection.volcResource,
                        options = com.gameocr.app.data.VolcAsrResource.entries,
                        optionLabel = { stringResource(when (it) {
                            com.gameocr.app.data.VolcAsrResource.V2_DURATION -> R.string.asr_model_volc_v2_duration
                            com.gameocr.app.data.VolcAsrResource.V2_CONCURRENT -> R.string.asr_model_volc_v2_concurrent
                        }) },
                        onValueChange = { value -> save { it.copy(asrCloudConnections = it.asrCloudConnections.copy(volcResource = value)) } },
                    )
                    AsrConnectionField(connection.volcApiKey, stringResource(R.string.settings_api_key), secret = true) { value ->
                        onCredentialsEdited { it.copy(asrCloudConnections = it.asrCloudConnections.copy(volcApiKey = value)) }
                    }
                }
                AsrCloudProvider.OPENAI_COMPATIBLE -> {
                    AsrConnectionField(connection.openAiBaseUrl, stringResource(R.string.settings_base_url)) { value ->
                        onCredentialsEdited { it.copy(asrCloudConnections = it.asrCloudConnections.copy(openAiBaseUrl = value)) }
                    }
                    AsrConnectionField(connection.openAiModel, stringResource(R.string.settings_model)) { value ->
                        onCredentialsEdited { it.copy(asrCloudConnections = it.asrCloudConnections.copy(openAiModel = value)) }
                    }
                    AsrConnectionField(connection.openAiApiKey, stringResource(R.string.settings_api_key), secret = true) { value ->
                        onCredentialsEdited { it.copy(asrCloudConnections = it.asrCloudConnections.copy(openAiApiKey = value)) }
                    }
                }
                else -> Unit
            }
        }
        return
    }
    SettingsOptionDropdown(
        label = stringResource(R.string.settings_asr_cloud_model),
        value = settings?.asrCloudModel ?: AsrCloudModel.PARAFORMER,
        options = AsrCloudModel.entries,
        optionLabel = { it.modelId },
        onValueChange = { picked -> save { it.copy(asrCloudModel = picked) } },
    )
    SecretTextField(
        value = settings?.asrDashScopeApiKey.orEmpty(),
        onValueChange = { value -> onCredentialsEdited { it.copy(asrDashScopeApiKey = value) } },
        label = stringResource(R.string.settings_asr_cloud_api_key),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AsrConnectionField(value: String, label: String, secret: Boolean = false, onValueChange: (String) -> Unit) {
    if (secret) SecretTextField(value, onValueChange, label = label, modifier = Modifier.fillMaxWidth())
    else OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MaxRecordsRow(settings: Settings?, save: ((Settings) -> Settings) -> Unit) {
    val saved = settings?.asrMaxDisplayedRecords ?: AsrDisplayPolicy.DEFAULT_RECORDS
    var value by remember(saved) { mutableFloatStateOf(saved.toFloat()) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.settings_asr_max_records),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(value.roundToInt().toString(), style = MaterialTheme.typography.labelLarge)
    }
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = {
            val picked = AsrDisplayPolicy.clampRecords(value.roundToInt())
            if (picked != saved) save { it.copy(asrMaxDisplayedRecords = picked) }
        },
        valueRange = AsrDisplayPolicy.MIN_RECORDS.toFloat()..AsrDisplayPolicy.MAX_RECORDS.toFloat(),
        steps = AsrDisplayPolicy.MAX_RECORDS - AsrDisplayPolicy.MIN_RECORDS - 1,
        enabled = settings != null,
    )
}

@Composable
private fun asrModelStatusText(state: AsrModelUiState, model: AsrModelId): String {
    return when (state) {
        AsrModelUiState.Checking -> stringResource(R.string.settings_paddle_status_checking)
        AsrModelUiState.Queued -> stringResource(R.string.settings_paddle_btn_processing)
        AsrModelUiState.Importing -> stringResource(R.string.settings_asr_model_importing_format, model.displayName)
        AsrModelUiState.NotDownloaded -> stringResource(R.string.settings_asr_model_missing_hint, model.displayName)
        is AsrModelUiState.Downloading -> state.percent
            ?.let { stringResource(R.string.settings_asr_state_downloading, "$it%") }
            ?: stringResource(R.string.settings_asr_state_downloading, "")
        AsrModelUiState.Verifying -> stringResource(R.string.settings_asr_model_verifying_format, model.displayName)
        AsrModelUiState.Ready -> stringResource(R.string.settings_asr_model_ready_format, model.displayName, AsrModelUiPolicy.sizeMegabytes(model))
        AsrModelUiState.Failed -> stringResource(R.string.settings_asr_state_failed)
    }
}

@Composable
private fun asrLanguageLabel(language: AsrLanguage): String = when (language) {
    AsrLanguage.ZH_EN -> stringResource(R.string.settings_asr_language_zh_en)
    AsrLanguage.AUTO -> stringResource(R.string.settings_asr_language_auto)
    AsrLanguage.ZH -> stringResource(R.string.settings_asr_language_zh)
    AsrLanguage.EN -> stringResource(R.string.settings_asr_language_en)
    AsrLanguage.JA -> stringResource(R.string.settings_asr_language_ja)
    AsrLanguage.KO -> stringResource(R.string.settings_asr_language_ko)
    AsrLanguage.YUE -> stringResource(R.string.settings_asr_language_yue)
    else -> com.gameocr.app.data.Languages.nameOf(LocalContext.current, when (language) {
        AsrLanguage.JW -> "jv"
        AsrLanguage.FIL -> "tl"
        AsrLanguage.NO -> "nb"
        else -> language.code
    })
}

