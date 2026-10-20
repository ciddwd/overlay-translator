package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrCredentialDraft
import com.gameocr.app.data.Settings
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OpenAiAsrModelsTest {
    @Test fun modelsEndpoint_usesTheSameApiRootAsTranscription_tableDriven() {
        mapOf(
            "https://speech.example" to "https://speech.example/v1/models",
            "https://speech.example/" to "https://speech.example/v1/models",
            "https://speech.example/v1/" to "https://speech.example/v1/models",
            " https://speech.example/proxy/v1 " to "https://speech.example/proxy/v1/models",
            "https://speech.example/api" to "https://speech.example/api/models",
            "http://127.0.0.1:9000/v1/audio/transcriptions/" to "http://127.0.0.1:9000/v1/models",
            "https://speech.example/proxy%2Ftenant/v1" to "https://speech.example/proxy%2Ftenant/v1/models",
        ).forEach { (base, expected) ->
            assertEquals(base, expected, OpenAiAsrProtocol.modelsEndpoint(base).toString())
        }
    }

    @Test fun invalidOrCredentialBearingAddresses_neverCreateAModelEndpoint_tableDriven() {
        listOf("", "bad", "ftp://speech.example", "https://key@speech.example/v1",
            "https://user:secret@speech.example/v1", "https://speech.example/v1?key=secret",
            "https://speech.example/v1#fragment", "https://speech.example/v1/chat/completions",
            "https://speech.example/v1/messages").forEach { assertNull(it, OpenAiAsrProtocol.modelsEndpoint(it)) }
    }

    @Test fun modelSelection_isOnlyASpeechFormDraftUntilSave_tableDriven() {
        listOf("whisper-1", "org/SenseVoiceSmall", "custom-audio-model").forEach { picked ->
            val saved = Settings(baseUrl = "https://translation.example/v1/", apiKey = "translation-key", model = "translation-model",
                asrCloudConnections = AsrCloudConnections(openAiBaseUrl = "https://speech.example/v1", openAiApiKey = "speech-key"))
            val draft = AsrCredentialDraft.from(saved).copy(connections = saved.asrCloudConnections.copy(openAiModel = picked))
            val screen = draft.applyTo(saved)
            assertEquals(picked, screen.asrCloudConnections.openAiModel)
            assertEquals(saved, screen.copy(asrCloudConnections = saved.asrCloudConnections))
            assertEquals("FunAudioLLM/SenseVoiceSmall", saved.asrCloudConnections.openAiModel)
            assertEquals("speech-key", screen.asrCloudConnections.openAiApiKey)
            assertEquals(saved.model, screen.model)
        }
    }

    @Test fun uiSmoke_reusesTheModelPickerAndCancelsStaleRequestsWithoutSaving_tableDriven() {
        val ui = source("ui/AsrSettingsSection.kt")
        val selector = ui.substringAfter("private fun OpenAiAsrModelList(").substringBefore("private fun AsrConnectionField(")
        val viewModel = source("ui/SettingsViewModel.kt")
        val fetch = viewModel.substringAfter("suspend fun fetchAsrModels(").substringBefore("suspend fun testTranslator(")
        val sharedPanel = source("ui/TranslatorConnectionTestPanel.kt")
        listOf(
            "selector only in compatible cloud branch" to ui.substringAfter("AsrCloudProvider.OPENAI_COMPATIBLE -> {")
                .substringBefore("else -> Unit").contains("OpenAiAsrModelList(settings, viewModel, onCredentialsEdited)"),
            "current unsaved speech inputs" to source("ui/SettingsScreen.kt").substringAfter("AsrSettingsSection(")
                .substringBefore("// —— 译文显示").contains("settings = initialSettings?.let { buildSnapshot() }"),
            "changing URL, key or timeout clears state and cancels the request" to selector.contains(
                "key(connection.openAiBaseUrl, connection.openAiApiKey, settings.apiTimeoutSeconds)"),
            "shared request panel" to selector.contains("TranslatorConnectionTestPanel("),
            "shared model picker" to selector.contains("TranslatorModelPicker(fetchedModels)"),
            "selected model only changes the speech credential draft" to selector.contains(
                "it.copy(asrCloudConnections = it.asrCloudConnections.copy(openAiModel = model))"),
            "selection does not save" to (!selector.contains("saveAsrSettings") && !selector.contains("save {")),
            "fetch uses only speech credentials and normal network timeout" to fetch.contains(
                "modelListClient.fetch(url, connection.openAiApiKey, settings.apiTimeoutSeconds)"),
            "fetch does not save or probe translation" to (!fetch.contains("repo.") && !fetch.contains("connectionTester.") && !fetch.contains("routingTranslator.")),
            "cancellation propagates" to fetch.contains("throw cancelled"),
            "shared panel suppresses late results" to sharedPanel.contains("ensureActive()\n                                result = tested"),
            "shared panel clears old models on retry" to sharedPanel.contains("onModels(emptyList())"),
            "translation uses the same list-only client" to source("translate/OpenAiTranslator.kt").contains("modelListClient.fetch("),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/gameocr/app/$path"), File("app/src/main/java/com/gameocr/app/$path"),
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
