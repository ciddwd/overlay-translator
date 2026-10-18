package com.gameocr.app.asr

import com.gameocr.app.data.*
import org.junit.Assert.*
import org.junit.Test

class AsrCredentialDraftTest {
    @Test fun editingChangesOnlyTheFormUntilExplicitSave_tableDriven() {
        listOf("123457", "", "  123457  ").forEach { entered ->
            val baseline = Settings(asrCloudConnections = AsrCloudConnections(tencentAppId = "123456"))
            val draft = AsrCredentialDraft.from(baseline).copy(connections = baseline.asrCloudConnections.copy(tencentAppId = entered))
            val screen = draft.applyTo(baseline)
            assertEquals("123456", baseline.asrCloudConnections.tencentAppId)
            assertEquals(entered, screen.asrCloudConnections.tencentAppId)
            assertNotEquals(baseline, screen) // Existing unsaved-changes check must see this edit.
            assertEquals(entered.trim(), draft.applyTo(baseline, normalize = true).asrCloudConnections.tencentAppId)
        }
    }

    @Test fun staleCredentialDraftPreservesLatestModelChoicesAndUnrelatedCredentials() {
        val baseline = Settings(apiKey = "translation-key", tencentSecretId = "ocr-id", tencentSecretKey = "ocr-key", ttsHttpBearerToken = "tts-key")
        val draft = AsrCredentialDraft.from(baseline).copy(
            connections = AsrCloudConnections(tencentAppId = "123456", volcApiKey = "volc-key",
                openAiBaseUrl = "https://asr.example/v1", openAiApiKey = "audio-key", openAiModel = "audio-model"),
            dashScopeApiKey = "aliyun-key",
        )
        val current = baseline.copy(asrCloudProvider = AsrCloudProvider.TENCENT,
            asrCloudConnections = baseline.asrCloudConnections.copy(tencentModel = TencentAsrModel.SPEAKER, volcResource = VolcAsrResource.V2_CONCURRENT))
        val saved = draft.applyTo(current, normalize = true)
        assertEquals("123456", saved.asrCloudConnections.tencentAppId)
        assertEquals(TencentAsrModel.SPEAKER, saved.asrCloudConnections.tencentModel)
        assertEquals(VolcAsrResource.V2_CONCURRENT, saved.asrCloudConnections.volcResource)
        assertEquals(AsrCloudProvider.TENCENT, saved.asrCloudProvider)
        assertEquals(baseline.apiKey, saved.apiKey)
        assertEquals(baseline.tencentSecretId, saved.tencentSecretId)
        assertEquals(baseline.tencentSecretKey, saved.tencentSecretKey)
        assertEquals(baseline.ttsHttpBearerToken, saved.ttsHttpBearerToken)
        assertFalse(draft.toString().contains("speech-id"))
        assertFalse(draft.toString().contains("speech-key"))
    }
}
