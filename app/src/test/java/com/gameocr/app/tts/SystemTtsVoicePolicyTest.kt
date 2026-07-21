package com.gameocr.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemTtsVoicePolicyTest {

    @Test
    fun orderedSystemTtsVoiceOptions_prioritizesLanguageAndOfflineVoices() {
        data class Case(
            val name: String,
            val preferredLanguageTag: String,
            val expectedNames: List<String>,
        )

        val voices = listOf(
            SystemTtsVoiceOption("z-net", "zh-CN", networkConnectionRequired = true),
            SystemTtsVoiceOption("tw-off", "zh-TW", networkConnectionRequired = false),
            SystemTtsVoiceOption("en-off", "en-US", networkConnectionRequired = false),
            SystemTtsVoiceOption("z-off", "zh-CN", networkConnectionRequired = false),
            SystemTtsVoiceOption("Z-OFF", "zh-CN", networkConnectionRequired = true),
            SystemTtsVoiceOption("  ", "ja-JP", networkConnectionRequired = false),
        )
        val cases = listOf(
            Case(
                name = "exact locale before same-language locale",
                preferredLanguageTag = "zh-CN",
                expectedNames = listOf("z-off", "z-net", "tw-off", "en-off"),
            ),
            Case(
                name = "same language before other languages",
                preferredLanguageTag = "zh-HK",
                expectedNames = listOf("z-off", "tw-off", "z-net", "en-off"),
            ),
            Case(
                name = "auto uses offline and locale ordering",
                preferredLanguageTag = "auto",
                expectedNames = listOf("en-off", "z-off", "tw-off", "z-net"),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedNames,
                orderedSystemTtsVoiceOptions(voices, case.preferredLanguageTag).map { it.name },
            )
        }
    }

    @Test
    fun shouldLoadSystemTtsVoices_requiresEnabledSystemProvider() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val systemProviderSelected: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("disabled system", false, true, false),
            Case("disabled non-system", false, false, false),
            Case("enabled non-system", true, false, false),
            Case("enabled system", true, true, true),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldLoadSystemTtsVoices(case.enabled, case.systemProviderSelected),
            )
        }
    }
}
