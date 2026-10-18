package com.gameocr.app.service

import com.gameocr.app.data.AsrEngineKind
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrWhisperVariant
import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeechModePolicyTest {
    @Test fun sessionKeyChangesOnlyWithRecognizerInputs_tableDriven() {
        val base = Settings(asrEngine = AsrEngineKind.WHISPER, asrWhisperVariant = AsrWhisperVariant.TINY)
        listOf(
            base.copy(asrWhisperVariant = AsrWhisperVariant.BASE) to true,
            base.copy(asrLanguage = AsrLanguage.JA) to true,
            // Cantonese is not available for Whisper, so it resolves to the same automatic language.
            base.copy(asrLanguage = AsrLanguage.YUE) to false,
            base.copy(asrMaxDisplayedRecords = 30) to false,
            // The cloud key matters only for the cloud engine.
            base.copy(asrDashScopeApiKey = "sk-1") to false,
            base.copy(asrEngine = AsrEngineKind.SENSE_VOICE) to true,
            base.copy(asrEngine = AsrEngineKind.PARAFORMER_CLOUD) to true,
        ).forEach { (next, changes) ->
            val before = SpeechModePolicy.sessionKey(base)
            val after = SpeechModePolicy.sessionKey(next)
            if (changes) assertNotEquals(next.toString(), before, after) else assertEquals(next.toString(), before, after)
        }
    }

    @Test fun cloudKeyChangeRestartsTheCloudSessionWithoutExposingTheKey() {
        val cloud = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrDashScopeApiKey = "sk-secret-1")
        val rotated = cloud.copy(asrDashScopeApiKey = "sk-secret-2")
        val padded = cloud.copy(asrDashScopeApiKey = "  sk-secret-1 ")
        assertNotEquals(SpeechModePolicy.sessionKey(cloud), SpeechModePolicy.sessionKey(rotated))
        assertEquals(SpeechModePolicy.sessionKey(cloud), SpeechModePolicy.sessionKey(padded))
        assertFalse(SpeechModePolicy.sessionKey(cloud).contains("sk-secret"))
    }
}
