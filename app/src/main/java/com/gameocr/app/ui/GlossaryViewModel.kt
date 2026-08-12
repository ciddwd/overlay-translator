package com.gameocr.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gameocr.app.appcontext.ForegroundApp
import com.gameocr.app.appcontext.ForegroundAppResolver
import com.gameocr.app.appcontext.InstalledAppCatalog
import com.gameocr.app.appcontext.SelectableApp
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.glossary.GlossaryTermEntity
import com.gameocr.app.glossary.TranslationGlossaryRepository
import com.gameocr.app.translate.TranslationMemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class GlossaryViewModel @Inject constructor(
    private val glossaryRepository: TranslationGlossaryRepository,
    private val foregroundAppResolver: ForegroundAppResolver,
    private val installedAppCatalog: InstalledAppCatalog,
    private val settingsRepository: SettingsRepository,
    private val translationMemoryRepository: TranslationMemoryRepository,
) : ViewModel() {
    init {
        viewModelScope.launch { glossaryRepository.ensureSourcePreservationPresets() }
    }

    val terms = glossaryRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val memories = translationMemoryRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val sourcePreservationEnabled: StateFlow<Boolean?> = settingsRepository.settings
        .map { settings -> settings.sourcePreservationEnabled }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    suspend fun currentApp(): ForegroundApp? {
        val settings = settingsRepository.get()
        return foregroundAppResolver.resolve(settings.foregroundAppDetectionMode)
    }

    suspend fun defaultLanguages(): Pair<String, String> {
        val settings = settingsRepository.get()
        return settings.sourceLang to settings.targetLang
    }

    suspend fun selectableApps(): List<SelectableApp> = installedAppCatalog.launchableApps()

    suspend fun upsert(term: GlossaryTermEntity): Long = glossaryRepository.upsert(term)

    suspend fun findConflict(term: GlossaryTermEntity): GlossaryTermEntity? =
        glossaryRepository.findConflict(term)

    suspend fun overwriteConflict(term: GlossaryTermEntity): Long =
        glossaryRepository.overwriteConflict(term)

    suspend fun delete(id: Long) = glossaryRepository.delete(id)

    fun setSourcePreservationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { settings ->
                settings.copy(sourcePreservationEnabled = enabled)
            }
        }
    }

    suspend fun updateMemory(
        id: Long,
        correctedSource: String,
        correctedTranslation: String,
    ): Boolean = translationMemoryRepository.updateCorrection(
        id = id,
        correctedSource = correctedSource,
        correctedTranslation = correctedTranslation,
    )

    suspend fun deleteMemory(id: Long) = translationMemoryRepository.delete(id)
}
