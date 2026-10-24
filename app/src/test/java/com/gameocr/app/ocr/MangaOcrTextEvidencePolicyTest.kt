package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrTextEvidencePolicyTest {
    @Test
    fun filter_absorbsOverlappingColumnsAndPreservesErasureOwnership() {
        // Geometry from the color-page log: complete sentence plus two fallback columns.
        for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
            fun rect(l: Int, t: Int, r: Int, b: Int): IntRect = if (transpose) {
                IntRect(t * scale, l * scale, b * scale, r * scale)
            } else IntRect(l * scale, t * scale, r * scale, b * scale)
            val owner = entry(BubbleModelRegrouper.Source.MODEL,
                rect(467, 1287, 631, 1483), members = listOf(0))
            val first = entry(BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                rect(570, 1288, 639, 1392), members = listOf(1))
            val second = entry(BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                rect(458, 1289, 528, 1485), members = listOf(2))
            val evidence = rect(467, 1290, 631, 1478)
            val result = MangaOcrTextEvidencePolicy.filter(
                entries = listOf(first, owner, second),
                textDetections = listOf(textDetection(
                    MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                    evidence.left.toFloat(), evidence.top.toFloat(),
                    evidence.right.toFloat(), evidence.bottom.toFloat(),
                )), evidenceAvailable = true,
            )
            val label = "scale=$scale transpose=$transpose"
            assertEquals(label, listOf(0, 2), result.droppedIndices)
            assertEquals(label, listOf(0, 1, 2), result.entries.single().bubble.memberIndices)
            assertEquals(label, rect(458, 1287, 639, 1485), result.entries.single().bubble.contentRect)
            assertEquals(label, setOf(0), result.textSupportedEntryIndices)
            assertEquals(label, 0, result.assignments.single().entryIndex)
            assertEquals(label, listOf(0, 1, 2), result.toModelGroups().single().memberIndices)
        }
    }

    @Test
    fun filter_fragmentTablePreservesSeparateDialogueFreeTextAndAmbiguity() {
        data class FragmentCase(
            val name: String, val bounds: IntRect, val free: Boolean = false,
            val twoOwners: Boolean = false, val evidenceAvailable: Boolean = true,
            val absorbed: Boolean,
        )
        listOf(
            FragmentCase("contained", IntRect(20, 20, 40, 80), absorbed = true),
            FragmentCase("eighty percent overlap", IntRect(84, 20, 104, 80), absorbed = true),
            FragmentCase("less than eighty percent", IntRect(85, 20, 105, 80), absorbed = false),
            FragmentCase("neighbor", IntRect(95, 20, 115, 80), absorbed = false),
            FragmentCase("disjoint", IntRect(110, 20, 130, 80), absorbed = false),
            FragmentCase("free text", IntRect(20, 20, 40, 80), free = true, absorbed = false),
            FragmentCase("ambiguous owners", IntRect(20, 20, 40, 80), twoOwners = true, absorbed = false),
            FragmentCase("no detector", IntRect(20, 20, 40, 80), evidenceAvailable = false, absorbed = false),
        ).forEach { case ->
            val owner = entry(BubbleModelRegrouper.Source.MODEL, IntRect(0, 0, 100, 100), members = listOf(0))
            val fragment = entry(BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                case.bounds, members = listOf(1)).copy(
                regionGranularity = if (case.free) TextRegionGranularity.FREE_TEXT else TextRegionGranularity.BUBBLE,
            )
            val owners = if (case.twoOwners) listOf(owner,
                owner.copy(bubble = owner.bubble.copy(rect = IntRect(1, 0, 101, 100),
                    contentRect = IntRect(1, 0, 101, 100), memberIndices = listOf(2)), modelBubbleIndex = 1),
            ) else listOf(owner)
            val result = MangaOcrTextEvidencePolicy.filter(
                entries = owners + fragment,
                textDetections = listOf(
                    textDetection(MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE, 0f, 0f, 100f, 100f),
                ) + if (case.twoOwners) listOf(
                    textDetection(MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE, 1f, 0f, 101f, 100f),
                ) else emptyList(),
                evidenceAvailable = case.evidenceAvailable,
            )
            assertEquals(case.name, owners.size + if (case.absorbed) 0 else 1, result.entries.size)
            assertEquals(case.name, listOf(0, 1) + if (case.twoOwners) listOf(2) else emptyList(),
                result.entries.flatMap { it.bubble.memberIndices }.sorted())
        }
    }

    @Test
    fun filter_absorbedMemberIsStillErasableInTheAssignedBubble() {
        val width = 60
        val height = 60
        val first = IntRect(10, 10, 20, 40)
        val second = IntRect(30, 10, 40, 40)
        val result = MangaOcrTextEvidencePolicy.filter(
            entries = listOf(
                entry(BubbleModelRegrouper.Source.MODEL, first, members = listOf(0)),
                entry(BubbleModelRegrouper.Source.LEGACY_FALLBACK, second, members = listOf(1)),
            ), textDetections = listOf(
                textDetection(MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE, 10f, 10f, 40f, 40f),
            ), evidenceAvailable = true,
        )
        val erase = DelayedTextEraseMaskBuilder.build(
            width, height,
            candidateTextMask = BooleanArray(width * height) { index ->
                index / width in 15..20 && (index % width in 13..16 || index % width in 33..36)
            },
            memberBounds = listOf(first, second),
            modelGroups = result.toModelGroups(),
            modelMasks = listOf(BubbleSegmentationPostprocessor.InstanceMask(0, 0, width, height,
                BooleanArray(width * height) { true })),
            confirmedBlocks = listOf(DelayedTextEraseMaskBuilder.ConfirmedBlock(0, listOf(first, second))),
        )
        assertEquals(1, erase.acceptedBlockCount)
        assertEquals(listOf(0, 1), erase.decisions.single().memberIndices)
        org.junit.Assert.assertTrue(erase.mask[16 * width + 14])
        org.junit.Assert.assertTrue(erase.mask[16 * width + 34])
    }

    @Test
    fun filter_tableDriven_recognizedTextExtentSurvivesScaleAndAxisChanges() {
        // A text detector can see columns that DBNet missed. The recognition crop and the
        // geometry used by erasure/rendering must describe that same text, not just the members.
        for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
            fun rect(l: Int, t: Int, r: Int, b: Int): IntRect = if (transpose) {
                IntRect(t * scale, l * scale, b * scale, r * scale)
            } else IntRect(l * scale, t * scale, r * scale, b * scale)
            val member = rect(75, 1844, 141, 2028)
            val evidence = rect(82, 1850, 229, 2111)
            val expected = rect(75, 1844, 229, 2111)
            val result = MangaOcrTextEvidencePolicy.filter(
                entries = listOf(entry(BubbleModelRegrouper.Source.MODEL, member, members = listOf(0))),
                textDetections = listOf(textDetection(
                    MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
                    evidence.left.toFloat(), evidence.top.toFloat(),
                    evidence.right.toFloat(), evidence.bottom.toFloat(),
                )),
                evidenceAvailable = true,
            )
            assertEquals("scale=$scale transpose=$transpose crop", expected, result.entries.single().bubble.rect)
            assertEquals("scale=$scale transpose=$transpose erasure", expected, result.entries.single().bubble.contentRect)
            assertEquals(listOf(0), result.entries.single().bubble.memberIndices)
        }
    }

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
    fun filter_modelBubbleEvidence_updatesTextExtentButNotToTheWholeBubble() {
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
        assertEquals(IntRect(40, 40, 110, 160), result.entries.single().bubble.contentRect)
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
