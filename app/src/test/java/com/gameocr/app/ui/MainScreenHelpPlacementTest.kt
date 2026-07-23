package com.gameocr.app.ui

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
            source.indexOf("ActionCard(title = stringResource(R.string.main_section_capture))"),
            source.indexOf("ActionCard(title = stringResource(R.string.main_section_region))"),
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
            Case("usage and help share a row", captureSection, "horizontalArrangement = Arrangement.SpaceBetween", true),
            Case("usage label remains visible", captureSection, "R.string.main_label_usage", true),
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
        assertEquals(
            "onboarding help must have exactly one main-screen entry",
            1,
            Regex("""onClick\s*=\s*onOpenOnboarding""").findAll(source).count(),
        )
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
