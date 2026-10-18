package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class MangaRecognizedTextConsolidatorTest {
    @Test
    fun savedPageResidualFragment_isConsolidatedAcrossScaleAxisAndOrder() {
        for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) for (reverse in listOf(false, true)) {
            fun rect(l: Int, t: Int, r: Int, b: Int) = if (transpose) {
                IntRect(t * scale, l * scale, b * scale, r * scale)
            } else IntRect(l * scale, t * scale, r * scale, b * scale)
            val owner = entry(rect(1060, 1303, 1186, 1545), model = 3, member = 0)
            val fragment = entry(rect(1109, 1296, 1203, 1543), member = 1)
            val entries = listOf(owner, fragment).let { if (reverse) it.reversed() else it }
            val texts = listOf("たかい...!たかいぞ!", "たかい...!").let { if (reverse) it.reversed() else it }
            val result = MangaRecognizedTextConsolidator.consolidate(entries, texts)
            assertEquals(1, result.entries.size)
            assertEquals(listOf("たかい...!たかいぞ!"), result.texts)
            assertEquals(listOf(0, 1), result.entries.single().bubble.memberIndices)
            assertEquals(3, result.entries.single().modelBubbleIndex)
            assertEquals(rect(1060, 1296, 1203, 1545), result.entries.single().bubble.contentRect)
            assertEquals(1, result.absorbedByOwner.size)
            assertEquals(listOf(if (reverse) 1 else 0), result.retainedIndices)
        }
    }

    @Test
    fun consolidationTable_requiresContentAndGeometryAndUnambiguousOwnership() {
        data class Case(
            val name: String,
            val fragmentBounds: IntRect = IntRect(80, 0, 180, 100),
            val text: String = "alpha beta",
            val free: Boolean = false,
            val model: Int? = null,
            val secondOwner: Boolean = false,
            val absorbed: Boolean,
        )
        listOf(
            Case("seventy percent boundary", absorbed = true),
            Case("below overlap boundary", fragmentBounds = IntRect(81, 0, 181, 100), absorbed = false),
            Case("same phrase elsewhere", fragmentBounds = IntRect(160, 0, 260, 100), absorbed = false),
            Case("different text in overlapping region", text = "other words", absorbed = false),
            Case("short fragment", text = "al", absorbed = false),
            Case("empty fragment", text = "...!", absorbed = false),
            Case("normalized case and punctuation", text = "ＡＬＰＨＡ...ＢＥＴＡ!", absorbed = true),
            Case("standalone free text", free = true, absorbed = false),
            Case("separate model bubble", model = 1, absorbed = false),
            Case("ambiguous owner", secondOwner = true, absorbed = false),
        ).forEach { case ->
            val owners = listOf(entry(IntRect(0, 0, 150, 100), model = 0, member = 0)) +
                if (case.secondOwner) listOf(entry(IntRect(20, 0, 170, 100), model = 2, member = 2)) else emptyList()
            val fragment = entry(case.fragmentBounds, model = case.model, member = 1).copy(
                regionGranularity = if (case.free) TextRegionGranularity.FREE_TEXT else TextRegionGranularity.BUBBLE,
            )
            val result = MangaRecognizedTextConsolidator.consolidate(owners + fragment,
                owners.map { "prefix alpha beta suffix" } + case.text)
            assertEquals(case.name, owners.size + if (case.absorbed) 0 else 1, result.entries.size)
            assertEquals(case.name, (owners + fragment).flatMap { it.bubble.memberIndices }.sorted(),
                result.entries.flatMap { it.bubble.memberIndices }.sorted())
            if (!case.absorbed) assertEquals(case.name, case.text, result.texts.last())
        }
    }

    @Test
    fun emptyInputAndMismatchedTextCountsAreHandledExplicitly() {
        assertTrue(MangaRecognizedTextConsolidator.consolidate(emptyList(), emptyList()).entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            MangaRecognizedTextConsolidator.consolidate(listOf(entry(IntRect(0, 0, 10, 10), member = 0)), emptyList())
        }
    }

    private fun entry(bounds: IntRect, model: Int? = null, member: Int) = MangaOcrBubbleGroupingPolicy.Entry(
        bubble = Bubble(bounds, bounds, listOf(member)),
        guidedSource = if (model == null) BubbleModelRegrouper.Source.LEGACY_FALLBACK else BubbleModelRegrouper.Source.MODEL,
        modelBubbleIndex = model,
    )
}
