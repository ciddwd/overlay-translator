package com.gameocr.app.trigger

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTranslationAccessibilityWiringTest {

    @Test
    fun accessibilityService_hasRequiredCapabilities_withoutPasswordTraversal() {
        data class Case(val name: String, val source: String, val markers: List<String>)
        val config = moduleFile("src/main/res/xml/a11y_config.xml").readText()
        val service = moduleFile(
            "src/main/java/com/gameocr/app/trigger/GameOcrAccessibilityService.kt"
        ).readText()

        listOf(
            Case(
                "service configuration",
                config,
                listOf(
                    "android:canRetrieveWindowContent=\"true\"",
                    "flagReportViewIds",
                ),
            ),
            Case(
                "focused editor access",
                service,
                listOf(
                    "findFocus(AccessibilityNodeInfo.FOCUS_INPUT)",
                    "AccessibilityNodeInfo.ACTION_SET_TEXT",
                    "val passwordField = isPassword",
                    "readNonPasswordInputText(passwordField)",
                    "FocusedInputPolicy.unchanged(original, current)",
                ),
            ),
        ).forEach { case ->
            case.markers.forEach { marker ->
                assertTrue("${case.name}: missing $marker", case.source.contains(marker))
            }
        }

        val captureService = moduleFile(
            "src/main/java/com/gameocr/app/service/CaptureService.kt"
        ).readText()
        val blacklistCheck = captureService.indexOf("InputTranslationAppPolicy.isBlocked(")
        val focusedInputRead = captureService.indexOf(
            "GameOcrAccessibilityService.captureFocusedInput()"
        )
        assertTrue(
            "blocked apps must be rejected before reading the focused editor",
            blacklistCheck >= 0 && blacklistCheck < focusedInputRead,
        )
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
