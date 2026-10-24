package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class MangaOcrFragmentPolicyTest {
    @Test
    fun fragmentTable_keepsIndependentSingleCharactersFreeTextAndNormalRegions() {
        data class Case(val name: String, val bounds: IntRect, val model: Int? = null,
            val free: Boolean = false, val evidence: Boolean = false, val drop: Boolean)
        listOf(
            Case("saved line fragment", IntRect(868, 567, 894, 597), drop = true),
            Case("saved kana corner", IntRect(697, 769, 724, 807), drop = true),
            Case("real small character with evidence", IntRect(697, 769, 724, 807), evidence = true, drop = false),
            Case("model backed short dialogue", IntRect(697, 769, 724, 807), model = 0, drop = false),
            Case("free text", IntRect(697, 769, 724, 807), free = true, drop = false),
            Case("normal text region", IntRect(100, 100, 200, 300), drop = false),
        ).forEach { c ->
            val entry = entry(c.bounds, c.model, c.free)
            val detections = if (c.evidence) listOf(detection(c.bounds)) else emptyList()
            assertEquals(c.name, c.drop, MangaOcrFragmentPolicy.dropBeforeRecognition(entry, detections, 1440, 3200))
        }
    }

    @Test
    fun tinyCornerOfLargerDetection_isNotIndependentTextEvidence() {
        val bounds = IntRect(321, 1260, 363, 1283)
        val wideEvidence = MangaBubbleDetectionPostprocessor.Detection(.391f,
            210.7f, 1266.6f, 421.5f, 1374.6f, MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE)
        assertTrue(MangaOcrFragmentPolicy.dropBeforeRecognition(entry(bounds), listOf(wideEvidence), 1440, 3200))
        assertFalse(MangaOcrFragmentPolicy.dropBeforeRecognition(entry(bounds), listOf(detection(bounds)), 1440, 3200))
        assertFalse(MangaOcrFragmentPolicy.dropBeforeRecognition(entry(bounds, model = 0), listOf(wideEvidence), 1440, 3200))
    }

    @Test
    fun secondaryCheckTable_preservesConfirmedWordsAndLeavesUnconfirmedSourceUntouched() {
        val bounds = IntRect(688, 2016, 874, 2162)
        val weakFree = MangaBubbleDetectionPostprocessor.Detection(.489f, 556.6f, 2025.9f,
            880.6f, 2165.4f, MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE)
        assertTrue(MangaOcrFragmentPolicy.needsSecondaryCheck("格", entry(bounds), listOf(weakFree), 1440, 3200))
        assertFalse(MangaOcrFragmentPolicy.needsSecondaryCheck("格", entry(bounds), listOf(weakFree.copy(confidence = .9f)), 1440, 3200))
        assertFalse(MangaOcrFragmentPolicy.needsSecondaryCheck("はい", entry(bounds), emptyList(), 1440, 3200))
        assertFalse(MangaOcrFragmentPolicy.needsSecondaryCheck("え", entry(bounds, model = 0), emptyList(), 1440, 3200))
        data class Case(val name: String, val lines: List<MangaMixedScriptPaddleLine>, val expected: String?)
        listOf(Case("agreement", listOf(MangaMixedScriptPaddleLine("格", .92f)), "格"),
            Case("clear short word", listOf(MangaMixedScriptPaddleLine("よし", .98f)), "よし"),
            Case("empty", emptyList(), null),
            Case("weak", listOf(MangaMixedScriptPaddleLine("格", .4f)), null),
            Case("conflicting glyph", listOf(MangaMixedScriptPaddleLine("好", .98f)), null),
            Case("unreliable word", listOf(MangaMixedScriptPaddleLine("よし", .93f)), null),
            Case("competing lines", listOf(MangaMixedScriptPaddleLine("はい", .99f), MangaMixedScriptPaddleLine("いい", .99f)), null)
        ).forEach { c -> assertEquals(c.name, c.expected, MangaOcrFragmentPolicy.resolveSecondary("格", c.lines).outputText) }
    }

    @Test
    fun postRecognitionTable_usesActualConfidenceAndKeepsSupportedShortText() {
        val r = IntRect(100, 100, 160, 160)
        data class Case(val text: String, val confidence: Float, val evidence: Boolean, val drop: Boolean)
        listOf(Case("あ", .79f, false, true), Case("あ", .8f, false, false),
            Case("あ", .2f, true, false), Case("!!", .99f, false, true),
            Case("ABC", .3f, false, false), Case("あ", Float.NaN, false, false)).forEach { c ->
            assertEquals(c.toString(), c.drop, MangaOcrFragmentPolicy.dropAfterRecognition(c.text, entry(r),
                if (c.evidence) listOf(detection(r)) else emptyList(), 1440, 3200, c.confidence))
        }
    }

    private fun entry(r: IntRect, model: Int? = null, free: Boolean = false) = MangaOcrBubbleGroupingPolicy.Entry(
        Bubble(r, r, listOf(0)), if (model == null) BubbleModelRegrouper.Source.LEGACY_FALLBACK else BubbleModelRegrouper.Source.MODEL,
        model, if (free) TextRegionGranularity.FREE_TEXT else TextRegionGranularity.BUBBLE)
    private fun detection(r: IntRect) = MangaBubbleDetectionPostprocessor.Detection(.8f,
        r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE)
}
