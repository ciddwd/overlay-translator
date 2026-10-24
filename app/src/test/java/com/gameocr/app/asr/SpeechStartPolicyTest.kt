package com.gameocr.app.asr

import com.gameocr.app.data.AsrEngineKind
import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechStartPolicyTest {
    @Test fun prerequisitesAreCheckedBeforeAnyPrompt_tableDriven() {
        data class Case(val engine: AsrEngineKind, val key: String, val installed: Boolean?, val expected: SpeechStartBlock?)
        listOf(
            Case(AsrEngineKind.SENSE_VOICE, "", true, null),
            Case(AsrEngineKind.SENSE_VOICE, "", false, SpeechStartBlock.MODEL_MISSING),
            Case(AsrEngineKind.WHISPER, "sk", false, SpeechStartBlock.MODEL_MISSING),
            Case(AsrEngineKind.ZIPFORMER, "", null, SpeechStartBlock.MODEL_MISSING),
            Case(AsrEngineKind.PARAFORMER_CLOUD, "sk-1", null, null),
            Case(AsrEngineKind.PARAFORMER_CLOUD, "", null, SpeechStartBlock.CLOUD_NOT_CONFIGURED),
            Case(AsrEngineKind.PARAFORMER_CLOUD, "   ", null, SpeechStartBlock.CLOUD_NOT_CONFIGURED),
            // A downloaded on-device model never substitutes for a missing cloud key.
            Case(AsrEngineKind.PARAFORMER_CLOUD, "", true, SpeechStartBlock.CLOUD_NOT_CONFIGURED),
        ).forEach { case ->
            val settings = Settings(asrEngine = case.engine, asrDashScopeApiKey = case.key)
            assertEquals(case.toString(), case.expected, SpeechStartPolicy.blockReason(settings, case.installed))
        }
    }
}
