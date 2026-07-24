package com.gameocr.app.capture

import com.gameocr.app.data.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowCapturePolicyTest {

    @Test
    fun action_tableDriven_keepsLoopFloatingWindowVisible() {
        data class Case(
            val name: String,
            val loopMode: Boolean,
            val renderMode: RenderMode,
            val windowShown: Boolean,
            val expected: FloatingWindowCaptureAction,
        )

        val cases = listOf(
            Case(
                name = "single capture leaves shown floating window unchanged",
                loopMode = false,
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = true,
                expected = FloatingWindowCaptureAction.NONE,
            ),
            Case(
                name = "loop has no floating window yet",
                loopMode = true,
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = false,
                expected = FloatingWindowCaptureAction.NONE,
            ),
            Case(
                name = "loop floating window stays visible and is masked in bitmap",
                loopMode = true,
                renderMode = RenderMode.FLOATING_WINDOW,
                windowShown = true,
                expected = FloatingWindowCaptureAction.PRESERVE_AND_MASK,
            ),
            Case(
                name = "stale floating window in blocks mode is hidden for capture",
                loopMode = true,
                renderMode = RenderMode.BLOCKS,
                windowShown = true,
                expected = FloatingWindowCaptureAction.HIDE_TEMPORARILY,
            ),
            Case(
                name = "blocks mode without stale floating window needs no action",
                loopMode = true,
                renderMode = RenderMode.BLOCKS,
                windowShown = false,
                expected = FloatingWindowCaptureAction.NONE,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                floatingWindowCaptureAction(
                    loopMode = case.loopMode,
                    renderMode = case.renderMode,
                    isFloatingWindowShown = case.windowShown,
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
