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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class GlossaryViewModel @Inject constructor(
    private val glossaryRepository: TranslationGlossaryRepository,
    private val foregroundAppResolver: ForegroundAppResolver,
    private val installedAppCatalog: InstalledAppCatalog,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val terms = glossaryRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
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
}
