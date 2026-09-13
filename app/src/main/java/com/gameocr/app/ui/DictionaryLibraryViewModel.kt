package com.gameocr.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gameocr.app.data.DictionaryLookupMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.dictionary.DictionaryPackId
import com.gameocr.app.dictionary.DictionaryPackInstallResult
import com.gameocr.app.dictionary.DictionaryPackInstaller
import com.gameocr.app.dictionary.DictionaryPackStatus
import com.gameocr.app.dictionary.supportsOnlineDictionaryLookup
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DictionaryLibraryViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val installer: DictionaryPackInstaller,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Settings(),
    )

    private val _packStatuses = MutableStateFlow<List<DictionaryPackStatus>>(emptyList())
    val packStatuses: StateFlow<List<DictionaryPackStatus>> = _packStatuses

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _packStatuses.value = installer.statuses() }
    }

    suspend fun selectMode(mode: DictionaryLookupMode): Boolean {
        val current = settingsRepository.get()
        if (mode == DictionaryLookupMode.ONLINE &&
            !supportsOnlineDictionaryLookup(current.translatorEngine)
        ) {
            return false
        }
        settingsRepository.update { it.copy(dictionaryLookupMode = mode) }
        return true
    }

    fun setTapLookupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(dictionaryTapLookupEnabled = enabled) }
        }
    }

    suspend fun importPack(uri: Uri, id: DictionaryPackId): DictionaryPackInstallResult {
        val result = installer.importPack(
            uri = uri,
            expectedId = id,
        )
        _packStatuses.value = installer.statuses()
        return result
    }

    suspend fun deletePack(id: DictionaryPackId): Boolean {
        val deleted = installer.delete(id)
        _packStatuses.value = installer.statuses()
        return deleted
    }
}
