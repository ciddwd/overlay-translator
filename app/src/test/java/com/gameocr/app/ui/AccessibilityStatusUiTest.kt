package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityStatusUiTest {
    @Test
    fun mainAndSettings_recheckAndRenderAccessibilityStatus() {
        data class Case(val name: String, val source: String, val markers: List<String>)
        val main = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val settings = moduleFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()

        listOf(
            Case(
                "main system compatibility",
                main,
                listOf(
                    "AccessibilityServiceStatus.isEnabled(context)",
                    "R.string.settings_btn_a11y_enabled",
                    "Settings.ACTION_ACCESSIBILITY_SETTINGS",
                ),
            ),
            Case(
                "settings accessibility actions",
                settings,
                listOf(
                    "rememberAccessibilityServiceEnabled(context)",
                    "R.string.settings_btn_a11y_enabled",
                    "AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS",
                ),
            ),
        ).forEach { case ->
            case.markers.forEach { marker ->
                assertTrue("${case.name}: missing $marker", case.source.contains(marker))
            }
        }
        assertEquals(
            "both settings accessibility buttons use the checked state",
            2,
            settings.windowed("enabled = !accessibilityServiceEnabled".length)
                .count { it == "enabled = !accessibilityServiceEnabled" },
        )
        val statusCard = main.substring(
            main.indexOf("private fun StatusCard("),
            main.indexOf("internal fun PresetCarouselCard("),
        )
        assertFalse(
            "current status must not render accessibility service",
            statusCard.contains("R.string.main_status_accessibility_service"),
        )
    }

    @Test
    fun shizukuOverlayGrant_tableDriven_doesNotOpenUnrelatedAccessibilitySettings() {
        val main = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val settings = moduleFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val overlayGrant = main.substringAfter("suspend fun grantOverlayPermissionViaShizuku()")
            .substringBefore("internal suspend fun presetModelIssues(")

        data class Case(val name: String, val content: String, val marker: String, val expected: Boolean)
        listOf(
            Case("auto configuration remains enabled", overlayGrant, "appPermissions.configure().overlayGranted", true),
            Case("overlay result does not depend on accessibility connection", overlayGrant, "accessibilityConnected", false),
            Case("overlay grant cannot navigate", overlayGrant, "startActivity", false),
            Case("no automatic accessibility settings redirect", overlayGrant, "ACTION_ACCESSIBILITY_SETTINGS", false),
            Case("button uses context-free grant action", main, "viewModel.grantOverlayPermissionViaShizuku()", true),
            Case("failed overlay grant keeps its own recovery", main, "if (!granted || !canDrawOverlay)", true),
            Case("overlay settings recovery is retained", main, "openOverlayPermissionSettings(context)", true),
            Case("explicit main accessibility action remains", main, "if (!viewModel.enableAccessibilityViaShizuku())", true),
            Case("explicit main action retains system settings", main, "Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)", true),
            Case("explicit settings accessibility actions remain", settings, "if (!viewModel.enableAccessibilityViaShizuku())", true),
            Case("explicit settings actions retain system settings", settings, "Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)", true),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.content.contains(case.marker))
        }
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
