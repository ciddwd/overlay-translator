package com.gameocr.app.ui

import com.gameocr.app.readSourceText
import com.gameocr.app.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenHelpPlacementTest {

    @Test
    fun helpAction_tableDriven_isTheCaptureCardUsageRowAndAbsentFromTopBar() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readSourceText()
        val card = sourceFile("src/main/java/com/gameocr/app/ui/CaptureServiceCard.kt").readSourceText()
        val topBar = source.substring(
            source.indexOf("TopAppBar("),
            source.indexOf("snackbarHost ="),
        )
        val captureSection = source.substring(
            source.indexOf("capturePage = { pageModifier, onNaturalHeightChanged ->"),
            source.indexOf("galleryPage = { pageModifier ->"),
        )
        val usageRow = card.substring(card.indexOf("private fun UsageRow("))

        data class Case(
            val name: String,
            val content: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        listOf(
            Case("top bar no longer owns help", topBar, "onOpenOnboarding", false),
            // Help asks for confirmation first; the dialog then opens onboarding.
            Case("capture card owns help", captureSection, "onOpenHelp = { showHelpConfirmDialog = true }", true),
            Case("confirmed help opens onboarding", source,
                "showHelpConfirmDialog = false\n                    onOpenOnboarding()", true),
            Case("usage text is permission-aware", captureSection, "mainUsageTextRes(showCaptureControls)", true),
            Case("usage row is shown in setup and ready states", card, "UsageRow(usageText = usageText, onClick = onOpenHelp)", true),
            Case("usage label remains visible", usageRow, "R.string.main_label_usage", true),
            Case("help keeps its icon", usageRow, "Icons.AutoMirrored.Outlined.HelpOutline", true),
            Case("help keeps its text", usageRow, "R.string.main_help", true),
        ).forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.marker in case.content)
        }
        assertTrue(
            "usage label must remain to the left of the help action",
            usageRow.indexOf("R.string.main_label_usage") < usageRow.indexOf("R.string.main_help"),
        )
        assertEquals(
            "onboarding help must have exactly one main-screen entry",
            1,
            // The one call is the help confirmation dialog's; the capture card only opens that dialog.
            Regex("""\bonOpenOnboarding\(\)""").findAll(source).count(),
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

        val englishStrings = sourceFile("src/main/res/values/strings.xml").readSourceText()
        val chineseStrings = sourceFile("src/main/res/values-zh-rCN/strings.xml").readSourceText()
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
        val main = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readSourceText()
        val settings = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readSourceText()
        val englishStrings = sourceFile("src/main/res/values/strings.xml").readSourceText()
        val chineseStrings = sourceFile("src/main/res/values-zh-rCN/strings.xml").readSourceText()
        val captureCard = sourceFile("src/main/java/com/gameocr/app/ui/CaptureServiceCard.kt").readSourceText()
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
                    "ActionCard(title = stringResource(R.string.main_section_rom_guide), listStyle = true)"
                ),
            ),
            Case(
                "system compatibility is removed from settings",
                !settings.contains("SectionKeys.SYSTEM_COMPATIBILITY") &&
                    !settings.contains("SystemCompatibilityGuide()"),
            ),
            Case(
                "battery whitelist is a key status tile on the capture card",
                captureCard.contains("R.string.main_key_background") &&
                    captureCard.contains("ok = batteryOk") &&
                    main.contains("batteryOk = batteryOk"),
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
