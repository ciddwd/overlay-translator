package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextBlockRegrouperTest {

    private data class Case(
        val name: String,
        val blocks: List<TextBlock>,
        val bubbleByBlock: List<Int?>,
        val expectedTexts: List<String>,
        val expectedBounds: List<Rect>,
        val expectedSourceBoxCounts: List<Int>,
        val expectedBubbleIds: List<Int?>,
    )

    @Test
    fun regroup_tableDriven_buildsOneTranslationUnitPerDetectedBubble() {
        val cases = listOf(
            Case(
                name = "three lines in one bubble become one translation unit",
                blocks = listOf(
                    block("第一行", Rect(20, 20, 180, 60)),
                    block("第二行", Rect(18, 70, 190, 110)),
                    block("第三行", Rect(24, 120, 170, 160)),
                ),
                bubbleByBlock = listOf(3, 3, 3),
                expectedTexts = listOf("第一行\n第二行\n第三行"),
                expectedBounds = listOf(Rect(18, 20, 190, 160)),
                expectedSourceBoxCounts = listOf(3),
                expectedBubbleIds = listOf(3),
            ),
            Case(
                name = "nearby bubbles remain separate",
                blocks = listOf(
                    block("左一", Rect(10, 10, 90, 40)),
                    block("左二", Rect(10, 45, 100, 75)),
                    block("右一", Rect(110, 12, 190, 42)),
                    block("右二", Rect(110, 48, 200, 78)),
                ),
                bubbleByBlock = listOf(1, 1, 2, 2),
                expectedTexts = listOf("左一\n左二", "右一\n右二"),
                expectedBounds = listOf(
                    Rect(10, 10, 100, 75),
                    Rect(110, 12, 200, 78),
                ),
                expectedSourceBoxCounts = listOf(2, 2),
                expectedBubbleIds = listOf(1, 2),
            ),
            Case(
                name = "unmatched text remains an independent unit",
                blocks = listOf(
                    block("气泡内", Rect(10, 10, 90, 40)),
                    block("音效", Rect(95, 20, 140, 60)),
                ),
                bubbleByBlock = listOf(5, null),
                expectedTexts = listOf("气泡内", "音效"),
                expectedBounds = listOf(
                    Rect(10, 10, 90, 40),
                    Rect(95, 20, 140, 60),
                ),
                expectedSourceBoxCounts = listOf(1, 1),
                expectedBubbleIds = listOf(5, null),
            ),
            Case(
                name = "missing source boxes fall back to bounding boxes",
                blocks = listOf(
                    block("上", Rect(30, 20, 80, 60), sourceBoxes = emptyList()),
                    block("下", Rect(28, 70, 85, 110), sourceBoxes = emptyList()),
                ),
                bubbleByBlock = listOf(8, 8),
                expectedTexts = listOf("上\n下"),
                expectedBounds = listOf(Rect(28, 20, 85, 110)),
                expectedSourceBoxCounts = listOf(2),
                expectedBubbleIds = listOf(8),
            ),
        )

        cases.forEach { case ->
            val actual = BubbleTextBlockRegrouper.regroup(case.blocks, case.bubbleByBlock)
            assertEquals(case.name, case.expectedTexts, actual.map(TextBlock::text))
            assertEquals(
                case.name,
                case.expectedBounds.map { it.coordinates() },
                actual.map { it.boundingBox.coordinates() },
            )
            assertEquals(
                case.name,
                case.expectedSourceBoxCounts,
                actual.map { it.sourceBoxes.size },
            )
            assertEquals(case.name, case.expectedBubbleIds, actual.map(TextBlock::bubbleGroupId))
        }
    }

    private fun block(
        text: String,
        bounds: Rect,
        sourceBoxes: List<Rect> = listOf(Rect(bounds)),
    ): TextBlock = TextBlock(
        text = text,
        boundingBox = Rect(bounds),
        confidence = 0.9f,
        recognizedLanguage = "auto",
        sourceBoxes = sourceBoxes,
    )

    private fun Rect.coordinates(): List<Int> = listOf(left, top, right, bottom)
}
