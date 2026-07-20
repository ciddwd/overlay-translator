package com.gameocr.app.tts

import com.gameocr.app.data.TtsProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackGainPolicyTest {

    @Test
    fun gainNormalization_tableDriven_clampsAndConvertsDecibelsToMillibels() {
        data class Case(
            val name: String,
            val inputDb: Int,
            val expectedDb: Int,
            val expectedMillibels: Int,
        )

        listOf(
            Case("below minimum", -3, 0, 0),
            Case("disabled", 0, 0, 0),
            Case("one decibel", 1, 1, 100),
            Case("middle", 12, 12, 1200),
            Case("maximum", 24, 24, 2400),
            Case("above maximum", 99, 24, 2400),
        ).forEach { case ->
            assertEquals(case.name, case.expectedDb, normalizedTtsPlaybackGainDb(case.inputDb))
            assertEquals(case.name, case.expectedMillibels, ttsPlaybackGainMillibels(case.inputDb))
        }
    }

    @Test
    fun providerSupport_tableDriven_limitsPostProcessingToAppOwnedAudio() {
        data class Case(val provider: TtsProvider, val expected: Boolean)

        listOf(
            Case(TtsProvider.SYSTEM, false),
            Case(TtsProvider.GENERIC_HTTP, true),
            Case(TtsProvider.VOLCENGINE, true),
            Case(TtsProvider.MINIMAX, true),
            Case(TtsProvider.MIMO, true),
        ).forEach { case ->
            assertEquals(case.provider.name, case.expected, supportsTtsPlaybackGain(case.provider))
        }
    }
}
