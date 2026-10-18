package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSingleChoiceSegmentedControlsUiTest {
    @Test
    fun singleChoiceSettings_shareTheSameChoiceControl_tableDrivenContract() {
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
                name = "theme mode",
                startMarker = "val themeOptions =",
                endMarker = "internal fun SwitchRow(",
                requiredMarkers = listOf(
                    "ThemeMode.FOLLOW_SYSTEM", "ThemeMode.LIGHT", "ThemeMode.DARK",
                    "selected = mode == option", "controller.setMode(option)",
                ),
            ),
            Case(
                name = "translation border style",
                startMarker = "val customBorderOptions =",
                endMarker = "R.string.settings_overlay_font_label",
                requiredMarkers = listOf(
                    "BorderStyle.SOLID", "BorderStyle.DASHED", "BorderStyle.DOTTED", "BorderStyle.DOUBLE", "BorderStyle.GROOVE",
                    "selected = customBorderStyle == style",
                    "viewModel.saveCustomBorderStyle(style)",
                ),
            ),
            Case(
                name = "capture region border style",
                startMarker = "val captureBorderOptions =",
                endMarker = "// —— 输入翻译 ——",
                requiredMarkers = listOf(
                    "CaptureRegionBorderStyle.SOLID", "CaptureRegionBorderStyle.DASHED", "CaptureRegionBorderStyle.DOTTED",
                    "selected = captureRegionBorderStyle == style",
                    "viewModel.saveCaptureRegionBorderStyle(style)",
                ),
            ),
            Case(
                name = "translation placement",
                startMarker = "val placementOptions =",
                endMarker = "stringResource(R.string.settings_offset_x_format",
                requiredMarkers = listOf(
                    "OverlayPlacement.BELOW",
                    "OverlayPlacement.OVERLAP",
                    "OverlayPlacement.ABOVE",
                    "selected = effectivePlacement == option",
                    "enabled = layoutControlsEnabled",
                    "onClick = { placement = option }",
                ),
            ),
            Case(
                name = "loop trigger mode",
                startMarker = "val loopTriggerOptions =",
                endMarker = "if (loopTriggerMode == LoopTriggerMode.FIXED_INTERVAL)",
                requiredMarkers = listOf(
                    "LoopTriggerMode.FIXED_INTERVAL",
                    "LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE",
                    "LoopTriggerMode.SETTLED_PAGE",
                    "selected = loopTriggerMode == mode",
                    "if (loopTriggerMode != mode)",
                ),
            ),
            Case(
                name = "display mode",
                startMarker = "val renderModeOptions =",
                endMarker = "if (renderMode == RenderMode.FLOATING_WINDOW)",
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
                endMarker = "stringResource(when (shownMergeStrength)",
                requiredMarkers = listOf(
                    "MergeStrength.CONSERVATIVE",
                    "MergeStrength.STANDARD",
                    "MergeStrength.AGGRESSIVE",
                    "MergeStrength.ALL",
                    "mergeStrengthOptionsFor(renderMode)",
                    "selected = shownMergeStrength == strength",
                    "if (mergeStrength != strength)",
                ),
            ),
            Case(
                name = "NiuTrans version",
                startMarker = "val niuTransModes =",
                endMarker = "SecretTextField(",
                requiredMarkers = listOf(
                    "NiuTransMode.FLASH",
                    "NiuTransMode.PRO",
                    "selected = niuTransMode == mode",
                    "if (niuTransMode != mode)",
                ),
            ),
        )

        cases.forEach { case ->
            val block = source.substring(
                source.indexOf(case.startMarker),
                source.indexOf(case.endMarker, source.indexOf(case.startMarker)),
            )
            listOf(
                "SettingsChoiceRow(",
                "SettingsChoiceButton(",
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
