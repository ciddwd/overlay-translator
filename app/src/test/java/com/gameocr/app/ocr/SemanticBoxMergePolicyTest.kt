package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticBoxMergePolicyTest {

    private data class Item(
        val text: String,
        val granularity: TextRegionGranularity,
        val parentRegionId: Int? = null,
    )

    @Test
    fun lineLevelEligibility_isTableDriven() {
        data class Case(
            val granularity: TextRegionGranularity,
            val expected: Boolean,
        )

        listOf(
            Case(TextRegionGranularity.UNKNOWN, true),
            Case(TextRegionGranularity.LINE, true),
            Case(TextRegionGranularity.PARAGRAPH, false),
            Case(TextRegionGranularity.BUBBLE, false),
            Case(TextRegionGranularity.FREE_TEXT, false),
        ).forEach { case ->
            assertEquals(
                case.granularity.name,
                case.expected,
                SemanticBoxMergePolicy.isLineLevel(case.granularity),
            )
        }
    }

    @Test
    fun mergeEligibility_isTableDriven() {
        data class Case(
            val name: String,
            val granularity: TextRegionGranularity,
            val parentRegionId: Int?,
            val expected: Boolean,
            val mergeStandaloneFreeText: Boolean = false,
        )

        listOf(
            Case("unknown OCR", TextRegionGranularity.UNKNOWN, null, true),
            Case("line OCR", TextRegionGranularity.LINE, null, true),
            Case("paragraph", TextRegionGranularity.PARAGRAPH, null, false),
            Case("legacy manga bubble", TextRegionGranularity.BUBBLE, null, true),
            Case("detector-confirmed manga bubble", TextRegionGranularity.BUBBLE, 7, false),
            Case("free text without parent", TextRegionGranularity.FREE_TEXT, null, false),
            Case("free text with parent", TextRegionGranularity.FREE_TEXT, 7, false),
            Case(
                "manga free text explicitly joins the user merge path",
                TextRegionGranularity.FREE_TEXT,
                null,
                mergeStandaloneFreeText = true,
                expected = true,
            ),
            Case(
                "parented free text remains protected even when opted in",
                TextRegionGranularity.FREE_TEXT,
                7,
                mergeStandaloneFreeText = true,
                expected = false,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                SemanticBoxMergePolicy.isMergeEligible(
                    granularity = case.granularity,
                    parentRegionId = case.parentRegionId,
                    mergeStandaloneFreeText = case.mergeStandaloneFreeText,
                ),
            )
        }
    }

    @Test
    fun mergeEligibleRuns_tableDriven_preservesConfirmedSemanticBoundariesAndOrder() {
        data class Case(
            val name: String,
            val input: List<Item>,
            val expectedTexts: List<String>,
            val expectedRunSizes: List<Int>,
        )

        val unknown = TextRegionGranularity.UNKNOWN
        val line = TextRegionGranularity.LINE
        val paragraph = TextRegionGranularity.PARAGRAPH
        val bubble = TextRegionGranularity.BUBBLE
        val freeText = TextRegionGranularity.FREE_TEXT
        listOf(
            Case("empty", emptyList(), emptyList(), emptyList()),
            Case(
                "ordinary OCR remains one eligible run",
                listOf(Item("a", unknown), Item("b", line), Item("c", unknown)),
                listOf("a+b+c"),
                listOf(3),
            ),
            Case(
                "detector-confirmed manga bubbles stay independent",
                listOf(Item("bubble-1", bubble, 1), Item("bubble-2", bubble, 2)),
                listOf("bubble-1", "bubble-2"),
                emptyList(),
            ),
            Case(
                "legacy manga columns remain eligible for geometric merging",
                listOf(Item("column-1", bubble), Item("column-2", bubble)),
                listOf("column-1+column-2"),
                listOf(2),
            ),
            Case(
                "free text stays independent",
                listOf(Item("sound-1", freeText), Item("sound-2", freeText)),
                listOf("sound-1", "sound-2"),
                emptyList(),
            ),
            Case(
                "paragraph is already semantic output",
                listOf(Item("paragraph", paragraph)),
                listOf("paragraph"),
                emptyList(),
            ),
            Case(
                "mixed output cannot merge through bubble",
                listOf(
                    Item("left-1", line),
                    Item("left-2", unknown),
                    Item("bubble", bubble, 9),
                    Item("right-1", unknown),
                    Item("right-2", line),
                ),
                listOf("left-1+left-2", "bubble", "right-1+right-2"),
                listOf(2, 2),
            ),
            Case(
                "every semantic kind is a hard boundary",
                listOf(
                    Item("a", line),
                    Item("paragraph", paragraph),
                    Item("b", line),
                    Item("free", freeText),
                    Item("c", unknown),
                ),
                listOf("a", "paragraph", "b", "free", "c"),
                listOf(1, 1, 1),
            ),
        ).forEach { case ->
            val runSizes = mutableListOf<Int>()
            val actual = SemanticBoxMergePolicy.mergeEligibleRuns(
                items = case.input,
                isEligible = { item ->
                    SemanticBoxMergePolicy.isMergeEligible(
                        granularity = item.granularity,
                        parentRegionId = item.parentRegionId,
                    )
                },
            ) { run ->
                runSizes += run.size
                listOf(Item(run.joinToString("+") { it.text }, TextRegionGranularity.PARAGRAPH))
            }

            assertEquals(case.name, case.expectedTexts, actual.map(Item::text))
            assertEquals(case.name, case.expectedRunSizes, runSizes)
        }
    }
}
