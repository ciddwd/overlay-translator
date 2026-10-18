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
            Case("bounded height", ".heightIn(max = maxHeight)"),
            Case("scrolling content", "baseModifier.verticalScroll(rememberScrollState())"),
            Case("fixed responsive actions", "FlowRow("),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }

    @Test
    fun everyAppDialog_usesSharedComponent() {
        // Scans all app sources, so a new screen cannot reintroduce the stock Material dialog.
        val root = listOf(File("src/main/java/com/gameocr/app"), File("app/src/main/java/com/gameocr/app"))
            .first(File::isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "CatalystAlertDialog.kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("import androidx.compose.material3.AlertDialog") ||
                    Regex("""\bAlertDialog\s*\(""").containsMatchIn(source)
            }
            .map { it.name }
            .toList()
        assertTrue("Material AlertDialog used in: $offenders", offenders.isEmpty())
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


    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
