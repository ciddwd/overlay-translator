package com.gameocr.app.ocr

import com.gameocr.app.data.PaddleDetectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleDetectionSizingTest {

    @Test
    fun profile_policy_is_table_driven() {
        data class Case(
            val profile: PaddleDetectionProfile,
            val maxSide: Int,
            val mangaTiling: Boolean,
        )

        val cases = listOf(
            Case(PaddleDetectionProfile.FAST, maxSide = 960, mangaTiling = false),
            Case(PaddleDetectionProfile.ACCURATE, maxSide = 1920, mangaTiling = true),
        )

        cases.forEach { case ->
            assertEquals(case.profile.name, case.maxSide, case.profile.maxSideLen)
            assertEquals(case.profile.name, case.mangaTiling, case.profile.enableMangaTiling)
        }
    }

    @Test
    fun selectable_profiles_are_exactly_speed_and_accuracy() {
        assertEquals(
            listOf(PaddleDetectionProfile.FAST, PaddleDetectionProfile.ACCURATE),
            PaddleDetectionProfile.entries,
        )
    }

    @Test
    fun resize_plan_covers_profiles_orientations_and_small_inputs() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val profile: PaddleDetectionProfile,
            val expectedWidth: Int,
            val expectedHeight: Int,
            val expectedResized: Boolean,
        )

        val cases = listOf(
            Case("portrait-fast", 1422, 2109, PaddleDetectionProfile.FAST, 640, 960, true),
            Case("portrait-accurate", 1422, 2109, PaddleDetectionProfile.ACCURATE, 1280, 1920, true),
            Case("landscape-fast", 2109, 1422, PaddleDetectionProfile.FAST, 960, 640, true),
            Case("already-aligned-no-upscale", 640, 480, PaddleDetectionProfile.ACCURATE, 640, 480, false),
            Case("alignment-only", 601, 799, PaddleDetectionProfile.FAST, 608, 800, true),
        )

        cases.forEach { case ->
            val plan = PaddleDetectionSizing.plan(case.width, case.height, case.profile)
            assertEquals(case.name, case.expectedWidth, plan.targetWidth)
            assertEquals(case.name, case.expectedHeight, plan.targetHeight)
            assertEquals(case.name, case.expectedResized, plan.resized)
            assertTrue(case.name, plan.targetWidth <= case.profile.maxSideLen)
            assertTrue(case.name, plan.targetHeight <= case.profile.maxSideLen)
            assertEquals(
                case.name,
                case.expectedWidth.toLong() * case.expectedHeight,
                plan.inputPixels,
            )
        }
    }

    @Test
    fun output_scale_uses_independent_axes_after_alignment() {
        data class Case(
            val name: String,
            val sourceWidth: Int,
            val sourceHeight: Int,
            val outputWidth: Int,
            val outputHeight: Int,
            val expectedX: Float,
            val expectedY: Float,
        )

        val cases = listOf(
            Case("aligned-portrait", 1422, 2109, 640, 960, 1422f / 640f, 2109f / 960f),
            Case("identity", 640, 480, 640, 480, 1f, 1f),
            Case("defensive-zero-output", 100, 200, 0, 0, 100f, 200f),
        )

        cases.forEach { case ->
            val scale = PaddleDetectionSizing.outputScale(
                case.sourceWidth,
                case.sourceHeight,
                case.outputWidth,
                case.outputHeight,
            )
            assertEquals(case.name, case.expectedX, scale.x, 0.000001f)
            assertEquals(case.name, case.expectedY, scale.y, 0.000001f)
        }

        val portrait = PaddleDetectionSizing.outputScale(1422, 2109, 640, 960)
        assertNotEquals("32-alignment requires separate X/Y mapping", portrait.x, portrait.y)
        assertFalse(portrait.x == portrait.y)
    }
}
