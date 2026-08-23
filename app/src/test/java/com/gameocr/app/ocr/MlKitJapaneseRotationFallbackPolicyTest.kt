package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitJapaneseRotationFallbackPolicyTest {

    @Test
    fun next_tableDriven_stopsAcceptedResultsAndBoundsUnproductiveRotationAttempts() {
        data class Case(
            val name: String,
            val variants: List<MlKitJapaneseShadowVariant>,
            val expected: MlKitJapaneseRotationFallback,
        )

        val cases = listOf(
            Case(
                name = "accepted upright result needs no rotation",
                variants = listOf(variant(0, vertical("本文です", 10, 10, 30, 100))),
                expected = MlKitJapaneseRotationFallback.STOP,
            ),
            Case(
                name = "rejected upright result tries clockwise once",
                variants = listOf(variant(0, horizontal("横組み", 10, 10, 100, 30))),
                expected = MlKitJapaneseRotationFallback.CLOCKWISE,
            ),
            Case(
                name = "two horizontal-only attempts stop instead of trying forever",
                variants = listOf(
                    variant(0, horizontal("横組み", 10, 10, 100, 30)),
                    variant(90, horizontal("横組み", 10, 40, 100, 60)),
                ),
                expected = MlKitJapaneseRotationFallback.STOP,
            ),
            Case(
                name = "partial vertical evidence permits final counterclockwise attempt",
                variants = listOf(
                    variant(0, vertical("字", 10, 10, 30, 40)),
                    variant(90, horizontal("横組み", 10, 40, 100, 60)),
                ),
                expected = MlKitJapaneseRotationFallback.COUNTERCLOCKWISE,
            ),
            Case(
                name = "empty recognizer output permits opposite orientation",
                variants = listOf(variant(0), variant(90)),
                expected = MlKitJapaneseRotationFallback.COUNTERCLOCKWISE,
            ),
            Case(
                name = "all three orientations stop after final attempt",
                variants = listOf(variant(0), variant(90), variant(-90)),
                expected = MlKitJapaneseRotationFallback.STOP,
            ),
        )

        cases.forEach { case ->
            val selection = MlKitJapaneseShadowSelectionPolicy.select(
                variants = case.variants,
                evidenceGlyphCount = 6,
                cropWidth = 120,
                cropHeight = 160,
            )
            assertEquals(case.name, case.expected, MlKitJapaneseRotationFallbackPolicy.next(selection))
        }
    }

    private fun variant(rotation: Int, vararg blocks: TextBlock) = MlKitJapaneseShadowVariant(
        rotationDegrees = rotation,
        blocks = blocks.toList(),
    )

    private fun vertical(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        block(text, left, top, right, bottom)

    private fun horizontal(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        block(text, left, top, right, bottom)

    private fun block(text: String, left: Int, top: Int, right: Int, bottom: Int) = TextBlock(
        text = text,
        boundingBox = Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        },
        confidence = 0.7f,
    )
}
