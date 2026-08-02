package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaTextEvidenceMatcherTest {
    private data class ClassificationCase(
        val name: String,
        val member: IntRect,
        val bubbles: List<MangaBubbleDetectionPostprocessor.Detection> = emptyList(),
        val text: List<MangaBubbleDetectionPostprocessor.Detection> = emptyList(),
        val modelIndex: Int? = null,
        val expected: MangaTextEvidenceMatcher.MemberAction,
    )

    @Test
    fun classifyMembers_tableDriven_keepsUnambiguousFreeTextStandalone() {
        val cases = listOf(
            ClassificationCase(
                name = "free-only member outside bubbles becomes standalone text",
                member = IntRect(120, 20, 160, 80),
                text = listOf(detection(TEXT_FREE, 115f, 10f, 165f, 90f)),
                expected = MangaTextEvidenceMatcher.MemberAction.KEEP_FREE_TEXT,
            ),
            ClassificationCase(
                name = "same member supported by both text kinds is kept",
                member = IntRect(120, 20, 160, 80),
                text = listOf(
                    detection(TEXT_FREE, 115f, 10f, 165f, 90f),
                    detection(TEXT_BUBBLE, 118f, 15f, 162f, 85f),
                ),
                expected = MangaTextEvidenceMatcher.MemberAction.KEEP_KIND_CONFLICT,
            ),
            ClassificationCase(
                name = "free-only member inside bubble bounds is kept as ambiguous",
                member = IntRect(20, 20, 60, 80),
                bubbles = listOf(detection(BUBBLE, 0f, 0f, 100f, 100f)),
                text = listOf(detection(TEXT_FREE, 15f, 10f, 65f, 90f)),
                expected =
                    MangaTextEvidenceMatcher.MemberAction.KEEP_AMBIGUOUS_INSIDE_BUBBLE,
            ),
            ClassificationCase(
                name = "valid model assignment protects free-only member",
                member = IntRect(120, 20, 160, 80),
                bubbles = listOf(detection(BUBBLE, 0f, 0f, 100f, 100f)),
                text = listOf(detection(TEXT_FREE, 115f, 10f, 165f, 90f)),
                modelIndex = 0,
                expected =
                    MangaTextEvidenceMatcher.MemberAction.KEEP_AMBIGUOUS_INSIDE_BUBBLE,
            ),
            ClassificationCase(
                name = "adjacent free text below coverage threshold does not exclude",
                member = IntRect(0, 0, 100, 100),
                text = listOf(detection(TEXT_FREE, 90f, 0f, 120f, 100f)),
                expected = MangaTextEvidenceMatcher.MemberAction.KEEP,
            ),
            ClassificationCase(
                name = "bubble text without free evidence is kept",
                member = IntRect(20, 20, 60, 80),
                text = listOf(detection(TEXT_BUBBLE, 15f, 10f, 65f, 90f)),
                expected = MangaTextEvidenceMatcher.MemberAction.KEEP,
            ),
        )

        cases.forEach { case ->
            val result = MangaTextEvidenceMatcher.classifyMembers(
                memberBounds = listOf(case.member),
                bubbleDetections = case.bubbles,
                textDetections = case.text,
                modelByMember = listOf(case.modelIndex),
            )

            assertEquals(case.name, case.expected, result.single().action)
        }
    }

    @Test
    fun assignTextBubbleDetections_tableDriven_assignsEachDetectionToOneModelGroup() {
        data class Case(
            val name: String,
            val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
            val detections: List<MangaBubbleDetectionPostprocessor.Detection>,
            val expected: List<Pair<Int, Int>>,
        )

        val left = entry(IntRect(0, 0, 100, 200), source = MODEL)
        val right = entry(IntRect(100, 0, 200, 200), source = MODEL)
        val cases = listOf(
            Case(
                name = "slightly overlapping neighboring evidence stays with nearest group",
                entries = listOf(left, right),
                detections = listOf(
                    detection(TEXT_BUBBLE, 10f, 10f, 105f, 190f),
                    detection(TEXT_BUBBLE, 95f, 10f, 190f, 190f),
                ),
                expected = listOf(0 to 0, 1 to 1),
            ),
            Case(
                name = "multiple text regions may belong to one multiline group",
                entries = listOf(left),
                detections = listOf(
                    detection(TEXT_BUBBLE, 10f, 10f, 90f, 80f),
                    detection(TEXT_BUBBLE, 10f, 110f, 90f, 190f),
                ),
                expected = listOf(0 to 0, 1 to 0),
            ),
            Case(
                name = "fallback group cannot consume bubble-text evidence",
                entries = listOf(entry(IntRect(0, 0, 100, 200), source = LEGACY_FALLBACK)),
                detections = listOf(detection(TEXT_BUBBLE, 10f, 10f, 90f, 190f)),
                expected = emptyList(),
            ),
            Case(
                name = "free-text detections are never assigned as crop evidence",
                entries = listOf(left),
                detections = listOf(detection(TEXT_FREE, 10f, 10f, 90f, 190f)),
                expected = emptyList(),
            ),
        )

        cases.forEach { case ->
            val result = MangaTextEvidenceMatcher.assignTextBubbleDetections(
                entries = case.entries,
                textDetections = case.detections,
            )

            assertEquals(
                case.name,
                case.expected,
                result.map { assignment ->
                    assignment.detectionIndex to assignment.entryIndex
                },
            )
        }
    }

    private fun entry(
        bounds: IntRect,
        source: BubbleModelRegrouper.Source,
    ) = MangaOcrBubbleGroupingPolicy.Entry(
        bubble = Bubble(
            rect = bounds,
            contentRect = bounds,
            memberIndices = listOf(0),
        ),
        guidedSource = source,
        modelBubbleIndex = if (source == MODEL) 0 else null,
    )

    private fun detection(
        kind: MangaBubbleDetectionPostprocessor.Kind,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = MangaBubbleDetectionPostprocessor.Detection(
        confidence = 0.9f,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        kind = kind,
    )

    private companion object {
        val BUBBLE = MangaBubbleDetectionPostprocessor.Kind.BUBBLE
        val TEXT_BUBBLE = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE
        val TEXT_FREE = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE
        val MODEL = BubbleModelRegrouper.Source.MODEL
        val LEGACY_FALLBACK = BubbleModelRegrouper.Source.LEGACY_FALLBACK
    }
}
