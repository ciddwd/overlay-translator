package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrTextEvidencePolicyTest {
    private data class Case(
        val name: String,
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val textDetections: List<MangaBubbleDetectionPostprocessor.Detection> = emptyList(),
        val evidenceAvailable: Boolean = true,
        val expectedKeptMembers: List<List<Int>>,
        val expectedDroppedIndices: List<Int>,
        val expectedTextSupportedIndices: Set<Int> = emptySet(),
    )

    @Test
    fun filter_tableDriven_dropsOnlyUnsupportedUltraWideFallbacks() {
        val wideFallback = entry(
            source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
            bounds = IntRect(10, 100, 1030, 170),
            members = listOf(4),
        )
        val cases = listOf(
            Case(
                name = "unsupported ultra-wide fallback is dropped",
                entries = listOf(wideFallback),
                expectedKeptMembers = emptyList(),
                expectedDroppedIndices = listOf(0),
            ),
            Case(
                name = "overlapping free-text evidence no longer rescues wide fallback",
                entries = listOf(wideFallback),
                textDetections = listOf(
                    textDetection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                        left = 20f,
                        top = 110f,
                        right = 1020f,
                        bottom = 160f,
                    ),
                ),
                expectedKeptMembers = emptyList(),
                expectedDroppedIndices = listOf(0),
            ),
            Case(
                name = "explicit standalone free-text region preserves wide text",
                entries = listOf(
                    wideFallback.copy(regionGranularity = TextRegionGranularity.FREE_TEXT),
                ),
                textDetections = listOf(
                    textDetection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                        left = 20f,
                        top = 110f,
                        right = 1020f,
                        bottom = 160f,
                    ),
                ),
                expectedKeptMembers = listOf(listOf(4)),
                expectedDroppedIndices = emptyList(),
            ),
            Case(
                name = "unavailable model evidence preserves legacy behavior",
                entries = listOf(wideFallback),
                evidenceAvailable = false,
                expectedKeptMembers = listOf(listOf(4)),
                expectedDroppedIndices = emptyList(),
            ),
            Case(
                name = "multi-member title is preserved without evidence",
                entries = listOf(
                    entry(
                        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        bounds = IntRect(10, 100, 1030, 170),
                        members = listOf(0, 1),
                    ),
                ),
                expectedKeptMembers = listOf(listOf(0, 1)),
                expectedDroppedIndices = emptyList(),
            ),
            Case(
                name = "ordinary free text is preserved without evidence",
                entries = listOf(
                    entry(
                        source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        bounds = IntRect(10, 100, 260, 170),
                        members = listOf(2),
                    ),
                ),
                expectedKeptMembers = listOf(listOf(2)),
                expectedDroppedIndices = emptyList(),
            ),
            Case(
                name = "model bubble is never filtered by line-artifact rule",
                entries = listOf(
                    entry(
                        source = BubbleModelRegrouper.Source.MODEL,
                        bounds = IntRect(10, 100, 1030, 170),
                        members = listOf(3),
                    ),
                ),
                expectedKeptMembers = listOf(listOf(3)),
                expectedDroppedIndices = emptyList(),
            ),
            Case(
                name = "adjacent non-overlapping text evidence does not rescue artifact",
                entries = listOf(wideFallback),
                textDetections = listOf(
                    textDetection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                        left = 20f,
                        top = 180f,
                        right = 300f,
                        bottom = 240f,
                    ),
                ),
                expectedKeptMembers = emptyList(),
                expectedDroppedIndices = listOf(0),
            ),
            Case(
                name = "overlapping bubble-text evidence does not rescue free-text artifact",
                entries = listOf(wideFallback),
                textDetections = listOf(
                    textDetection(
                        kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                        left = 20f,
                        top = 110f,
                        right = 1020f,
                        bottom = 160f,
                    ),
                ),
                expectedKeptMembers = emptyList(),
                expectedDroppedIndices = listOf(0),
            ),
        )

        cases.forEach { case ->
            val result = MangaOcrTextEvidencePolicy.filter(
                entries = case.entries,
                textDetections = case.textDetections,
                evidenceAvailable = case.evidenceAvailable,
            )

            assertEquals(
                case.name,
                case.expectedKeptMembers,
                result.entries.map { it.bubble.memberIndices },
            )
            assertEquals(case.name, case.expectedDroppedIndices, result.droppedIndices)
            assertEquals(
                case.name,
                case.expectedTextSupportedIndices,
                result.textSupportedEntryIndices,
            )
        }
    }

    @Test
    fun filter_freeTextEvidence_doesNotExpandRecognitionCrop() {
        val originalBounds = IntRect(196, 817, 784, 1095)
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(
                    source = BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                    bounds = originalBounds,
                    members = listOf(0, 1),
                ),
            ),
            textDetections = listOf(
                textDetection(
                    kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                    left = 211.5f,
                    top = 747.3f,
                    right = 791.4f,
                    bottom = 1098.7f,
                ),
            ),
            evidenceAvailable = true,
        )

        assertEquals(originalBounds, result.entries.single().bubble.rect)
        assertEquals(originalBounds, result.entries.single().bubble.contentRect)
        assertEquals(emptySet<Int>(), result.textSupportedEntryIndices)
    }

    @Test
    fun filter_modelBubbleEvidence_tightensRecognitionCropWithoutChangingContentBounds() {
        val contentBounds = IntRect(40, 50, 100, 150)
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = IntRect(0, 0, 200, 200),
                    contentBounds = contentBounds,
                    members = listOf(0),
                ),
            ),
            textDetections = listOf(
                textDetection(
                    kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                    left = 45f,
                    top = 40f,
                    right = 110f,
                    bottom = 160f,
                ),
                textDetection(
                    kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
                    left = 0f,
                    top = 0f,
                    right = 200f,
                    bottom = 200f,
                ),
            ),
            evidenceAvailable = true,
        )

        assertEquals(IntRect(40, 40, 110, 160), result.entries.single().bubble.rect)
        assertEquals(contentBounds, result.entries.single().bubble.contentRect)
        assertEquals(setOf(0), result.textSupportedEntryIndices)
    }

    @Test
    fun filter_modelBubbleWithoutAssignedEvidenceUsesOwnContentBounds() {
        val contentBounds = IntRect(40, 50, 100, 150)
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = IntRect(0, 0, 200, 200),
                    contentBounds = contentBounds,
                    members = listOf(0),
                ),
            ),
            textDetections = emptyList(),
            evidenceAvailable = true,
        )

        assertEquals(contentBounds, result.entries.single().bubble.rect)
        assertEquals(contentBounds, result.entries.single().bubble.contentRect)
        assertEquals(emptySet<Int>(), result.textSupportedEntryIndices)
    }

    @Test
    fun filter_assignsAdjacentBubbleTextToOnlyOneModelEntry() {
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = IntRect(0, 0, 100, 200),
                    members = listOf(0),
                ),
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = IntRect(100, 0, 200, 200),
                    members = listOf(1),
                ),
            ),
            textDetections = listOf(
                textDetection(
                    kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                    left = 10f,
                    top = 10f,
                    right = 105f,
                    bottom = 190f,
                ),
                textDetection(
                    kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                    left = 95f,
                    top = 10f,
                    right = 190f,
                    bottom = 190f,
                ),
            ),
            evidenceAvailable = true,
        )

        assertEquals(
            listOf(0 to 0, 1 to 1),
            result.assignments.map { assignment ->
                assignment.detectionIndex to assignment.entryIndex
            },
        )
        assertEquals(IntRect(0, 0, 105, 200), result.entries[0].bubble.rect)
        assertEquals(IntRect(95, 0, 200, 200), result.entries[1].bubble.rect)
        assertEquals(emptySet<Int>(), result.unassignedTextBubbleDetectionIndices)
    }

    @Test
    fun filter_modelCropTighteningPreventsSharedRecognitionBounds() {
        val sharedCrop = IntRect(0, 0, 200, 200)
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = sharedCrop,
                    contentBounds = IntRect(10, 10, 80, 190),
                    members = listOf(0),
                ),
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = sharedCrop,
                    contentBounds = IntRect(120, 10, 190, 190),
                    members = listOf(1),
                ),
            ),
            textDetections = emptyList(),
            evidenceAvailable = true,
        )

        assertEquals(
            listOf(IntRect(10, 10, 80, 190), IntRect(120, 10, 190, 190)),
            result.entries.map { entry -> entry.bubble.rect },
        )
        assertEquals(emptySet<Int>(), result.duplicateCropEntryIndices)
    }

    @Test
    fun filter_reportsRemainingDuplicateContentGeometry() {
        val sharedBounds = IntRect(10, 10, 80, 190)
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = sharedBounds,
                    members = listOf(0),
                ),
                entry(
                    source = BubbleModelRegrouper.Source.MODEL,
                    bounds = sharedBounds,
                    members = listOf(1),
                ),
            ),
            textDetections = emptyList(),
            evidenceAvailable = true,
        )

        assertEquals(setOf(0, 1), result.duplicateCropEntryIndices)
    }

    private fun entry(
        source: BubbleModelRegrouper.Source,
        bounds: IntRect,
        contentBounds: IntRect = bounds,
        members: List<Int>,
    ) = MangaOcrBubbleGroupingPolicy.Entry(
        bubble = Bubble(
            rect = bounds,
            contentRect = contentBounds,
            memberIndices = members,
        ),
        guidedSource = source,
        modelBubbleIndex = if (source == BubbleModelRegrouper.Source.MODEL) 0 else null,
    )

    private fun textDetection(
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
