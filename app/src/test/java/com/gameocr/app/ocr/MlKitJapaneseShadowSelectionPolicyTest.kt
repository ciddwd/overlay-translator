package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MlKitJapaneseShadowSelectionPolicyTest {

    @Test
    fun select_tableDriven_usesCompletenessGeometryAndConfidenceOnCapturedRegions() {
        data class Case(
            val name: String,
            val glyphs: Int,
            val width: Int,
            val height: Int,
            val variants: List<MlKitJapaneseShadowVariant>,
            val expectedRotation: Int?,
            val expectedText: List<String>,
            val expectedAnnotations: List<String> = emptyList(),
            val expectedEdges: List<String> = emptyList(),
        )

        val cases = listOf(
            Case(
                name = "furigana page chooses materially better clockwise result and removes ruby",
                glyphs = 23,
                width = 142,
                height = 203,
                variants = listOf(
                    variant(0, 0.547f, line("むては、", 104, 5, 132, 97), line("え、もの", 98, 87, 120, 148), line("オラの獲物を", 76, 11, 102, 168), line("よこどりしょう", 49, 10, 70, 193), line("てんだなミ", 12, 11, 47, 142)),
                    variant(90, 0.604f, line("もては", 103, 6, 133, 100), line("えもの、", 101, 95, 117, 149), line("オラの獲物を", 73, 6, 102, 183), line("よこどりしょう", 46, 4, 71, 203), line("てんだなミ", 13, 2, 47, 159)),
                    variant(-90, 0.496f, line("むては、", 106, 5, 135, 97), line("え、もの", 102, 87, 122, 148), line("オラの獲物を見", 77, 11, 103, 170), line("よこどりしょう", 52, 10, 71, 194), line("てんだなが", 15, 12, 47, 143)),
                ),
                expectedRotation = 90,
                expectedText = listOf("もては", "オラの獲物を", "よこどりしょう", "てんだなミ"),
                expectedAnnotations = listOf("えもの、"),
            ),
            Case(
                name = "tiny confidence difference keeps upright and drops clipped neighbor",
                glyphs = 19,
                width = 129,
                height = 154,
                variants = listOf(
                    variant(0, 0.639f, line("ちょ", 97, 13, 116, 53), line("ちょっと", 69, 13, 92, 100), line("あぶないじゃ", 40, 13, 65, 143), line("ないっ", 17, 11, 40, 74)),
                    variant(90, 0.612f, line("ちょ", 93, 7, 117, 66), line("ちょっと", 67, 9, 91, 113), line("あぶないじゃ、", 43, 7, 68, 154), line("ない", 18, 21, 39, 56)),
                    variant(-90, 0.642f, line("ちょ", 99, 13, 119, 53), line("ちょっと", 73, 13, 94, 100), line("あぶないじゃ", 42, 13, 66, 145), line("ないつ", 21, 11, 45, 73)),
                ),
                expectedRotation = 0,
                expectedText = listOf("ちょっと", "あぶないじゃ", "ないっ"),
                expectedEdges = listOf("ちょ"),
            ),
            Case(
                name = "large completeness loss rejects clockwise and selects counterclockwise",
                glyphs = 9,
                width = 143,
                height = 121,
                variants = listOf(
                    variant(0, 0.486f, line("るう", 85, 10, 101, 59), line("ちょっと", 53, 6, 78, 121), line("西かな?", 10, 0, 39, 121)),
                    variant(90, 0.500f, line("と", 57, 92, 71, 116)),
                    variant(-90, 0.537f, line("るう", 87, 10, 102, 61), line("ちょっと", 56, 6, 81, 121), line("西かな?", 16, 10, 41, 114)),
                ),
                expectedRotation = -90,
                expectedText = listOf("るう", "ちょっと", "西かな?"),
            ),
            Case(
                name = "horizontal row recognition is rejected instead of replacing visible text",
                glyphs = 14,
                width = 175,
                height = 128,
                variants = listOf(
                    variant(0, 0.561f, line("やなあオ", 12, 9, 160, 50), line("るついラ", 15, 49, 159, 81), line("!!!ててが", 11, 86, 163, 117)),
                    variant(90, 0.581f, line("やなあオ", 12, 12, 159, 47), line("るついラ", 14, 50, 158, 84), line("!!!ててが", 10, 86, 161, 120)),
                    variant(-90, 0.605f, line("やなあオ", 2, 6, 174, 46), line("るつ いラ", 8, 45, 172, 80), line("!!ててが", 1, 79, 175, 119)),
                ),
                expectedRotation = null,
                expectedText = emptyList(),
            ),
            Case(
                name = "mixed region keeps complete upright columns and discards horizontal fragments",
                glyphs = 11,
                width = 113,
                height = 199,
                variants = listOf(
                    variant(0, 0.464f, line("|怪物め!", 6, 0, 50, 170), line("怪:お", 14, 13, 100, 45), line("おのれ", 66, 87, 101, 188), line("めお", 17, 87, 101, 115), line("の", 68, 122, 99, 154), line("!!!", 10, 122, 53, 154)),
                    variant(90, 0.476f, line("怪:お", 14, 14, 99, 47), line("物5", 14, 50, 62, 84), line("めお", 17, 88, 100, 117), line("!!! の", 10, 123, 98, 156), line("れ", 69, 159, 93, 190)),
                    variant(-90, 0.507f, line("怪:お", 6, 9, 112, 43), line("物5", 7, 49, 76, 82), line("めお", 10, 82, 113, 117), line("!!! の", 0, 116, 102, 157), line("れ", 70, 159, 103, 189)),
                ),
                expectedRotation = 0,
                expectedText = listOf("|怪物め!", "おのれ"),
            ),
        )

        cases.forEach { case ->
            val selection = MlKitJapaneseShadowSelectionPolicy.select(
                variants = case.variants,
                evidenceGlyphCount = case.glyphs,
                cropWidth = case.width,
                cropHeight = case.height,
            )
            assertEquals(case.name, case.expectedRotation, selection.selected?.rotationDegrees)
            assertEquals(case.name, case.expectedText, selection.selected?.retainedBlocks?.map(TextBlock::text).orEmpty())
            if (case.expectedRotation == null) {
                assertNull(case.name, selection.selected)
            } else {
                assertEquals(case.name, case.expectedAnnotations, selection.selected?.annotationBlocks?.map(TextBlock::text).orEmpty())
                assertEquals(case.name, case.expectedEdges, selection.selected?.edgeFragmentBlocks?.map(TextBlock::text).orEmpty())
            }
        }
    }

    private fun variant(
        rotation: Int,
        confidence: Float,
        vararg blocks: TextBlock,
    ) = MlKitJapaneseShadowVariant(
        rotationDegrees = rotation,
        blocks = blocks.map { block -> block.copy(confidence = confidence) },
    )

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) = TextBlock(
        text = text,
        boundingBox = Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        },
    )
}
