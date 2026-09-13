package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordSelectCapturePolicyTest {
    @Test
    fun extraction_tableDriven_forcesCardWithoutChangingSelectionMemoryOrSavedCardChoice() {
        val region = CaptureRegion(10, 20, 300, 400)
        for (extract in listOf(false, true)) {
            for (cardMode in listOf(false, true)) {
                for (remember in listOf(false, true)) {
                    val plan = wordSelectCapturePlan(remember, cardMode, region, extract)
                    val name = "extract=$extract card=$cardMode remember=$remember"
                    assertEquals(name, remember, plan.saveLastSelection)
                    assertEquals(name, extract, plan.extractTextOnly)
                    assertEquals(name, extract || cardMode, plan.useTranslationCard)
                    assertEquals(name, region.takeUnless { extract || cardMode }, plan.captureRegionOverride)
                    assertEquals(name, cardMode, wordSelectCapturePlan(remember, cardMode, region).useTranslationCard)
                }
            }
        }
    }

    @Test
    fun recognizedText_tableDriven_preservesExtractedBlocksWithoutAlteringTranslationInput() {
        data class Case(val blocks: List<String>, val extracted: String, val translated: String)
        listOf(
            Case(emptyList(), "", ""),
            Case(listOf("  ", "\n"), "", ""),
            Case(listOf(" hello "), "hello", "hello"),
            Case(listOf("第一行", "第二行"), "第一行\n第二行", "第一行 第二行"),
            Case(listOf("안녕\n하세요", " 世界 "), "안녕\n하세요\n世界", "안녕\n하세요 世界"),
            Case(listOf("a", "  ", "b"), "a\nb", "a  b"),
            Case(listOf("x".repeat(10000)), "x".repeat(10000), "x".repeat(10000)),
        ).forEach { case ->
            assertEquals(case.extracted, wordSelectRecognizedText(case.blocks, true))
            assertEquals(case.translated, wordSelectRecognizedText(case.blocks, false))
        }
    }

    @Test
    fun plan_tableDriven_keepsSelectionMemorySeparateFromSharedCaptureRegion() {
        data class Case(
            val name: String,
            val remember: Boolean,
            val cardMode: Boolean,
            val expectedSave: Boolean,
            val expectedOverride: CaptureRegion?,
        )
        val selected = CaptureRegion(10, 20, 300, 400)
        val cases = listOf(
            Case("card without memory", false, true, false, null),
            Case("card with memory", true, true, true, null),
            Case("overlay without memory", false, false, false, selected),
            Case("overlay with memory", true, false, true, selected),
        )

        cases.forEach { case ->
            val actual = wordSelectCapturePlan(
                rememberLastSelection = case.remember,
                useTranslationCard = case.cardMode,
                selectedRegion = selected,
            )
            assertEquals(case.name, case.expectedSave, actual.saveLastSelection)
            assertEquals(case.name, case.cardMode, actual.useTranslationCard)
            if (case.expectedOverride == null) {
                assertNull(case.name, actual.captureRegionOverride)
            } else {
                assertEquals(case.name, case.expectedOverride, actual.captureRegionOverride)
            }
        }
    }
}
