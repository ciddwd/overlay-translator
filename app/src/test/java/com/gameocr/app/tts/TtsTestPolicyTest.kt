package com.gameocr.app.tts

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTestPolicyTest {

    @Test
    fun testLanguage_usesDetectedScriptAndConfiguredFallback() {
        data class Case(
            val name: String,
            val text: String,
            val fallback: String,
            val expected: String,
        )

        listOf(
            Case("Chinese overrides Japanese target", "你好，这是语音测试。", "ja", "zh-CN"),
            Case("Japanese kana overrides Chinese target", "今日は音声テストです。", "zh-CN", "ja"),
            Case("English overrides Chinese target", "This is a speech test.", "zh-CN", "en"),
            Case("Korean overrides English target", "안녕하세요.", "en", "ko"),
            Case("punctuation uses configured fallback", "123!?", "ja-JP", "ja-JP"),
            Case("unknown text and auto fallback remain auto", "123!?", "auto", "auto"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, ttsTestLanguageTag(case.text, case.fallback))

            val prepared = settingsForTtsTest(
                case.text,
                Settings(ttsEnabled = false, targetLang = case.fallback),
            )
            assertTrue(case.name, prepared.ttsEnabled)
            assertEquals(case.name, case.expected, prepared.targetLang)
        }
    }

    @Test
    fun providerChange_resetsFeedbackOnlyWhenProviderActuallyChanges() {
        TtsProvider.entries.forEach { current ->
            TtsProvider.entries.forEach { next ->
                val expected = current != next
                assertEquals(
                    "${current.name} -> ${next.name}",
                    expected,
                    shouldResetTtsTestFeedback(current, next),
                )
            }
            assertFalse(shouldResetTtsTestFeedback(current, current))
        }
    }

    @Test
    fun costWarning_tableDriven_isHiddenForSystemTtsOnly() {
        data class Case(val provider: TtsProvider, val expected: Boolean)

        listOf(
            Case(TtsProvider.SYSTEM, false),
            Case(TtsProvider.GENERIC_HTTP, true),
            Case(TtsProvider.VOLCENGINE, true),
            Case(TtsProvider.MINIMAX, true),
            Case(TtsProvider.MIMO, true),
        ).forEach { case ->
            assertEquals(
                case.provider.name,
                case.expected,
                ttsTestMayIncurCharges(case.provider),
            )
        }
    }

    @Test
    fun providerGroup_tableDriven_separatesOnDeviceLocalAndOnlineEngines() {
        data class Case(val provider: TtsProvider, val expected: TtsProviderGroup)

        listOf(
            Case(TtsProvider.SYSTEM, TtsProviderGroup.ON_DEVICE),
            Case(TtsProvider.GENERIC_HTTP, TtsProviderGroup.LOCAL),
            Case(TtsProvider.VOLCENGINE, TtsProviderGroup.ONLINE),
            Case(TtsProvider.MINIMAX, TtsProviderGroup.ONLINE),
            Case(TtsProvider.MIMO, TtsProviderGroup.ONLINE),
        ).forEach { case ->
            assertEquals(case.provider.name, case.expected, ttsProviderGroup(case.provider))
        }
    }
}
