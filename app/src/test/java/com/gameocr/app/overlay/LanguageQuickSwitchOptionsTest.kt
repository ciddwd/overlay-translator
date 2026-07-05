package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LanguageQuickSwitchOptionsTest {

    @Test
    fun ordered_prioritizesCurrentPinnedThenCommonLanguages() {
        data class Case(
            val name: String,
            val slot: LanguageSlot,
            val pinned: List<String>,
            val currentSource: String,
            val currentTarget: String,
            val expectedPrefix: List<String>
        )

        val cases = listOf(
            Case(
                name = "source-keeps-auto-as-selectable",
                slot = LanguageSlot.SOURCE,
                pinned = listOf("ko", "ja", "auto"),
                currentSource = "en",
                currentTarget = "zh-CN",
                expectedPrefix = listOf("en", "ko", "ja", "auto", "zh-CN", "zh-TW")
            ),
            Case(
                name = "target-skips-auto-and-keeps-current-first",
                slot = LanguageSlot.TARGET,
                pinned = listOf("auto", "ja", "ko"),
                currentSource = "auto",
                currentTarget = "zh-CN",
                expectedPrefix = listOf("zh-CN", "ja", "ko", "en", "zh-TW")
            )
        )

        cases.forEach { case ->
            val codes = LanguageQuickSwitchOptions.ordered(
                slot = case.slot,
                pinned = case.pinned,
                currentSource = case.currentSource,
                currentTarget = case.currentTarget
            ).map { it.code }

            assertEquals(case.name, case.expectedPrefix, codes.take(case.expectedPrefix.size))
        }
    }

    @Test
    fun ordered_targetLanguagesNeverIncludeAuto() {
        val codes = LanguageQuickSwitchOptions.ordered(
            slot = LanguageSlot.TARGET,
            pinned = listOf("auto"),
            currentSource = "ja",
            currentTarget = "auto"
        ).map { it.code }

        assertFalse(codes.contains("auto"))
    }
}
