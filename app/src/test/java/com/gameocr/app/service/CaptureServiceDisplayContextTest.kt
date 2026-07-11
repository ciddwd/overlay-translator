package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureServiceDisplayContextTest {

    @Test
    fun currentDisplayRotation_tableDriven_avoidsVisualContextOnlyDisplayApis() {
        data class Case(val name: String, val forbidden: String)

        val source = File("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()
        val function = functionSnippet(source, "private fun currentDisplayRotation(")
        val cases = listOf(
            Case("nullable Context display", "display?.rotation"),
            Case("direct Context display", "display.rotation"),
            Case("getDisplay call", "getDisplay()")
        )

        cases.forEach { case ->
            assertFalse(case.name, case.forbidden in function)
        }
        assertTrue(
            "service-safe WindowManager display rotation should be used",
            "wm.defaultDisplay.rotation" in function
        )
    }

    @Test
    fun mediaProjectionResize_tableDriven_wiresRotationToExistingVirtualDisplay() {
        data class Case(val name: String, val source: String, val expected: String)

        val serviceSource = File("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()
        val screenshotterSource =
            File("src/main/java/com/gameocr/app/capture/MediaProjectionScreenshotter.kt").readText()
        val resizeFunction = functionSnippet(
            screenshotterSource,
            "private fun resizeProjectionOnHandler("
        )
        val cases = listOf(
            Case(
                "service configuration fallback",
                serviceSource,
                "resizeProjectionForCurrentDisplay(\"serviceConfigurationChanged\")"
            ),
            Case(
                "platform captured-content callback",
                screenshotterSource,
                "override fun onCapturedContentResize(width: Int, height: Int)"
            ),
            Case("resize existing virtual display", resizeFunction, "display.resize(targetWidth, targetHeight, density)"),
            Case("attach resized ImageReader surface", resizeFunction, "display.setSurface(newReader.surface)"),
            Case("replace active ImageReader", resizeFunction, "imageReader = newReader")
        )

        cases.forEach { case ->
            assertTrue(case.name, case.expected in case.source)
        }
        assertFalse(
            "resize must not create a second VirtualDisplay with the same projection token",
            "createVirtualDisplay(" in resizeFunction
        )
    }

    private fun functionSnippet(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val nextFunction = source.indexOf("\n    private fun ", start + signature.length)
        return if (nextFunction >= 0) source.substring(start, nextFunction) else source.substring(start)
    }
}
