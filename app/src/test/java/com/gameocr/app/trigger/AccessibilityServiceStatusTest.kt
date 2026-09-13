package com.gameocr.app.trigger

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityServiceStatusTest {
    @Test
    fun enabledServiceMatch_requiresExactPackageAndClass() {
        data class Case(
            val packageName: String?,
            val className: String?,
            val expected: Boolean,
        )

        listOf(
            Case("com.gameocr.app.debug", "com.gameocr.app.trigger.GameOcrAccessibilityService", true),
            Case("com.gameocr.app", "com.gameocr.app.trigger.GameOcrAccessibilityService", false),
            Case("com.gameocr.app.debug", "com.example.OtherAccessibilityService", false),
            Case(null, "com.gameocr.app.trigger.GameOcrAccessibilityService", false),
            Case("com.gameocr.app.debug", null, false),
        ).forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                matchesAccessibilityService(
                    packageName = case.packageName,
                    className = case.className,
                    expectedPackageName = "com.gameocr.app.debug",
                    expectedClassName = "com.gameocr.app.trigger.GameOcrAccessibilityService",
                ),
            )
        }
    }
}
