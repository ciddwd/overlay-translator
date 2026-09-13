package com.gameocr.app.capture

import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRegionBorderPolicyTest {

    @Test
    fun captureAutoHide_onlyControlsScreenshotSuppression_tableDriven() {
        data class Case(val name: String, val autoHide: Boolean, val capture: Boolean, val editor: Boolean, val wordSelect: Boolean, val hidden: Boolean)
        listOf(
            Case("enabled idle", true, false, false, false, false),
            Case("enabled capture", true, true, false, false, true),
            Case("disabled idle", false, false, false, false, false),
            Case("disabled capture keeps region visible", false, true, false, false, false),
            Case("disabled still hides for region editor", false, false, true, false, true),
            Case("disabled still hides for word selection", false, false, false, true, true),
            Case("capture ends while editor remains open", false, false, true, true, true),
            Case("overlapping capture and editor", false, true, true, false, true),
            Case("overlapping capture and selection", false, true, false, true, true),
        ).forEach { case ->
            assertEquals(case.name, case.hidden, shouldHideCaptureRegionBorder(
                hiddenForCapture = case.capture,
                hiddenForEditor = case.editor,
                hiddenForWordSelect = case.wordSelect,
                autoHideOnCapture = case.autoHide,
            ))
        }
    }

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
    fun temporaryVisibilitySuppression_tableDriven_hidesForEveryActiveCaptureSurface() {
        data class Case(
            val name: String,
            val hiddenForCapture: Boolean,
            val hiddenForEditor: Boolean,
            val hiddenForWordSelect: Boolean,
            val expectedHidden: Boolean,
        )

        listOf(
            Case("normal display", false, false, false, false),
            Case("screenshot in progress", true, false, false, true),
            Case("region editor", false, true, false, true),
            Case("word selection", false, false, true, true),
            Case("overlapping reasons", true, true, true, true),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedHidden,
                shouldHideCaptureRegionBorder(
                    hiddenForCapture = case.hiddenForCapture,
                    hiddenForEditor = case.hiddenForEditor,
                    hiddenForWordSelect = case.hiddenForWordSelect,
                ),
            )
        }
    }

    @Test
    fun move_tableDriven_preservesSizeAndClampsToDisplay() {
        data class Case(
            val name: String,
            val region: CaptureRegion,
            val dx: Int,
            val dy: Int,
            val screenWidth: Int,
            val screenHeight: Int,
            val expected: CaptureRegion?,
        )

        listOf(
            Case(
                "moves freely",
                CaptureRegion(100, 200, 500, 700),
                40,
                -60,
                1080,
                2400,
                CaptureRegion(140, 140, 540, 640),
            ),
            Case(
                "clamps top left",
                CaptureRegion(100, 200, 500, 700),
                -1000,
                -1000,
                1080,
                2400,
                CaptureRegion(0, 0, 400, 500),
            ),
            Case(
                "clamps bottom right",
                CaptureRegion(100, 200, 500, 700),
                5000,
                5000,
                1080,
                2400,
                CaptureRegion(680, 1900, 1080, 2400),
            ),
            Case(
                "overflow safe",
                CaptureRegion(100, 200, 500, 700),
                Int.MAX_VALUE,
                Int.MIN_VALUE,
                1080,
                2400,
                CaptureRegion(680, 0, 1080, 500),
            ),
            Case(
                "region larger than display rejected",
                CaptureRegion(0, 0, 1200, 500),
                1,
                1,
                1080,
                2400,
                null,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                movedCaptureRegion(
                    case.region,
                    case.dx,
                    case.dy,
                    case.screenWidth,
                    case.screenHeight,
                ),
            )
        }
    }

    @Test
    fun resize_tableDriven_keepsOppositeCornerAndMinimumSize() {
        data class Case(
            val name: String,
            val corner: CaptureRegionResizeCorner,
            val dx: Int,
            val dy: Int,
            val expected: CaptureRegion?,
        )

        val region = CaptureRegion(100, 200, 500, 700)
        listOf(
            Case(
                "top left expands to display edge",
                CaptureRegionResizeCorner.TOP_LEFT,
                -500,
                -500,
                CaptureRegion(0, 0, 500, 700),
            ),
            Case(
                "top right resizes independently",
                CaptureRegionResizeCorner.TOP_RIGHT,
                200,
                100,
                CaptureRegion(100, 300, 700, 700),
            ),
            Case(
                "bottom left respects minimum",
                CaptureRegionResizeCorner.BOTTOM_LEFT,
                1000,
                -1000,
                CaptureRegion(460, 200, 500, 240),
            ),
            Case(
                "bottom right clamps to display",
                CaptureRegionResizeCorner.BOTTOM_RIGHT,
                5000,
                5000,
                CaptureRegion(100, 200, 1080, 2400),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resizedCaptureRegion(
                    region = region,
                    corner = case.corner,
                    deltaX = case.dx,
                    deltaY = case.dy,
                    screenWidth = 1080,
                    screenHeight = 2400,
                    minSidePx = 40,
                ),
            )
        }

        assertNull(
            resizedCaptureRegion(
                region = region,
                corner = CaptureRegionResizeCorner.TOP_LEFT,
                deltaX = 0,
                deltaY = 0,
                screenWidth = 20,
                screenHeight = 20,
                minSidePx = 40,
            )
        )
    }

    @Test
    fun defaults_useIconBlueAndSolidTwoDpBorder() {
        val settings = Settings()

        assertTrue(settings.captureRegionBorderEnabled)
        assertTrue(settings.captureRegionHideOnCapture)
        assertEquals(0xFF1976D2.toInt(), settings.captureRegionBorderColor)
        assertEquals(2, settings.captureRegionBorderWidthDp)
        assertEquals(CaptureRegionBorderStyle.SOLID, settings.captureRegionBorderStyle)
        assertFalse(settings.captureRegionAdjustmentEnabled)
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
