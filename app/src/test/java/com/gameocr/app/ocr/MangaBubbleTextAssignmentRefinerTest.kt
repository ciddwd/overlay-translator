package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaBubbleTextAssignmentRefinerTest {
    private data class Case(
        val name: String,
        val members: List<IntRect>,
        val bubbles: List<MangaBubbleDetectionPostprocessor.Detection>,
        val text: List<MangaBubbleDetectionPostprocessor.Detection>,
        val assignments: List<Int?>,
        val expected: List<Int?>,
        val expectedExcluded: Set<Int> = emptySet(),
        val expectedFreeExcluded: Set<Int> = emptySet(),
        val expectedAmbiguousFree: Set<Int> = emptySet(),
        val expectedKindConflicts: Set<Int> = emptySet(),
    )

    @Test
    fun refine_tableDriven_separatesSoundEffectsFromBubbleText() {
        val leftBubble = detection(
            kind = MangaBubbleDetectionPostprocessor.Kind.BUBBLE,
            left = 0f,
            top = 0f,
            right = 100f,
            bottom = 100f,
        )
        val rightBubble = detection(
            kind = MangaBubbleDetectionPostprocessor.Kind.BUBBLE,
            left = 100f,
            top = 0f,
            right = 200f,
            bottom = 100f,
        )
        val cases = listOf(
            Case(
                name = "member inside bubble text stays and adjacent effect falls back",
                members = listOf(
                    IntRect(12, 15, 38, 75),
                    IntRect(62, 15, 92, 75),
                ),
                bubbles = listOf(leftBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                        left = 10f,
                        top = 10f,
                        right = 42f,
                        bottom = 80f,
                    ),
                ),
                assignments = listOf(0, 0),
                expected = listOf(0, null),
                expectedExcluded = setOf(1),
            ),
            Case(
                name = "coverage keeps a member whose center is just outside text box",
                members = listOf(IntRect(20, 20, 60, 80)),
                bubbles = listOf(leftBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                        left = 20f,
                        top = 20f,
                        right = 38f,
                        bottom = 80f,
                    ),
                ),
                assignments = listOf(0),
                expected = listOf(0),
            ),
            Case(
                name = "bubble without text evidence preserves original assignment",
                members = listOf(IntRect(62, 15, 92, 75)),
                bubbles = listOf(leftBubble),
                text = emptyList(),
                assignments = listOf(0),
                expected = listOf(0),
            ),
            Case(
                name = "free text is not treated as bubble-member evidence",
                members = listOf(IntRect(62, 15, 92, 75)),
                bubbles = listOf(leftBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                        left = 10f,
                        top = 10f,
                        right = 42f,
                        bottom = 80f,
                    ),
                ),
                assignments = listOf(0),
                expected = listOf(0),
            ),
            Case(
                name = "text evidence is scoped to its overlapping bubble",
                members = listOf(
                    IntRect(62, 15, 92, 75),
                    IntRect(120, 15, 150, 75),
                ),
                bubbles = listOf(leftBubble, rightBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                        left = 115f,
                        top = 10f,
                        right = 155f,
                        bottom = 80f,
                    ),
                ),
                assignments = listOf(0, 1),
                expected = listOf(0, 1),
            ),
            Case(
                name = "null and invalid assignments remain fallback",
                members = listOf(
                    IntRect(10, 10, 30, 30),
                    IntRect(40, 10, 60, 30),
                ),
                bubbles = listOf(leftBubble),
                text = emptyList(),
                assignments = listOf(null, 4),
                expected = listOf(null, null),
            ),
            Case(
                name = "unassigned free-only member outside bubbles is excluded",
                members = listOf(IntRect(120, 15, 150, 75)),
                bubbles = listOf(leftBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                        left = 115f,
                        top = 10f,
                        right = 155f,
                        bottom = 80f,
                    ),
                ),
                assignments = listOf(null),
                expected = listOf(null),
                expectedExcluded = setOf(0),
                expectedFreeExcluded = setOf(0),
            ),
            Case(
                name = "free-only member inside bubble remains ambiguous fallback",
                members = listOf(IntRect(20, 15, 50, 75)),
                bubbles = listOf(leftBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                        left = 15f,
                        top = 10f,
                        right = 55f,
                        bottom = 80f,
                    ),
                ),
                assignments = listOf(null),
                expected = listOf(null),
                expectedAmbiguousFree = setOf(0),
            ),
            Case(
                name = "conflicting free and bubble text evidence keeps member",
                members = listOf(IntRect(120, 15, 150, 75)),
                bubbles = listOf(leftBubble),
                text = listOf(
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                        left = 115f,
                        top = 10f,
                        right = 155f,
                        bottom = 80f,
                    ),
                    detection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                        left = 118f,
                        top = 12f,
                        right = 152f,
                        bottom = 78f,
                    ),
                ),
                assignments = listOf(null),
                expected = listOf(null),
                expectedKindConflicts = setOf(0),
            ),
        )

        cases.forEach { case ->
            val result = MangaBubbleTextAssignmentRefiner.refine(
                memberBounds = case.members,
                bubbleDetections = case.bubbles,
                textDetections = case.text,
                modelByMember = case.assignments,
            )

            assertEquals(case.name, case.expected, result.assignments)
            assertEquals(case.name, case.expectedExcluded, result.excludedMemberIndices)
            assertEquals(
                case.name,
                case.expectedFreeExcluded,
                result.freeTextExcludedMemberIndices,
            )
            assertEquals(
                case.name,
                case.expectedAmbiguousFree,
                result.ambiguousFreeTextMemberIndices,
            )
            assertEquals(
                case.name,
                case.expectedKindConflicts,
                result.conflictingTextKindMemberIndices,
            )
        }
    }

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
}
