package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class MangaTextRegionRecoveryPolicyTest {
    @Test
    fun recovery_tableDriven_usesOnlyIndependentValidTextDetections() {
        data class Case(
            val name: String,
            val detections: List<MangaBubbleDetectionPostprocessor.Detection>,
            val existing: List<IntRect> = emptyList(),
            val expected: List<IntRect>,
        )
        val bounds = IntRect(82, 1996, 228, 2277)
        val valid = detection(bounds)
        listOf(
            Case("no DBNet regions", listOf(valid), expected = listOf(bounds)),
            Case("no detections", emptyList(), expected = emptyList()),
            Case("below existing detector threshold", listOf(valid.copy(confidence = 0.1f)), expected = emptyList()),
            Case("NaN confidence", listOf(valid.copy(confidence = Float.NaN)), expected = emptyList()),
            Case("NaN geometry", listOf(valid.copy(left = Float.NaN)), expected = emptyList()),
            Case("inverted bounds", listOf(valid.copy(left = 300f)), expected = emptyList()),
            Case("out of image", listOf(valid.copy(left = 1500f, right = 1700f)), expected = emptyList()),
            Case("bubble is not text", listOf(valid.copy(kind = MangaBubbleDetectionPostprocessor.Kind.BUBBLE)), expected = emptyList()),
            Case("free-text expansion unchanged", listOf(valid.copy(kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE)), expected = emptyList()),
            Case("exact duplicate", listOf(valid, valid), expected = listOf(bounds)),
            Case("already recognized fallback", listOf(valid), listOf(bounds), listOf(bounds)),
            Case("overlapping existing text", listOf(valid), listOf(IntRect(90, 2000, 200, 2250)), listOf(IntRect(90, 2000, 200, 2250))),
            Case("adjacent separate bubble", listOf(valid), listOf(IntRect(300, 1996, 420, 2277)), listOf(IntRect(300, 1996, 420, 2277), bounds)),
            Case("clamp at screen edge", listOf(detection(IntRect(-10, -20, 70, 100))), expected = listOf(IntRect(0, 0, 70, 100))),
        ).forEach { case ->
            val evidence = emptyEvidence(case.existing, case.detections.indices.toSet())
            val result = MangaTextRegionRecoveryPolicy.recover(evidence, case.detections, 1430, 2734)
            assertEquals(case.name, case.expected, result.evidence.entries.map { it.bubble.contentRect })
            result.recoveredDetectionIndices.forEach { recovered ->
                val assignment = result.evidence.assignments.single { it.detectionIndex == recovered }
                val entry = result.evidence.entries[assignment.entryIndex]
                assertEquals(case.name, entry.bubble.rect, entry.bubble.contentRect)
                assertTrue(case.name, entry.bubble.memberIndices.isEmpty())
                assertNull(case.name, entry.modelBubbleIndex)
                assertTrue(case.name, assignment.entryIndex in result.evidence.textSupportedEntryIndices)
                assertFalse(case.name, recovered in result.evidence.unassignedTextBubbleDetectionIndices)
            }
        }
    }

    @Test
    fun recovery_tableDriven_isIdempotentAndPreservesCoordinatesForCropPlanning() {
        for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
            val b = if (transpose) IntRect(150, 50, 450, 180) else IntRect(50, 150, 180, 450)
            val bounds = IntRect(b.left * scale, b.top * scale, b.right * scale, b.bottom * scale)
            val detections = listOf(detection(bounds))
            val result = MangaTextRegionRecoveryPolicy.recover(emptyEvidence(emptyList(), setOf(0)), detections, 1000, 1000)
            val twice = MangaTextRegionRecoveryPolicy.recover(result.evidence, detections, 1000, 1000)
            assertEquals(result.evidence.entries, twice.evidence.entries)
            val crop = MangaOcrCropPlanner.plan(result.evidence.entries.map { it.bubble }, emptyList(), 1000, 1000, 0).single()
            assertEquals(bounds, crop.bubble.rect)
            assertEquals(bounds, crop.bubble.contentRect)
        }
    }

    private fun detection(b: IntRect) = MangaBubbleDetectionPostprocessor.Detection(
        0.912f, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
        MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
    )

    private fun emptyEvidence(existing: List<IntRect>, unassigned: Set<Int>) = MangaOcrTextEvidencePolicy.Result(
        entries = existing.map { MangaOcrBubbleGroupingPolicy.Entry(Bubble(it, it, emptyList()), null, null) },
        droppedIndices = emptyList(), textSupportedEntryIndices = emptySet(), assignments = emptyList(),
        unassignedTextBubbleDetectionIndices = unassigned, duplicateCropEntryIndices = emptySet(),
    )
}
