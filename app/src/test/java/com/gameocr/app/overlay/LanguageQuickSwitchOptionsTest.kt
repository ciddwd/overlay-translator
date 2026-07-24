package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun conflictsWithOtherSlot_tableDriven_disablesOnlyMatchingOppositeLanguage() {
        data class Case(
            val name: String,
            val slot: LanguageSlot,
            val candidate: String,
            val source: String,
            val target: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("source matching target is disabled", LanguageSlot.SOURCE, "zh-CN", "ja", "zh-CN", true),
            Case("source different from target stays enabled", LanguageSlot.SOURCE, "en", "ja", "zh-CN", false),
            Case("target matching source is disabled", LanguageSlot.TARGET, "ja", "ja", "zh-CN", true),
            Case("target comparison ignores case", LanguageSlot.TARGET, "JA", "ja", "zh-CN", true),
            Case("automatic source stays available", LanguageSlot.SOURCE, "auto", "ja", "zh-CN", false),
        )

        cases.forEach { case ->
            val actual = LanguageQuickSwitchOptions.conflictsWithOtherSlot(
                slot = case.slot,
                candidateCode = case.candidate,
                currentSource = case.source,
                currentTarget = case.target,
            )
            if (case.expected) {
                assertTrue(case.name, actual)
            } else {
                assertFalse(case.name, actual)
            }
        }
    }
}
