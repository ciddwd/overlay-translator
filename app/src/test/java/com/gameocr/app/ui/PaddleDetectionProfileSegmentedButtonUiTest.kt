package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleDetectionProfileSegmentedButtonUiTest {
    @Test
    fun detectionProfile_usesSingleChoiceSegmentedButton_tableDrivenContract() {
        val source = moduleFile(
            "src/main/java/com/gameocr/app/ui/SettingsScreen.kt"
        ).readText()
        val block = source.substring(
            source.indexOf("val detectionProfiles ="),
            source.indexOf("stringResource(paddleDetectionProfile.descRes)"),
        )
        data class Case(val name: String, val marker: String)

        listOf(
            Case("single-choice row", "SettingsChoiceRow("),
            Case("connected segment", "SettingsChoiceButton("),
            Case("current selection", "selected = paddleDetectionProfile == profile"),
            Case("skip redundant save", "if (paddleDetectionProfile != profile)"),
            Case("persist selection", "viewModel.savePaddleDetectionProfile(profile)"),
            Case("localized label", "Text(stringResource(profile.labelRes))"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", block.contains(case.marker))
        }
        assertFalse("detection profile must no longer use loose chips", block.contains("EngineChip("))
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
