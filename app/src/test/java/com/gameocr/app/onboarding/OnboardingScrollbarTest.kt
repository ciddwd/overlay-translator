package com.gameocr.app.onboarding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingScrollbarTest {
    @Test
    fun geometry_isTableDrivenAcrossOverflowAndScrollPositions() {
        data class Case(
            val name: String,
            val scrollValue: Int,
            val scrollMaxValue: Int,
            val viewportSize: Int,
            val trackHeight: Float,
            val minimumThumbHeight: Float,
            val expectedTop: Float?,
            val expectedHeight: Float?,
        )

        val cases = listOf(
            Case("content fits", 0, 0, 1_000, 100f, 20f, null, null),
            Case("invalid viewport", 0, 100, 0, 100f, 20f, null, null),
            Case("invalid track", 0, 100, 1_000, 0f, 20f, null, null),
            Case("top", 0, 1_000, 1_000, 100f, 20f, 0f, 50f),
            Case("middle", 500, 1_000, 1_000, 100f, 20f, 25f, 50f),
            Case("bottom", 1_000, 1_000, 1_000, 100f, 20f, 50f, 50f),
            Case("long content uses minimum thumb", 450, 900, 100, 100f, 20f, 40f, 20f),
            Case("negative position clamps to top", -100, 1_000, 1_000, 100f, 20f, 0f, 50f),
            Case("overscroll clamps to bottom", 1_100, 1_000, 1_000, 100f, 20f, 50f, 50f),
        )

        cases.forEach { case ->
            val actual = persistentScrollbarGeometry(
                scrollValue = case.scrollValue,
                scrollMaxValue = case.scrollMaxValue,
                viewportSize = case.viewportSize,
                trackHeight = case.trackHeight,
                minimumThumbHeight = case.minimumThumbHeight,
            )
            if (case.expectedTop == null || case.expectedHeight == null) {
                assertNull(case.name, actual)
            } else {
                requireNotNull(actual)
                assertEquals(case.name, case.expectedTop, actual.thumbTop, 0.001f)
                assertEquals(case.name, case.expectedHeight, actual.thumbHeight, 0.001f)
            }
        }
    }

    @Test
    fun persistentIndicator_wrapsTheScrollableContent() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        ).readText()
        val pageModifier = source.substringAfter(".heightIn(max = 760.dp)")
        val indicator = pageModifier.indexOf(".persistentVerticalScrollbar(")
        val scrolling = pageModifier.indexOf(
            ".verticalScroll(pageScrollState)",
            startIndex = indicator,
        )

        assertTrue("persistent indicator is missing", indicator >= 0)
        assertTrue(
            "indicator must wrap verticalScroll so it stays fixed while content moves",
            scrolling > indicator,
        )

        data class SpacingMarker(val name: String, val value: String)
        listOf(
            SpacingMarker("scrollbar right inset", "val rightInset = 2.dp.toPx()"),
            SpacingMarker("content end breathing space", "end = 16.dp"),
        ).forEach { marker ->
            assertTrue("${marker.name} is missing", source.contains(marker.value))
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
