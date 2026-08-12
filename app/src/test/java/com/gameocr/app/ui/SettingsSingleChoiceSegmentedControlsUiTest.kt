package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSingleChoiceSegmentedControlsUiTest {
    @Test
    fun singleChoiceSettings_useConnectedSegmentedButtons_tableDrivenContract() {
        val source = moduleFile(
            "src/main/java/com/gameocr/app/ui/SettingsScreen.kt"
        ).readText()

        data class Case(
            val name: String,
            val startMarker: String,
            val endMarker: String,
            val requiredMarkers: List<String>,
            val forbiddenMarkers: List<String> = emptyList(),
        )

        val cases = listOf(
            Case(
                name = "display mode",
                startMarker = "val renderModeOptions =",
                endMarker = "if (!layoutControlsEnabled)",
                requiredMarkers = listOf(
                    "RenderMode.BLOCKS",
                    "RenderMode.FLOATING_WINDOW",
                    "selected = renderMode == mode",
                    "if (renderMode != mode)",
                ),
                forbiddenMarkers = listOf(
                    "enabled = mode != RenderMode.FLOATING_WINDOW || layoutControlsEnabled",
                ),
            ),
            Case(
                name = "translation block copy mode",
                startMarker = "val translationBlockCopyOptions =",
                endMarker = "val effectivePlacement =",
                requiredMarkers = listOf(
                    "TranslationBlockInteractionMode.COPY_BUTTON",
                    "TranslationBlockInteractionMode.OPEN_COPY_PANEL",
                    "selected = translationBlockInteractionMode == mode",
                    "if (translationBlockInteractionMode != mode)",
                ),
            ),
            Case(
                name = "merge strength",
                startMarker = "val mergeStrengthOptions =",
                endMarker = "stringResource(when (mergeStrength)",
                requiredMarkers = listOf(
                    "MergeStrength.CONSERVATIVE",
                    "MergeStrength.STANDARD",
                    "MergeStrength.AGGRESSIVE",
                    "selected = mergeStrength == strength",
                    "if (mergeStrength != strength)",
                ),
            ),
        )

        cases.forEach { case ->
            val block = source.substring(
                source.indexOf(case.startMarker),
                source.indexOf(case.endMarker, source.indexOf(case.startMarker)),
            )
            listOf(
                "SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth())",
                "SegmentedButton(",
                "SegmentedButtonDefaults.itemShape(",
                "icon = {}",
                "label = { Text(stringResource(labelRes)) }",
            ).plus(case.requiredMarkers).forEach { marker ->
                assertTrue("${case.name}: missing $marker", block.contains(marker))
            }
            case.forbiddenMarkers.forEach { marker ->
                assertFalse("${case.name}: forbidden $marker", block.contains(marker))
            }
            assertFalse(
                "${case.name}: must no longer use loose chips",
                block.contains("EngineChip("),
            )
        }
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
