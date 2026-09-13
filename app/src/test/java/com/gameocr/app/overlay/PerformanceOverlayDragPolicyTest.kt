package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceOverlayDragPolicyTest {
    @Test
    fun resolve_tableDriven_keepsPerformanceWindowOnScreen() {
        data class Case(
            val name: String,
            val startX: Int,
            val startY: Int,
            val deltaX: Float,
            val deltaY: Float,
            val overlayWidth: Int,
            val overlayHeight: Int,
            val screenWidth: Int,
            val screenHeight: Int,
            val expected: PerformanceOverlayPosition,
        )

        listOf(
            Case("free movement", 12, 48, 100f, 80f, 240, 160, 1080, 1920, PerformanceOverlayPosition(112, 128)),
            Case("left and top clamp", 20, 30, -200f, -300f, 240, 160, 1080, 1920, PerformanceOverlayPosition(0, 0)),
            Case("right and bottom clamp", 700, 1_500, 500f, 900f, 240, 160, 1080, 1920, PerformanceOverlayPosition(840, 1_760)),
            Case("oversized overlay", 50, 50, 10f, 10f, 2_000, 2_000, 1_080, 1_920, PerformanceOverlayPosition(0, 0)),
            Case("invalid display size", 50, 50, 10f, 10f, 100, 100, 0, -1, PerformanceOverlayPosition(0, 0)),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                PerformanceOverlayDragPolicy.resolve(
                    startX = case.startX,
                    startY = case.startY,
                    deltaX = case.deltaX,
                    deltaY = case.deltaY,
                    overlayWidth = case.overlayWidth,
                    overlayHeight = case.overlayHeight,
                    screenWidth = case.screenWidth,
                    screenHeight = case.screenHeight,
                ),
            )
        }
    }

    @Test
    fun overlayManager_wiresDragAndRaisesAfterEveryTranslationHost() {
        val source = listOf(
            File("src/main/java/com/gameocr/app/overlay/OverlayManager.kt"),
            File("app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt"),
        ).first(File::isFile).readText()

        assertTrue("performance overlay must accept drag gestures", "configurePerformanceOverlayDrag(created)" in source)
        assertTrue("drag must update the WindowManager position", "wm.updateViewLayout(view, params)" in source)
        assertFalse(
            "performance overlay must not remain touch-through",
            source.substringAfter("private fun createPerformanceOverlayView")
                .substringBefore("private fun configurePerformanceOverlayDrag")
                .contains("FLAG_NOT_TOUCHABLE"),
        )
        assertTrue(
            "all translation hosts must raise the diagnostics window after rendering",
            Regex("raisePerformanceOverlayAboveTranslation\\(\\)").findAll(source).count() >= 5,
        )
        assertTrue("raising must preserve same-type overlay ordering", "wm.removeViewImmediate(view)" in source)
    }

    @Test
    fun performanceOverlay_canOnlyBeClosedByItsSettingOrServiceShutdown() {
        val managerSource = listOf(
            File("src/main/java/com/gameocr/app/overlay/OverlayManager.kt"),
            File("app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt"),
        ).first(File::isFile).readText()
        val serviceSource = listOf(
            File("src/main/java/com/gameocr/app/service/CaptureService.kt"),
            File("app/src/main/java/com/gameocr/app/service/CaptureService.kt"),
        ).first(File::isFile).readText()
        val clearBody = managerSource.substringAfter("fun clear()").substringBefore("fun clearForCapture")

        assertFalse("translation clear must keep diagnostics visible", "dismissPerformanceOverlay()" in clearBody)
        assertTrue(
            "disabling the setting must dismiss diagnostics",
            managerSource.substringAfter("internal fun setPerformanceOverlayEnabled")
                .substringBefore("internal fun updatePerformanceOverlay")
                .contains("dismissPerformanceOverlay()"),
        )
        assertTrue(
            "service shutdown must explicitly remove diagnostics",
            serviceSource.contains("overlay?.setPerformanceOverlayEnabled(false)"),
        )
    }
}
