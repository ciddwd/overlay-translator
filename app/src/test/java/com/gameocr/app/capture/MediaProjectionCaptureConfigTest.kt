package com.gameocr.app.capture

import android.hardware.display.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProjectionCaptureConfigTest {

    @Test
    fun virtualDisplayFlags_tableDriven() {
        data class Case(
            val name: String,
            val flag: Int,
            val expectedSet: Boolean
        )

        val cases = listOf(
            Case(
                name = "auto mirror is requested",
                flag = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                expectedSet = true
            ),
            Case(
                name = "public display is not requested",
                flag = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                expectedSet = false
            ),
            Case(
                name = "presentation display is not requested",
                flag = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                expectedSet = false
            ),
            Case(
                name = "own content only is not requested",
                flag = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                expectedSet = false
            ),
            Case(
                name = "secure display is not requested",
                flag = DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE,
                expectedSet = false
            )
        )

        val flags = MediaProjectionCaptureConfig.virtualDisplayFlags
        cases.forEach { case ->
            assertEquals(case.name, case.expectedSet, flags and case.flag != 0)
        }
    }
}
