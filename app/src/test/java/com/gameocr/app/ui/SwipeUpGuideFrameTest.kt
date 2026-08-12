package com.gameocr.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeUpGuideFrameTest {
    @Test
    fun accent_staysBlueAcrossAllThreeGuideContextsAndThemes() {
        data class Case(
            val name: String,
            val themePrimary: Color,
            val expectedAccent: Color,
        )

        listOf(
            Case("status vertical guide on light Slate", Color(0xFF0F172A), Color(0xFF1976D2)),
            Case("preset horizontal guide on dark Slate", Color(0xFFF8FAFC), Color(0xFF1976D2)),
            Case("capture horizontal guide on custom theme", Color(0xFF6750A4), Color(0xFF1976D2)),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedAccent,
                swipeUpGuideAccent(case.themePrimary),
            )
        }
    }

    @Test
    fun geometry_extendsTheSwipeTravelAndArrowTrail() {
        data class Case(
            val name: String,
            val scale: Float,
            val restingTop: Float,
            val travel: Float,
            val arrowBottom: Float,
        )

        listOf(
            Case("half scale", 0.5f, 30f, 30f, 46f),
            Case("base scale", 1f, 60f, 60f, 92f),
            Case("double scale", 2f, 120f, 120f, 184f),
        ).forEach { case ->
            val geometry = swipeUpGuideGeometry(case.scale)
            assertEquals(case.name, case.restingTop, geometry.restingTop, 0.0001f)
            assertEquals(case.name, case.travel, geometry.travel, 0.0001f)
            assertEquals(case.name, 0f, geometry.arrowTop, 0.0001f)
            assertEquals(case.name, case.arrowBottom, geometry.arrowBottom, 0.0001f)
        }
    }

    @Test
    fun horizontalGeometry_extendsTravelAndArrowAcrossScales() {
        data class Case(
            val name: String,
            val canvasWidth: Float,
            val scale: Float,
            val expectedCenterLeft: Float,
            val expectedTravel: Float,
            val expectedArrowLeft: Float,
            val expectedArrowRight: Float,
        )

        listOf(
            Case("half scale", 100f, 0.5f, 34.5f, 33f, 3.5f, 96.5f),
            Case("base scale", 200f, 1f, 69f, 66f, 7f, 193f),
            Case("double scale", 400f, 2f, 138f, 132f, 14f, 386f),
        ).forEach { case ->
            val geometry = swipeHorizontalGuideGeometry(case.canvasWidth, case.scale)
            assertEquals(case.name, case.expectedCenterLeft, geometry.centerHandLeft, 0.0001f)
            assertEquals(case.name, case.expectedTravel, geometry.handTravel, 0.0001f)
            assertEquals(case.name, case.expectedArrowLeft, geometry.arrowLeft, 0.0001f)
            assertEquals(case.name, case.expectedArrowRight, geometry.arrowRight, 0.0001f)
        }
    }

    @Test
    fun animationFrame_isTableDrivenAcrossAllPhases() {
        data class Case(
            val name: String,
            val progress: Float,
            val expectedOffset: Float,
            val expectedAlpha: Float,
            val expectedRingAlpha: Float,
            val expectedTrailAlpha: Float,
        )

        listOf(
            Case("negative progress clamps to start", -0.5f, 0f, 0f, 0f, 0f),
            Case("not-a-number progress safely resets", Float.NaN, 0f, 0f, 0f, 0f),
            Case("hand fades in before pressing", 0.08f, 0f, 0.5f, 0f, 0f),
            Case("touch ring grows at contact", 0.14f, 0f, 0.875f, 0.5f, 0f),
            Case("swipe begins after contact", 0.24f, 0f, 1f, 0.625f, 0.5f),
            Case("hand reaches the middle of the swipe", 0.48f, 0.5f, 1f, 0f, 1f),
            Case("hand reaches the top before fading", 0.72f, 1f, 1f, 0f, 0.777778f),
            Case("hand fades while staying at the top", 0.79f, 1f, 0.5f, 0f, 0.388889f),
            Case("pause frame is fully hidden", 0.90f, 1f, 0f, 0f, 0f),
            Case("overflow progress clamps to the hidden end", 1.5f, 1f, 0f, 0f, 0f),
        ).forEach { case ->
            val frame = swipeUpGuideFrame(case.progress)
            assertEquals(case.name, case.expectedOffset, frame.handOffsetFraction, 0.0001f)
            assertEquals(case.name, case.expectedAlpha, frame.handAlpha, 0.0001f)
            assertEquals(case.name, case.expectedRingAlpha, frame.touchRingAlpha, 0.0001f)
            assertEquals(case.name, case.expectedTrailAlpha, frame.trailAlpha, 0.0001f)
        }
    }

    @Test
    fun animationFrame_outputsOnlySafeNormalizedValues() {
        listOf(
            Float.NEGATIVE_INFINITY,
            -1f,
            Float.NaN,
            0f,
            0.5f,
            1f,
            2f,
            Float.POSITIVE_INFINITY,
        )
            .forEach { progress ->
                val frame = swipeUpGuideFrame(progress)
                listOf(
                    "offset" to frame.handOffsetFraction,
                    "alpha" to frame.handAlpha,
                    "ring alpha" to frame.touchRingAlpha,
                    "trail alpha" to frame.trailAlpha,
                ).forEach { (name, value) ->
                    assertEquals("$progress $name lower bound", true, value >= 0f)
                    assertEquals("$progress $name upper bound", true, value <= 1f)
                }
                assertEquals("$progress ring scale lower bound", true, frame.touchRingScale >= 0.70f)
                assertEquals("$progress ring scale upper bound", true, frame.touchRingScale <= 1.50f)
            }
    }

    @Test
    fun horizontalAnimationFrame_isTableDrivenAcrossBothDirections() {
        data class Case(
            val name: String,
            val progress: Float,
            val expectedOffset: Float,
            val expectedAlpha: Float,
            val expectedRingAlpha: Float,
            val expectedTrailAlpha: Float,
        )

        listOf(
            Case("negative progress clamps to start", -0.5f, 0f, 0f, 0f, 0f),
            Case("not-a-number progress safely resets", Float.NaN, 0f, 0f, 0f, 0f),
            Case("hand fades in before contact", 0.07f, 0f, 0.5f, 0f, 0f),
            Case("swipe begins after contact", 0.20f, 0f, 1f, 0.714286f, 0.333333f),
            Case("hand moves halfway to the right", 0.34f, 0.5f, 1f, 0f, 1f),
            Case("hand reaches the right endpoint", 0.48f, 1f, 1f, 0f, 1f),
            Case("hand returns through the center", 0.64f, 0f, 1f, 0f, 1f),
            Case("hand reaches the left endpoint", 0.80f, -1f, 1f, 0f, 0.875f),
            Case("hand fades at the left endpoint", 0.88f, -1f, 0.5f, 0f, 0.375f),
            Case("pause frame is fully hidden", 0.95f, -1f, 0f, 0f, 0f),
            Case("overflow progress clamps to the hidden end", 1.5f, -1f, 0f, 0f, 0f),
        ).forEach { case ->
            val frame = swipeHorizontalGuideFrame(case.progress)
            assertEquals(case.name, case.expectedOffset, frame.handOffsetFraction, 0.0001f)
            assertEquals(case.name, case.expectedAlpha, frame.handAlpha, 0.0001f)
            assertEquals(case.name, case.expectedRingAlpha, frame.touchRingAlpha, 0.0001f)
            assertEquals(case.name, case.expectedTrailAlpha, frame.trailAlpha, 0.0001f)
        }
    }

    @Test
    fun horizontalAnimationFrame_outputsOnlySafeValues() {
        listOf(
            Float.NEGATIVE_INFINITY,
            -1f,
            Float.NaN,
            0f,
            0.5f,
            1f,
            2f,
            Float.POSITIVE_INFINITY,
        ).forEach { progress ->
            val frame = swipeHorizontalGuideFrame(progress)
            assertEquals("$progress offset lower bound", true, frame.handOffsetFraction >= -1f)
            assertEquals("$progress offset upper bound", true, frame.handOffsetFraction <= 1f)
            listOf(
                "alpha" to frame.handAlpha,
                "ring alpha" to frame.touchRingAlpha,
                "trail alpha" to frame.trailAlpha,
            ).forEach { (name, value) ->
                assertEquals("$progress $name lower bound", true, value >= 0f)
                assertEquals("$progress $name upper bound", true, value <= 1f)
            }
            assertEquals("$progress ring scale lower bound", true, frame.touchRingScale >= 0.70f)
            assertEquals("$progress ring scale upper bound", true, frame.touchRingScale <= 1.50f)
        }
    }
}
