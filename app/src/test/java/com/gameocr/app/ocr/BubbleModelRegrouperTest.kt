package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleModelRegrouperTest {

    private data class RegroupCase(
        val name: String,
        val members: List<IntRect>,
        val modelBounds: List<IntRect>,
        val modelByMember: List<Int?>,
        val excludedMemberIndices: Set<Int> = emptySet(),
        val expected: List<ExpectedGroup>,
    )

    private data class ExpectedGroup(
        val source: BubbleModelRegrouper.Source,
        val modelIndex: Int?,
        val members: List<Int>,
    )

    @Test
    fun regroup_tableDriven_buildsModelGroupsAndPreservesFallbacks() {
        val cases = listOf(
            RegroupCase(
                name = "distant text columns in one model bubble regroup together",
                members = listOf(
                    IntRect(10, 10, 20, 40),
                    IntRect(45, 10, 55, 40),
                ),
                modelBounds = listOf(IntRect(5, 5, 60, 50)),
                modelByMember = listOf(0, 0),
                expected = listOf(
                    ExpectedGroup(BubbleModelRegrouper.Source.MODEL, 0, listOf(0, 1)),
                ),
            ),
            RegroupCase(
                name = "nearby sound effect remains a legacy fallback",
                members = listOf(
                    IntRect(10, 10, 25, 40),
                    IntRect(42, 12, 57, 42),
                ),
                modelBounds = listOf(IntRect(5, 5, 32, 48)),
                modelByMember = listOf(0, null),
                expected = listOf(
                    ExpectedGroup(BubbleModelRegrouper.Source.MODEL, 0, listOf(0)),
                    ExpectedGroup(BubbleModelRegrouper.Source.LEGACY_FALLBACK, null, listOf(1)),
                ),
            ),
            RegroupCase(
                name = "unassigned neighboring effects retain existing clustering",
                members = listOf(
                    IntRect(10, 70, 20, 80),
                    IntRect(22, 70, 32, 80),
                    IntRect(80, 70, 90, 80),
                ),
                modelBounds = emptyList(),
                modelByMember = listOf(null, null, null),
                expected = listOf(
                    ExpectedGroup(
                        BubbleModelRegrouper.Source.LEGACY_FALLBACK,
                        null,
                        listOf(0, 1),
                    ),
                    ExpectedGroup(BubbleModelRegrouper.Source.LEGACY_FALLBACK, null, listOf(2)),
                ),
            ),
            RegroupCase(
                name = "unused model and invalid model assignment do not lose members",
                members = listOf(
                    IntRect(10, 10, 20, 20),
                    IntRect(70, 70, 80, 80),
                ),
                modelBounds = listOf(
                    IntRect(0, 0, 30, 30),
                    IntRect(40, 40, 40, 60),
                    IntRect(60, 60, 90, 90),
                ),
                modelByMember = listOf(0, 1),
                expected = listOf(
                    ExpectedGroup(BubbleModelRegrouper.Source.MODEL, 0, listOf(0)),
                    ExpectedGroup(BubbleModelRegrouper.Source.LEGACY_FALLBACK, null, listOf(1)),
                ),
            ),
            RegroupCase(
                name = "text-refined bubble fragments are omitted instead of becoming fallbacks",
                members = listOf(
                    IntRect(10, 10, 25, 40),
                    IntRect(42, 12, 57, 42),
                    IntRect(70, 12, 85, 42),
                ),
                modelBounds = listOf(IntRect(5, 5, 32, 48)),
                modelByMember = listOf(0, null, null),
                excludedMemberIndices = setOf(1),
                expected = listOf(
                    ExpectedGroup(BubbleModelRegrouper.Source.MODEL, 0, listOf(0)),
                    ExpectedGroup(BubbleModelRegrouper.Source.LEGACY_FALLBACK, null, listOf(2)),
                ),
            ),
        )

        cases.forEach { case ->
            val result = BubbleModelRegrouper.regroupByModelAssignments(
                width = 100,
                height = 100,
                memberBounds = case.members,
                modelBounds = case.modelBounds,
                modelByMember = case.modelByMember,
                fallbackPadding = 0,
                fallbackGap = 3,
                excludedMemberIndices = case.excludedMemberIndices,
            )
            assertEquals(
                case.name,
                case.expected,
                result.map { group ->
                    ExpectedGroup(group.source, group.modelBubbleIndex, group.memberIndices)
                },
            )
            assertEquals(
                "${case.name}: every member appears exactly once",
                case.members.indices.filterNot(case.excludedMemberIndices::contains),
                result.flatMap { it.memberIndices }.sorted(),
            )
        }
    }

    @Test
    fun regroup_modelCropContainsModelAndAllMatchedMembers() {
        val result = BubbleModelRegrouper.regroup(
            width = 100,
            height = 100,
            memberBounds = listOf(
                IntRect(0, 20, 15, 40),
                IntRect(35, 20, 55, 40),
            ),
            modelBounds = listOf(IntRect(5, 10, 50, 50)),
            associations = listOf(association(0, 0), association(1, 0)),
            fallbackPadding = 0,
            fallbackGap = 0,
        ).single()

        assertEquals(IntRect(0, 10, 55, 50), result.cropBounds)
        assertEquals(IntRect(0, 20, 55, 40), result.contentBounds)
        assertTrue(result.memberIndices == listOf(0, 1))
    }

    private fun association(
        memberIndex: Int,
        modelIndex: Int?,
    ): BubbleMaskAssociator.Association = BubbleMaskAssociator.Association(
        ocrGroupIndex = memberIndex,
        modelBubbleIndex = modelIndex,
        reason = if (modelIndex == null) {
            BubbleMaskAssociator.Reason.UNMATCHED_NO_MODEL_BUBBLE
        } else {
            BubbleMaskAssociator.Reason.MATCHED_CENTER_AND_COVERAGE
        },
        score = if (modelIndex == null) 0f else 1f,
        memberMaskCoverage = if (modelIndex == null) 0f else 1f,
        contentBoxCoverage = if (modelIndex == null) 0f else 1f,
        centerInsideMask = modelIndex != null,
    )
}
