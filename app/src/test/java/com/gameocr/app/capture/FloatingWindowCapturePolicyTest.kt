package com.gameocr.app.capture

import com.gameocr.app.data.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowCapturePolicyTest {

    @Test
    fun floatingButton_tableDriven_hidesOnlyDuringLoopCapture() {
        data class Case(
            val name: String,
            val loopMode: Boolean,
            val buttonShown: Boolean,
            val expected: Boolean,
        )
        val cases = listOf(
            Case("manual trigger already manages button chrome", false, true, false),
            Case("loop hides attached floating button", true, true, true),
            Case("loop without attached button needs no settle wait", true, false, false),
            Case("manual capture without button needs no action", false, false, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldHideFloatingButtonForCapture(
                    loopMode = case.loopMode,
                    isFloatingButtonShown = case.buttonShown,
                ),
            )
        }
    }

    @Test
    fun action_tableDriven_appliesToEveryCaptureModeAndHonorsAutoHide() {
        data class Case(
            val name: String,
            val renderMode: RenderMode,
            val windowShown: Boolean,
            val autoHide: Boolean,
            val windowBounds: OverlayCaptureRect?,
            val captureRegion: CaptureRegion?,
            val expected: FloatingWindowCaptureAction,
        )

        val bounds = OverlayCaptureRect(100, 200, 500, 800)
        val cases = listOf(
            Case(
                name = "no shown window needs no action",
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = false,
                autoHide = false,
                windowBounds = bounds,
                captureRegion = null,
                expected = FloatingWindowCaptureAction.NONE,
            ),
            Case(
                name = "window outside selected region stays visible",
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = true,
                autoHide = true,
                windowBounds = bounds,
                captureRegion = CaptureRegion(600, 900, 1000, 1400),
                expected = FloatingWindowCaptureAction.NONE,
            ),
            Case(
                name = "default off preserves and masks overlapping floating window",
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = true,
                autoHide = false,
                windowBounds = bounds,
                captureRegion = null,
                expected = FloatingWindowCaptureAction.PRESERVE_AND_MASK,
            ),
            Case(
                name = "enabled auto hide temporarily hides overlapping floating window",
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = true,
                autoHide = true,
                windowBounds = bounds,
                captureRegion = CaptureRegion(400, 700, 900, 1200),
                expected = FloatingWindowCaptureAction.HIDE_TEMPORARILY,
            ),
            Case(
                name = "stale floating window in blocks mode is always hidden",
                renderMode = RenderMode.BLOCKS,
                windowShown = true,
                autoHide = false,
                windowBounds = bounds,
                captureRegion = null,
                expected = FloatingWindowCaptureAction.HIDE_TEMPORARILY,
            ),
            Case(
                name = "unknown shown bounds use safe temporary hide fallback",
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = true,
                autoHide = false,
                windowBounds = null,
                captureRegion = null,
                expected = FloatingWindowCaptureAction.HIDE_TEMPORARILY,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                floatingWindowCaptureAction(
                    renderMode = case.renderMode,
                    isFloatingWindowShown = case.windowShown,
                    autoHideWhenObstructing = case.autoHide,
                    windowBounds = case.windowBounds,
                    captureRegion = case.captureRegion,
                ),
            )
        }
    }

    @Test
    fun maskBounds_tableDriven_scalesAndClipsToCapturedBitmap() {
        data class Case(
            val name: String,
            val bounds: OverlayCaptureRect?,
            val overlayWidth: Int,
            val overlayHeight: Int,
            val captureWidth: Int,
            val captureHeight: Int,
            val expected: OverlayCaptureRect?,
        )

        val cases = listOf(
            Case(
                name = "missing window bounds",
                bounds = null,
                overlayWidth = 1440,
                overlayHeight = 3200,
                captureWidth = 1440,
                captureHeight = 3200,
                expected = null,
            ),
            Case(
                name = "matching coordinate spaces",
                bounds = OverlayCaptureRect(100, 200, 500, 800),
                overlayWidth = 1440,
                overlayHeight = 3200,
                captureWidth = 1440,
                captureHeight = 3200,
                expected = OverlayCaptureRect(100, 200, 500, 800),
            ),
            Case(
                name = "capture is half display resolution",
                bounds = OverlayCaptureRect(100, 200, 500, 800),
                overlayWidth = 1440,
                overlayHeight = 3200,
                captureWidth = 720,
                captureHeight = 1600,
                expected = OverlayCaptureRect(50, 100, 250, 400),
            ),
            Case(
                name = "fractional scaling expands to cover every overlay pixel",
                bounds = OverlayCaptureRect(1, 1, 2, 2),
                overlayWidth = 100,
                overlayHeight = 100,
                captureWidth = 33,
                captureHeight = 33,
                expected = OverlayCaptureRect(0, 0, 1, 1),
            ),
            Case(
                name = "partially offscreen window is clipped",
                bounds = OverlayCaptureRect(-20, -30, 200, 300),
                overlayWidth = 1000,
                overlayHeight = 1000,
                captureWidth = 1000,
                captureHeight = 1000,
                expected = OverlayCaptureRect(0, 0, 200, 300),
            ),
            Case(
                name = "fully offscreen window produces no mask",
                bounds = OverlayCaptureRect(1100, 100, 1200, 300),
                overlayWidth = 1000,
                overlayHeight = 1000,
                captureWidth = 1000,
                captureHeight = 1000,
                expected = null,
            ),
            Case(
                name = "invalid overlay dimensions produce no mask",
                bounds = OverlayCaptureRect(10, 10, 20, 20),
                overlayWidth = 0,
                overlayHeight = 1000,
                captureWidth = 1000,
                captureHeight = 1000,
                expected = null,
            ),
            Case(
                name = "empty bounds produce no mask",
                bounds = OverlayCaptureRect(20, 20, 20, 30),
                overlayWidth = 1000,
                overlayHeight = 1000,
                captureWidth = 1000,
                captureHeight = 1000,
                expected = null,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mapOverlayBoundsToCapture(
                    bounds = case.bounds,
                    overlayWidth = case.overlayWidth,
                    overlayHeight = case.overlayHeight,
                    captureWidth = case.captureWidth,
                    captureHeight = case.captureHeight,
                ),
            )
        }
    }
}
