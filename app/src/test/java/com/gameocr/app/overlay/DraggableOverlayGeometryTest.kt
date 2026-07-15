package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class DraggableOverlayGeometryTest {

    @Test
    fun constrainOverlayWindowSize_handlesSavedSizesAcrossScreenChanges() {
        data class Case(
            val name: String,
            val requestedWidth: Int,
            val requestedHeight: Int,
            val screenWidth: Int,
            val screenHeight: Int,
            val minimumWidth: Int,
            val minimumHeight: Int,
            val expected: OverlayWindowSize,
        )

        val cases = listOf(
            Case("landscape width on portrait", 1800, 900, 1080, 2400, 480, 300,
                OverlayWindowSize(1080, 900)),
            Case("size already fits", 900, 700, 1080, 2400, 480, 300,
                OverlayWindowSize(900, 700)),
            Case("both dimensions exceed screen", 3000, 3000, 1080, 2400, 480, 300,
                OverlayWindowSize(1080, 2400)),
            Case("requested dimensions below minimum", 100, 80, 1080, 2400, 480, 300,
                OverlayWindowSize(480, 300)),
            Case("screen smaller than minimum", 500, 400, 320, 240, 480, 300,
                OverlayWindowSize(320, 240)),
            Case("invalid requested dimensions", 0, -1, 1080, 2400, 480, 300,
                OverlayWindowSize(480, 300)),
        )

        cases.forEach { case ->
            val actual = constrainOverlayWindowSize(
                requestedWidthPx = case.requestedWidth,
                requestedHeightPx = case.requestedHeight,
                screenWidthPx = case.screenWidth,
                screenHeightPx = case.screenHeight,
                minimumWidthPx = case.minimumWidth,
                minimumHeightPx = case.minimumHeight,
            )
            assertEquals(case.name, case.expected, actual)
        }
    }

    @Test
    fun draggableWindow_constrainsBothInitialShowAndConfigurationChanges() {
        val source = listOf(
            File("src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt"),
            File("app/src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("DraggableOverlayWindow.kt not found")

        assertEquals(2, Regex("constrainOverlayWindowSize\\(").findAll(source).count())
    }
}
