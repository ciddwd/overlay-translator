package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitJapaneseShadowRepairPolicyTest {

    @Test
    fun apply_tableDriven_replacesOnlyClaimedLinesAndResolvesOverlapByScore() {
        data class Case(
            val name: String,
            val repairs: List<MlKitJapaneseShadowRepair>,
            val expected: List<String>,
        )

        val firstPass = listOf("zero", "one", "two", "three", "four").mapIndexed { index, text ->
            block(text, index * 10)
        }
        val cases = listOf(
            Case("no repairs preserves object order", emptyList(), firstPass.map(TextBlock::text)),
            Case(
                "one region replaces its first source position",
                listOf(repair(listOf(1, 2), "replacement", 0.8f)),
                listOf("zero", "replacement", "three", "four"),
            ),
            Case(
                "disjoint regions replace independently",
                listOf(
                    repair(listOf(3, 4), "late", 0.7f),
                    repair(listOf(0, 1), "early", 0.6f),
                ),
                listOf("early", "two", "late"),
            ),
            Case(
                "higher score owns overlapping source lines",
                listOf(
                    repair(listOf(1, 2), "lower", 0.5f),
                    repair(listOf(2, 3), "higher", 0.9f),
                ),
                listOf("zero", "one", "higher", "four"),
            ),
            Case(
                "invalid indices cannot remove valid source text",
                listOf(repair(listOf(-1, 99), "invalid", 1f)),
                firstPass.map(TextBlock::text),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MlKitJapaneseShadowRepairPolicy.apply(firstPass, case.repairs).map(TextBlock::text),
            )
        }
    }

    @Test
    fun mapNormalizedRectToSource_tableDriven_scalesOffsetsAndClips() {
        data class Case(
            val name: String,
            val rect: MlKitGeometryRect,
            val source: MlKitGeometryRect,
            val normalizedWidth: Int,
            val normalizedHeight: Int,
            val expected: MlKitGeometryRect,
        )

        val cases = listOf(
            Case("identity-sized crop adds source offset", geometry(10, 20, 50, 80), geometry(100, 200, 200, 300), 100, 100, geometry(110, 220, 150, 280)),
            Case("resized crop maps both axes independently", geometry(20, 10, 80, 40), geometry(100, 200, 300, 500), 100, 50, geometry(140, 260, 260, 440)),
            Case("recognizer rounding outside crop is clipped", geometry(-5, -4, 110, 60), geometry(10, 20, 210, 120), 100, 50, geometry(10, 20, 210, 120)),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MlKitJapaneseShadowRepairPolicy.mapNormalizedRectToSource(
                    rect = case.rect,
                    sourceBounds = case.source,
                    normalizedWidth = case.normalizedWidth,
                    normalizedHeight = case.normalizedHeight,
                ),
            )
        }
    }

    private fun repair(indices: List<Int>, text: String, score: Float) = MlKitJapaneseShadowRepair(
        sourceLineIndices = indices,
        replacement = block(text, 0),
        score = score,
    )

    private fun block(text: String, offset: Int) = TextBlock(
        text = text,
        boundingBox = Rect().apply {
            left = offset
            top = offset
            right = offset + 5
            bottom = offset + 10
        },
    )

    private fun geometry(left: Int, top: Int, right: Int, bottom: Int) =
        MlKitGeometryRect(left, top, right, bottom)
}
