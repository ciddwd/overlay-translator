package com.gameocr.app.data

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AsrConnectionDefaultsTest {
    private val old = AsrCloudConnections(openAiBaseUrl = "https://api.siliconflow.cn/v1",
        openAiModel = "FunAudioLLM/SenseVoiceSmall")
    private class Store(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
    private fun repository(store: Store) = SettingsRepository(ContextWrapper(null), object : SettingsSecretCipher {
        override fun encrypt(plainText: String) = "test:$plainText"
        override fun decrypt(cipherText: String) = cipherText.removePrefix("test:")
    }, store).apply { setDefaultPromptProvidersForTest({ "default prompt" }, { "dictionary prompt" }) }
    private fun store(connection: AsrCloudConnections, provider: String = "OPENAI_COMPATIBLE") = Store(mutablePreferencesOf(
        stringPreferencesKey("asr_cloud_connections") to Json.encodeToString(connection),
        stringPreferencesKey("asr_cloud_provider") to provider,
        stringPreferencesKey("api_key") to "translation-key",
    ))

    @Test fun newInstallAndMissingSerializedFieldsHaveEmptyAudioConnectionFields() = runBlocking {
        val defaults = listOf(AsrCloudConnections(), Json.decodeFromString<AsrCloudConnections>("{}"),
            Json.decodeFromString<Settings>("{}").asrCloudConnections,
            repository(Store(mutablePreferencesOf())).get().asrCloudConnections)
        defaults.forEach { assertEquals("", it.openAiBaseUrl); assertEquals("", it.openAiModel) }
    }

    @Test fun onlyTheUnconfiguredLegacyPairIsCleared_tableDriven() = runBlocking {
        listOf(old to old.copy(openAiBaseUrl = "", openAiModel = ""),
            old.copy(openAiApiKey = "configured-key") to old.copy(openAiApiKey = "configured-key"),
            old.copy(openAiBaseUrl = "https://custom.example/v1") to old.copy(openAiBaseUrl = "https://custom.example/v1"),
            old.copy(openAiModel = "my-model") to old.copy(openAiModel = "my-model"),
            old.copy(openAiModel = "") to old.copy(openAiModel = "")).forEach { (input, expected) ->
            val actual = repository(store(input)).get()
            assertEquals(expected, actual.asrCloudConnections)
            assertEquals("translation-key", actual.apiKey)
        }
    }

    @Test fun legacyCleanupRunsOnceAndDoesNotEraseLaterExplicitChoices() = runBlocking {
        val store = store(old)
        val repo = repository(store)
        assertEquals("", repo.get().asrCloudConnections.openAiBaseUrl)
        repo.update { it.copy(apiTimeoutSeconds = 43) }
        assertEquals("", repository(store).get().asrCloudConnections.openAiModel)
        repo.update { it.copy(asrCloudConnections = old) }
        val restored = repository(store).get()
        assertEquals(old, restored.asrCloudConnections)
        assertEquals(43, restored.apiTimeoutSeconds)
        assertEquals("translation-key", restored.apiKey)
    }

    @Test fun withdrawnDebugProviderFallsBackToOpenAiWithoutDroppingConnectionData() = runBlocking {
        val connection = old.copy(openAiApiKey = "existing-key", glmApiKey = "retired-key", glmModel = "old-model")
        val store = store(connection, "GLM")
        val repo = repository(store)
        assertEquals(AsrCloudProvider.OPENAI_COMPATIBLE, repo.get().asrCloudProvider)
        repo.update { it.copy(apiTimeoutSeconds = 43) }
        assertEquals(connection, repository(store).get().asrCloudConnections)
        assertFalse(SettingsFieldPolicy.encodePortable(repo.get()).toString().contains("retired-key"))
    }

    @Test fun removedRecognitionHistoryFlagDoesNotDiscardOtherSavedHints() = runBlocking {
        val store = Store(mutablePreferencesOf(stringPreferencesKey("asr_hints") to
            """{"prompt":"scene","hotwords":"Winterfell","includeRecentTranscript":true,"useGlossary":true}"""))
        val repo = repository(store)
        assertEquals(AsrHintSettings(prompt = "scene", hotwords = "Winterfell", useGlossary = true), repo.get().asrHints)
        repo.update { it.copy(apiTimeoutSeconds = 43) }
        val encoded = SettingsFieldPolicy.encodePortable(repo.get()).toString()
        assertFalse(encoded.contains("includeRecentTranscript"))
        assertEquals("scene", repository(store).get().asrHints.prompt)
    }
}
