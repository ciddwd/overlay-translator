package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderOrientationResolverTest {

    @Test
    fun resolve_render_orientation_prefers_vertical_layout_from_merge() {
        data class Case(
            val name: String,
            val hint: TextOrientation?,
            val blockOrientations: List<TextOrientation?>,
            val expected: TextOrientation
        )

        val cases = listOf(
            Case(
                name = "unknown-post-merge-keeps-vertical",
                hint = TextOrientation.UNKNOWN,
                blockOrientations = listOf(TextOrientation.VERTICAL_RTL),
                expected = TextOrientation.VERTICAL_RTL
            ),
            Case(
                name = "horizontal-downgrade-cannot-override-vertical-merge-layout",
                hint = TextOrientation.HORIZONTAL_LTR,
                blockOrientations = listOf(TextOrientation.VERTICAL_RTL, TextOrientation.VERTICAL_RTL),
                expected = TextOrientation.VERTICAL_RTL
            ),
            Case(
                name = "manual-or-refined-vertical-still-works-without-block-layout",
                hint = TextOrientation.VERTICAL_RTL,
                blockOrientations = listOf(null, TextOrientation.UNKNOWN),
                expected = TextOrientation.VERTICAL_RTL
            ),
            Case(
                name = "no-signal-falls-back-horizontal",
                hint = null,
                blockOrientations = listOf(null, TextOrientation.UNKNOWN),
                expected = TextOrientation.HORIZONTAL_LTR
            )
        )

        cases.forEach { case ->
            val actual = resolveRenderOrientation(case.hint, case.blockOrientations)
            assertEquals(case.name, case.expected, actual)
        }
    }

    @Test
    fun orientation_hint_from_layout_uses_dominant_non_unknown_orientation() {
        val blocks = listOf(
            block(TextOrientation.VERTICAL_RTL),
            block(TextOrientation.UNKNOWN),
            block(TextOrientation.VERTICAL_RTL),
            block(TextOrientation.HORIZONTAL_LTR)
        )

        val hint = orientationHintFromLayout(blocks)

        assertEquals(TextOrientation.VERTICAL_RTL, hint?.orientation)
        assertEquals("ocr-merge-layout", hint?.source)
    }

    private fun block(orientation: TextOrientation?): TextBlock =
        TextBlock(
            text = "x",
            boundingBox = android.graphics.Rect(0, 0, 1, 1),
            layoutOrientation = orientation
        )
}
