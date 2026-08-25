package com.gameocr.app.capture

import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRegionBorderPolicyTest {

    @Test
    fun visibility_tableDriven_requiresEnabledValidRegion() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val region: CaptureRegion?,
            val expected: Boolean,
        )

        listOf(
            Case("enabled valid region", true, CaptureRegion(10, 20, 110, 220), true),
            Case("disabled valid region", false, CaptureRegion(10, 20, 110, 220), false),
            Case("missing region", true, null, false),
            Case("zero width region", true, CaptureRegion(10, 20, 10, 220), false),
            Case("negative height region", true, CaptureRegion(10, 220, 110, 20), false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldShowCaptureRegionBorder(case.enabled, case.region),
            )
        }
    }

    @Test
    fun width_tableDriven_clampsToApprovedRange() {
        data class Case(val input: Int, val expected: Int)

        listOf(
            Case(-10, 1),
            Case(0, 1),
            Case(1, 1),
            Case(4, 4),
            Case(6, 6),
            Case(99, 6),
        ).forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                normalizedCaptureRegionBorderWidthDp(case.input),
            )
        }
    }

    @Test
    fun rect_tableDriven_clipsSafelyAndRejectsInvisibleGeometry() {
        data class Case(
            val name: String,
            val region: CaptureRegion,
            val viewportWidth: Int,
            val viewportHeight: Int,
            val strokeWidthPx: Float,
            val expected: CaptureRegionBorderRect?,
        )

        listOf(
            Case(
                "region inside viewport",
                CaptureRegion(10, 20, 90, 80),
                100,
                100,
                2f,
                CaptureRegionBorderRect(10f, 20f, 90f, 80f),
            ),
            Case(
                "region clips inside half stroke",
                CaptureRegion(-10, -20, 200, 300),
                100,
                100,
                4f,
                CaptureRegionBorderRect(2f, 2f, 98f, 98f),
            ),
            Case(
                "region fully outside viewport",
                CaptureRegion(200, 200, 300, 300),
                100,
                100,
                2f,
                null,
            ),
            Case(
                "invalid region",
                CaptureRegion(10, 10, 10, 50),
                100,
                100,
                2f,
                null,
            ),
            Case(
                "zero viewport",
                CaptureRegion(0, 0, 10, 10),
                0,
                100,
                2f,
                null,
            ),
            Case(
                "viewport cannot fit stroke",
                CaptureRegion(0, 0, 2, 2),
                2,
                2,
                2f,
                null,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                captureRegionBorderRect(
                    region = case.region,
                    viewportWidth = case.viewportWidth,
                    viewportHeight = case.viewportHeight,
                    strokeWidthPx = case.strokeWidthPx,
                ),
            )
        }
    }

    @Test
    fun defaults_useIconBlueAndSolidTwoDpBorder() {
        val settings = Settings()

        assertTrue(settings.captureRegionBorderEnabled)
        assertEquals(0xFF1976D2.toInt(), settings.captureRegionBorderColor)
        assertEquals(2, settings.captureRegionBorderWidthDp)
        assertEquals(CaptureRegionBorderStyle.SOLID, settings.captureRegionBorderStyle)
        assertFalse(shouldShowCaptureRegionBorder(settings.captureRegionBorderEnabled, null))
        assertNull(
            captureRegionBorderRect(
                region = CaptureRegion(0, 0, 10, 10),
                viewportWidth = -1,
                viewportHeight = 10,
                strokeWidthPx = 2f,
            )
        )
    }
}
