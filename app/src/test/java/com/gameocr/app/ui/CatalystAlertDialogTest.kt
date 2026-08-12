package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalystAlertDialogTest {
    @Test
    fun maxHeight_isTableDrivenAcrossCompactAndLargeScreens() {
        data class Case(
            val name: String,
            val screenHeightDp: Int,
            val expectedMaxHeightDp: Float,
        )

        listOf(
            Case("compact screen", 400, 340f),
            Case("regular phone", 640, 544f),
            Case("just below height cap", 752, 639.2f),
            Case("large phone reaches cap", 800, 640f),
            Case("tablet remains capped", 1_200, 640f),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedMaxHeightDp,
                catalystDialogMaxHeightDp(case.screenHeightDp),
                0.001f,
            )
        }
    }

    @Test
    fun sharedDialog_usesCatalystZincStructureWithBoundedScrollableContent() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/CatalystAlertDialog.kt").readText()

        data class Case(val name: String, val marker: String)

        listOf(
            Case("custom Compose dialog", "Dialog("),
            Case("custom window width", "DialogProperties(usePlatformDefaultWidth = false)"),
            Case("shared zinc palette", "FloatingMenuTourPalette.colors("),
            Case("zinc surface", "color = surfaceColor"),
            Case("zinc border", "BorderStroke(1.dp, borderColor)"),
            Case("Catalyst corner radius", "RoundedCornerShape(8.dp)"),
            Case("bounded height", ".heightIn(max = maxHeight)"),
            Case("scrolling content", "baseModifier.verticalScroll(rememberScrollState())"),
            Case("fixed responsive actions", "FlowRow("),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }

    @Test
    fun allKnownSystemDialogs_useSharedComponent_tableDrivenByScreen() {
        data class Case(
            val name: String,
            val path: String,
            val expectedSharedDialogCalls: Int,
        )

        listOf(
            Case(
                name = "onboarding",
                path = "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt",
                expectedSharedDialogCalls = 1,
            ),
            Case(
                name = "main",
                path = "src/main/java/com/gameocr/app/ui/MainScreen.kt",
                expectedSharedDialogCalls = 5,
            ),
            Case(
                name = "logs",
                path = "src/main/java/com/gameocr/app/ui/LogScreen.kt",
                expectedSharedDialogCalls = 2,
            ),
            Case(
                name = "MiniMax voice manager",
                path = "src/main/java/com/gameocr/app/ui/MiniMaxVoiceManagerDialog.kt",
                expectedSharedDialogCalls = 3,
            ),
            Case(
                name = "settings",
                path = "src/main/java/com/gameocr/app/ui/SettingsScreen.kt",
                expectedSharedDialogCalls = 22,
            ),
        ).forEach { case ->
            val source = sourceFile(case.path).readText()
            assertEquals(
                case.name,
                case.expectedSharedDialogCalls,
                source.countOccurrences("CatalystAlertDialog("),
            )
            assertFalse(
                "${case.name} must not import Material AlertDialog",
                source.contains("import androidx.compose.material3.AlertDialog"),
            )
            assertFalse(
                "${case.name} must not call Material AlertDialog",
                Regex("""\bAlertDialog\s*\(""").containsMatchIn(source),
            )
        }
    }

    @Test
    fun updateDialog_keepsVersionVisibleWhileReleaseNotesScroll() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val loadedState = source
            .substringAfter("is com.gameocr.app.update.UpdateViewModel.State.Loaded -> {")
            .substringBefore("is com.gameocr.app.update.UpdateViewModel.State.Failed -> {")

        data class Case(val name: String, val marker: String)

        listOf(
            Case("shared Catalyst dialog", "CatalystAlertDialog("),
            Case("outer body does not scroll", "contentScrollable = false"),
            Case("release notes scroll", ".verticalScroll(rememberScrollState())"),
            Case("solid zinc primary action", "Button(onClick = {"),
        ).forEach { case ->
            assertTrue(case.name, loadedState.contains(case.marker))
        }
    }

    @Test
    fun transparentOverlayHosts_remainOutsideDialogMigration_tableDriven() {
        listOf(
            "src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt",
            "src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
            "src/main/java/com/gameocr/app/overlay/TranslationBlockCopyOverlay.kt",
            "src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
        ).forEach { path ->
            val source = sourceFile(path).readText()
            assertTrue(path, source.contains("import android.app.Dialog"))
            assertTrue(path, source.contains("Theme_GameOcr_Transparent"))
            assertFalse(path, source.contains("CatalystAlertDialog("))
        }
    }

    private fun String.countOccurrences(needle: String): Int = split(needle).size - 1

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
