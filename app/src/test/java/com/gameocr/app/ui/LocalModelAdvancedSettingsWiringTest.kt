package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-model generation values are edited only on the model's own page; settings keep a shortcut. */
class LocalModelAdvancedSettingsWiringTest {
    @Test
    fun `settings card links to the selected model page instead of editing values inline`() {
        val screen = source("ui/SettingsScreen")
        val viewModel = source("ui/SettingsViewModel")
        val load = screen.substringAfter("LaunchedEffect(translatorEngine, localModelId, showModelManagement,")
            .substringBefore("if (showModelManagement) {")
        val read = viewModel.substringAfter("internal suspend fun selectedLocalModel(")
            .substringBefore("internal suspend fun quickLocalModels(")
        val applyPreset = screen.substringAfter("fun applyPresetSettingsToUi(s: Settings) {")
            .substringBefore("fun presetDisplayNameForMessage(")
        data class Case(val name: String, val code: String, val required: List<String>, val forbidden: List<String> = emptyList())
        listOf(
            Case("no inline sliders or second save path remain", screen, emptyList(),
                listOf("llmInferenceParamsExpanded", "localLlmAdvancedSaving", "saveLocalLlmInferenceParams",
                    "R.string.settings_llm_context_size", "R.string.settings_llm_max_new_tokens")),
            Case("view model has no inline save path", viewModel, emptyList(), listOf("saveLocalLlmInferenceParams")),
            Case("switching models and returning from details refresh the shortcut", load,
                listOf("selectedLocalModel = null", "selectedLocalModel = viewModel.selectedLocalModel(buildSnapshot())")),
            Case("shortcut resolves the same per-model catalog entry as details", read,
                listOf("selectedLocalModelId(settings)", "localModelCatalog.entry(id)")),
            Case("presets cannot overwrite independent model parameters", applyPreset,
                listOf("translatorEngine = s.translatorEngine", "localModelId = s.localModelId"),
                listOf("localLlmContextSize = s.localLlmContextSize", "localLlmMaxNewTokens = s.localLlmMaxNewTokens")),
        ).forEach { case ->
            case.required.forEach { assertTrue("${case.name}: missing $it", case.code.contains(it)) }
            case.forbidden.forEach { assertFalse("${case.name}: unexpected $it", case.code.contains(it)) }
        }
    }

    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/gameocr/app/$path.kt"),
        File("app/src/main/java/com/gameocr/app/$path.kt"),
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
