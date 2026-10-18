package com.gameocr.app.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.download.ModelDownloadManager
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.llm.LocalModelCatalog
import com.gameocr.app.llm.LocalModelConfig
import com.gameocr.app.llm.LocalModelEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

internal fun modelManagementSelectionSettings(settings: Settings, kind: LlmModelKind?, id: String): Settings =
    settings.copy(
        translatorEngine = localModelTranslatorEngine(kind),
        localModelId = id.takeIf { kind == null || kind.managedViaGeneric },
    )

/** Local model files and per-model parameters are deliberately separate from portable settings. */
@HiltViewModel
internal class ModelManagementViewModel @Inject constructor(
    private val catalog: LocalModelCatalog,
    private val repository: SettingsRepository,
    private val holder: LlamaEngineHolder,
    private val downloadManager: ModelDownloadManager,
    private val translator: com.gameocr.app.translate.Translator,
    private val recentSourceText: com.gameocr.app.translate.RecentSourceText,
) : ViewModel() {
    private val refreshLock = Mutex()
    private val _models = MutableStateFlow<List<LocalModelEntry>>(emptyList())
    val models = _models.asStateFlow()
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    val loadedModelId = holder.currentModelId
    val downloads = downloadManager.workInfos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _queuedDownloads = MutableStateFlow<Set<LlmModelKind>>(emptySet())
    val queuedDownloads = _queuedDownloads.asStateFlow()
    private val _queuedDownloadIds = MutableStateFlow<Map<LlmModelKind, UUID>>(emptyMap())
    val queuedDownloadIds = _queuedDownloadIds.asStateFlow()
    private val cancelledDownloads = mutableSetOf<UUID>()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val messageChannel = Channel<String?>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var importJob: Job? = null
    private val _trial = MutableStateFlow<ModelTrialState?>(null)
    val trial = _trial.asStateFlow()
    private val _importSheet = MutableStateFlow<ModelImportState?>(null)
    val importSheet = _importSheet.asStateFlow()

    val deviceCapable: Boolean get() = holder.isDeviceCapable()

    init {
        viewModelScope.launch {
            catalog.revision.collect { runOperation { refreshModels() } }
        }
        viewModelScope.launch {
            var lastStates: Map<UUID, WorkInfo.State> = emptyMap()
            downloadManager.workInfos.collect { infos ->
                val states = infos.associate { it.id to it.state }
                if (states != lastStates) {
                    lastStates = states
                    runOperation { refreshModels() }
                }
            }
        }
    }

    /**
     * Saves the draft first, so the trial runs exactly what the page shows, then translates text
     * through the normal path for this model. Without [source], the trial starts from the last real
     * text the app translated, or a game-style dialogue sample when there is none yet.
     */
    fun trySample(entry: LocalModelEntry, config: LocalModelConfig, source: String? = null) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                runOperation {
                    catalog.updateConfig(entry.id, config.normalized())
                    holder.applyManagedConfiguration(entry.id)
                    refreshModels()
                    val text = source?.trim()?.takeIf(String::isNotEmpty) ?: recentSourceText.latest
                    val settings = ModelTrialPolicy.settings(repository.get(), entry, usesSample = text == null)
                    val input = text ?: ModelTrialPolicy.sample(settings.sourceLang)
                    _trial.value = ModelTrialState(entry.id, config, input, null, running = true)
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = runCatching { translator.translate(input, settings) }
                        .onFailure { if (it is CancellationException) throw it }
                    _trial.value = ModelTrialState(entry.id, config, input, result.getOrNull()?.takeIf { it.isNotBlank() },
                        running = false, error = result.exceptionOrNull()?.message?.trim()?.take(400),
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt)
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Runs the open trial again with edited text, on the same model and saved parameters. */
    fun retryTrial(source: String) {
        val current = _trial.value ?: return
        val entry = _models.value.firstOrNull { it.id == current.entryId } ?: return
        trySample(entry, current.config, source)
    }

    fun dismissTrial() {
        _trial.value = null
    }

    /** Releases the loaded weights now; the holder waits for any running translation first. */
    fun unload() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                runOperation { holder.unload() }
            } finally {
                _busy.value = false
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { runOperation { refreshModels() } }
    }

    private suspend fun refreshModels() {
        refreshLock.withLock { _models.value = catalog.list() }
    }

    fun select(entry: LocalModelEntry, onSelected: (Settings) -> Unit = {}) {
        if (!entry.installed || !deviceCapable || _busy.value) return
        viewModelScope.launch {
            runOperation {
                // Re-resolve the file; a stale card must never select a model that has since been removed.
                val current = catalog.entry(entry.id) ?: return@runOperation
                if (!current.installed) return@runOperation
                repository.update { latest ->
                    modelManagementSelectionSettings(latest, current.kind, current.id)
                }
                val savedSettings = repository.get()
                if (selectedLocalModelId(savedSettings) == current.id) {
                    catalog.recordUse(current.id)
                    onSelected(savedSettings)
                }
            }
        }
    }

    /** Copies and checks one GGUF while the import sheet shows progress and the checks it passed. */
    fun importModel(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        importJob = viewModelScope.launch {
            try {
                val (name, total) = runCatching { catalog.fileInfo(uri) }.getOrDefault(null to null)
                _importSheet.value = ModelImportState(fileName = name ?: uri.lastPathSegment.orEmpty(), totalBytes = total)
                val entry = catalog.importModel(
                    uri,
                    onProgress = { copied -> _importSheet.update { it?.copy(copiedBytes = copied) } },
                    onCopied = { _importSheet.update { it?.copy(stage = ModelImportStage.CHECKING) } },
                )
                _importSheet.update { it?.copy(stage = ModelImportStage.DONE, entry = entry) }
            } catch (cancelled: CancellationException) {
                _importSheet.value = null
                throw cancelled
            } catch (error: Throwable) {
                Timber.w(error, "Local model import failed")
                _importSheet.update { it?.copy(stage = ModelImportStage.FAILED, error = error.message?.trim()?.take(400)) }
            } finally {
                _importing.value = false
                importJob = null
                runOperation { refreshModels() }
            }
        }
    }

    fun cancelImport() = importJob?.cancel()

    /** Applies the edited name, selects the model and closes the sheet. */
    fun finishImport(name: String, onSelected: (Settings) -> Unit) {
        val entry = _importSheet.value?.entry ?: return
        if (_busy.value) return
        viewModelScope.launch {
            runOperation {
                if (name.isNotBlank() && name.trim() != entry.name) catalog.renameImported(entry.id, name)
                refreshModels()
            }
            _importSheet.value = null
            catalog.entry(entry.id)?.let { select(it, onSelected) }
        }
    }

    /** Cancel stops a running copy; after a finished import it removes the model it just added. */
    fun discardImport() {
        val state = _importSheet.value ?: return
        when (state.stage) {
            ModelImportStage.COPYING, ModelImportStage.CHECKING -> cancelImport()
            ModelImportStage.DONE -> state.entry?.let { entry -> delete(entry) {} }
            ModelImportStage.FAILED -> Unit
        }
        _importSheet.value = null
    }

    suspend fun defaultConfig(id: String): LocalModelConfig = catalog.defaultConfig(id)

    fun download(kind: LlmModelKind) {
        if (kind in _queuedDownloads.value || !deviceCapable) return
        _queuedDownloads.value += kind
        viewModelScope.launch {
            var workId: UUID? = null
            try {
                runOperation(suppressFailure = { workId in cancelledDownloads }) {
                    downloadManager.enqueueAndAwait(listOf(ModelDownloadSpec.llm(kind)), onProgress = {}, onEnqueued = { id ->
                        workId = id
                        _queuedDownloadIds.value += kind to id
                    })
                }
            } finally {
                _queuedDownloads.value -= kind
                _queuedDownloadIds.value -= kind
                workId?.let(cancelledDownloads::remove)
                runOperation { refreshModels() }
            }
        }
    }

    fun cancelDownload(id: UUID) {
        cancelledDownloads += id
        downloadManager.cancel(id)
    }

    fun save(entry: LocalModelEntry, config: LocalModelConfig, onSaved: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                runOperation {
                    catalog.updateConfig(entry.id, config.normalized())
                    holder.applyManagedConfiguration(entry.id)
                    refreshModels()
                    onSaved()
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(entry: LocalModelEntry, onDeleted: () -> Unit) {
        if (_busy.value || _importing.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                runOperation {
                    if (holder.deleteManagedModel(entry)) {
                        // A removed import must not stay selected; the generic preset becomes current.
                        if (entry.kind == null) repository.update { latest ->
                            if (latest.localModelId == entry.id) latest.copy(localModelId = null) else latest
                        }
                        refreshModels()
                        onDeleted()
                    } else {
                        messageChannel.trySend(null)
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun runOperation(suppressFailure: () -> Boolean = { false }, operation: suspend () -> Unit) {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Timber.w(error, "Local model management operation failed")
            if (!suppressFailure()) messageChannel.trySend(error.message?.trim()?.take(400)?.takeIf { it.isNotBlank() })
        }
    }
}

internal fun localModelTranslatorEngine(kind: LlmModelKind?): TranslatorEngine = when (kind) {
    LlmModelKind.SAKURA_1_5B_Q4 -> TranslatorEngine.LOCAL_SAKURA
    LlmModelKind.HY_MT2_1_8B_Q4_K_M -> TranslatorEngine.LOCAL_HY_MT2
    LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M -> TranslatorEngine.LOCAL_INDEX_TRANSLATE
    else -> TranslatorEngine.LOCAL_QWEN3
}

internal fun selectedLocalModelId(settings: Settings): String? = when (settings.translatorEngine) {
    TranslatorEngine.LOCAL_SAKURA -> LocalModelCatalog.builtInId(LlmModelKind.SAKURA_1_5B_Q4)
    TranslatorEngine.LOCAL_HY_MT2 -> LocalModelCatalog.builtInId(LlmModelKind.HY_MT2_1_8B_Q4_K_M)
    TranslatorEngine.LOCAL_INDEX_TRANSLATE -> LocalModelCatalog.builtInId(LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M)
    TranslatorEngine.LOCAL_QWEN3 -> settings.localModelId
        ?: LocalModelCatalog.builtInId(LlmModelKind.QWEN3_0_6B_Q8_0)
    else -> null
}

internal data class ModelTrialState(
    val entryId: String,
    /** Saved parameters this trial ran with, reused when the text is edited and run again. */
    val config: LocalModelConfig,
    val source: String,
    val translation: String?,
    val running: Boolean,
    val error: String? = null,
    val elapsedMs: Long? = null,
)

/** Game-style dialogue per source language: names, address terms, ellipses and line breaks. */
internal object ModelTrialPolicy {
    private val samples = linkedMapOf(
        "ja" to "「お兄ちゃん、また徹夜したでしょ？」\n「……バレたか。セーブし忘れてさ」",
        "en" to "\"You stayed up all night again, didn't you?\"\n\"...Busted. I forgot to save.\"",
        "ko" to "\"오빠, 또 밤샜지?\"\n\"……들켰네. 세이브하는 걸 깜빡해서.\"",
        "zh-CN" to "“哥，你又熬夜了吧？”\n“……被发现了。忘了存档。”",
    )

    fun sample(sourceLang: String): String = samples[sourceLang] ?: samples.getValue("en")

    /**
     * Routes to this model with a language pair it accepts. Real text keeps the user's source
     * language; a built-in sample needs a language we have a sample for. Cache and translation
     * memory are skipped so the model actually runs and the timing is real.
     */
    fun settings(base: Settings, entry: LocalModelEntry, usesSample: Boolean = true): Settings {
        val routed = base.copy(
            translatorEngine = localModelTranslatorEngine(entry.kind),
            localModelId = entry.id.takeIf { entry.kind == null || entry.kind.managedViaGeneric },
            developerOptionsEnabled = true,
            disableTranslationCache = true,
            translationMemoryEnabled = false,
        )
        if (entry.kind == LlmModelKind.SAKURA_1_5B_Q4) return routed.copy(sourceLang = "ja", targetLang = "zh-CN")
        if (!usesSample) return routed
        val requested = base.sourceLang.trim().takeIf { it in samples }
        val source = (listOfNotNull(requested) + samples.keys).first { !it.equals(base.targetLang.trim(), ignoreCase = true) }
        return routed.copy(sourceLang = source)
    }
}

internal enum class ModelImportStage { COPYING, CHECKING, DONE, FAILED }

internal data class ModelImportState(
    val fileName: String,
    val totalBytes: Long?,
    val copiedBytes: Long = 0L,
    val stage: ModelImportStage = ModelImportStage.COPYING,
    val entry: LocalModelEntry? = null,
    val error: String? = null,
)
