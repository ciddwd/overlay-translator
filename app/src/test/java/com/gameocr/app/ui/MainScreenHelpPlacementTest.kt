package com.gameocr.app.ui

import com.gameocr.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenHelpPlacementTest {

    @Test
    fun helpAction_tableDriven_isOppositeUsageAndAbsentFromTopBar() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val topBar = source.substring(
            source.indexOf("TopAppBar("),
            source.indexOf("snackbarHost ="),
        )
        val captureSection = source.substring(
            source.indexOf("CaptureGalleryCarousel("),
            source.indexOf("ActionCard(title = stringResource(R.string.main_section_region))"),
        )
        val overlayPermissionGate = captureSection.substring(
            captureSection.indexOf("if (!canDrawOverlay)"),
            captureSection.indexOf("// 已授权时恢复原使用说明布局"),
        )
        val usageSection = captureSection.substring(
            captureSection.indexOf("// 已授权时恢复原使用说明布局"),
        )

        data class Case(
            val name: String,
            val content: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        val cases = listOf(
            Case("top bar no longer owns help", topBar, "onClick = onOpenOnboarding", false),
            Case("capture section owns help", captureSection, "onClick = onOpenOnboarding", true),
            Case(
                "overlay permission gate no longer hides help",
                overlayPermissionGate,
                "onClick = onOpenOnboarding",
                false,
            ),
            Case("usage and help share a row", captureSection, "horizontalArrangement = Arrangement.SpaceBetween", true),
            Case("usage label remains visible", captureSection, "R.string.main_label_usage", true),
            Case(
                "usage description selects permission-aware copy in both layouts",
                usageSection,
                "mainUsageTextRes(canDrawOverlay)",
                true,
            ),
            Case(
                "usage copy keeps compact local spacing",
                captureSection,
                "verticalArrangement = Arrangement.spacedBy(2.dp)",
                true,
            ),
            Case("help keeps its icon", captureSection, "Icons.AutoMirrored.Outlined.HelpOutline", true),
            Case("help keeps its text", captureSection, "R.string.main_help", true),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.marker in case.content)
        }
        assertTrue(
            "usage label must remain to the left of the help action",
            captureSection.indexOf("R.string.main_label_usage") <
                captureSection.indexOf("onClick = onOpenOnboarding"),
        )
        assertTrue(
            "unauthorized hint must remain between its label and the help action",
            usageSection.indexOf("R.string.main_label_usage") <
                usageSection.indexOf("mainUsageTextRes(canDrawOverlay)") &&
                usageSection.indexOf("mainUsageTextRes(canDrawOverlay)") <
                usageSection.indexOf("onClick = onOpenOnboarding"),
        )
        assertTrue(
            "authorized instructions must return below the original help row",
            usageSection.indexOf("onClick = onOpenOnboarding") <
                usageSection.lastIndexOf("mainUsageTextRes(canDrawOverlay)"),
        )
        assertEquals(
            "onboarding help must have exactly one main-screen entry",
            1,
            Regex("""onClick\s*=\s*onOpenOnboarding""").findAll(source).count(),
        )
    }

    @Test
    fun usageDescription_tableDriven_matchesOverlayPermissionState() {
        data class StateCase(
            val name: String,
            val canDrawOverlay: Boolean,
            val expected: Int,
        )

        listOf(
            StateCase(
                "authorized users see floating ball instructions",
                true,
                R.string.main_usage_text,
            ),
            StateCase(
                "unauthorized users see the permission hint",
                false,
                R.string.main_usage_overlay_permission_required,
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, mainUsageTextRes(case.canDrawOverlay))
        }

        val englishStrings = sourceFile("src/main/res/values/strings.xml").readText()
        val chineseStrings = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
        data class CopyCase(
            val name: String,
            val content: String,
            val expectedCopy: String,
        )

        listOf(
            CopyCase(
                "English permission hint",
                englishStrings,
                "Tap the button above to grant the overlay permission first.",
            ),
            CopyCase(
                "Chinese permission hint",
                chineseStrings,
                "请点击上方按钮，先授予悬浮窗权限。",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.expectedCopy in case.content)
        }
    }

    @Test
    fun homeCompatibilityRestoration_tableDriven() {
        val main = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val settings = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val englishStrings = sourceFile("src/main/res/values/strings.xml").readText()
        val chineseStrings = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
        val statusCard = main.substring(
            main.indexOf("private fun StatusCard("),
            main.indexOf("private fun StatusRow("),
        )
        val disabledRegion = main.substring(
            main.indexOf("BEGIN_DISABLED_SCREENSHOT_REGION"),
            main.indexOf("END_DISABLED_SCREENSHOT_REGION"),
        )

        data class Case(val name: String, val actual: Boolean)

        listOf(
            Case(
                "capture region code remains preserved inside the disabled block",
                disabledRegion.contains(
                    "ActionCard(title = stringResource(R.string.main_section_region))"
                ),
            ),
            Case(
                "system compatibility is restored to the home screen",
                main.contains(
                    "ActionCard(title = stringResource(R.string.main_section_rom_guide))"
                ),
            ),
            Case(
                "system compatibility is removed from settings",
                !settings.contains("SectionKeys.SYSTEM_COMPATIBILITY") &&
                    !settings.contains("SystemCompatibilityGuide()"),
            ),
            Case(
                "battery whitelist is restored to current status",
                statusCard.contains("R.string.main_status_battery_whitelist"),
            ),
            Case(
                "home compatibility retains the auto-start action",
                main.contains("RomHelper.autoStartIntents(context)"),
            ),
            Case(
                "home compatibility retains the battery whitelist action",
                main.contains("RomHelper.batteryWhitelistIntents(context)"),
            ),
            Case(
                "battery whitelist status refreshes when returning to the home screen",
                main.contains(
                    "batteryOk = RomHelper.isIgnoringBatteryOptimizations(context)"
                ),
            ),
            Case(
                "onboarding copy points back to home compatibility",
                englishStrings.contains(
                    "System compatibility section on the home screen"
                ) && chineseStrings.contains("主屏“系统兼容”区域"),
            ),
        ).forEach { case ->
            assertTrue(case.name, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
