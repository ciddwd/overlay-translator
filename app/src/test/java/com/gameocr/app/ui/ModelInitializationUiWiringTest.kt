package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring complements pure draft tests without adding a separate device/test APK. */
class ModelInitializationUiWiringTest {
    @Test
    fun `model details split prompt generation loading and info into tabs`() {
        val source = source("ModelManagementScreen")
        val detail = source.substringAfter("private fun ModelDetailScreen(").substringBefore("private val IDLE_MINUTE_OPTIONS")
        val prompt = detail.substringAfter("ModelDetailTab.PROMPT ->").substringBefore("ModelDetailTab.GENERATION -> {")
        val generation = detail.substringAfter("ModelDetailTab.GENERATION -> {").substringBefore("ModelDetailTab.LOADING -> {")
        val loading = detail.substringAfter("ModelDetailTab.LOADING -> {").substringBefore("ModelDetailTab.INFO ->")
        val bottomBar = detail.substringAfter("bottomBar = {").substringBefore("snackbarHost =")
        val tabEnum = source.substringAfter("internal enum class ModelDetailTab(").substringBefore("private fun ModelNumericField(")
        val tabs = source.substringAfter("private fun ModelTabs(").substringBefore("private fun ModelSliderRow(")
        data class Case(val name: String, val code: String, val required: List<String>, val forbidden: List<String> = emptyList())
        listOf(
            Case("specialized models have no prompt tab", tabEnum, listOf(
                "if (locked) listOf(GENERATION, LOADING, INFO) else entries.toList()")),
            Case("tabs are per model and accessible", detail + tabs, listOf(
                "val locked = entry.specialized && !draft.unlocked", "ModelDetailTab.forModel(locked)", "rememberSaveable(entry.id) { mutableStateOf(tabs.first()) }",
                "SecondaryTabRow(selectedTabIndex = selectedIndex)", "Tab(", "selected = index == selectedIndex",
                "onClick = { onSelect(tab) }", "tabs.indexOf(selected).coerceAtLeast(0)"), listOf("PillTabs(")),
            Case("tab bar stays above scrollable content", detail.substringAfter(") { padding ->"), listOf(
                "Column(Modifier.fillMaxSize().padding(padding))", "ModelTabs(tabs, selectedTab)",
                "modifier = Modifier.weight(1f).fillMaxWidth()"), listOf("item(key = \"tabs\")")),
            Case("bottom bar saves, resets the current tab, or tries a sample on the prompt tab", bottomBar, listOf(
                "R.string.model_try_sample", "config?.let(onTrySample)", "onClick = ::resetTab",
                "R.string.settings_save_btn", "enabled = !busy && config != null && changed")),
            Case("prompt tab edits prompt, context option and shows a preview", prompt, listOf(
                "value = draft.prompt", "R.string.model_manager_translation_prompt", "R.string.model_prompt_hint",
                "\"{text}\"", "\"{target}\"", "\"{source}\"", "draft.copy(includeContext = it)",
                "ManagedLocalModelRequestPolicy.withInstructions("), listOf("ModelNumericField(")),
            Case("generation owns context, output and sampling", generation, listOf(
                "ModelSliderRow(", "512..4096, 512", "coerceAtMost(value - 8)", "R.string.model_context_reinit_hint",
                "R.string.model_output_hint", "R.string.model_temperature_hint", "R.string.model_top_p_hint",
                "R.string.model_top_k_hint", "R.string.model_repeat_penalty_hint",
                "draft.copy(temperature = modelSamplingValueLabel(it))", "draft.copy(topK = it.toString())"),
                listOf("value = draft.prompt", "entry.chatTemplate", "ModelNumericField(")),
            Case("specialized sampling is shown read-only", generation, listOf(
                "if (locked) item(key = \"locked\")", "R.string.model_locked_title", "R.string.model_locked_hint",
                "val samplingEnabled = !busy && !locked", "if (locked) samplingDefaults.temperature")),
            Case("estimate reacts to model metadata and the current valid draft", detail, listOf(
                "remember(entry.sizeBytes, entry.memoryMetadata, config)",
                "config?.let { LocalModelMemoryPolicy.estimate(entry, it) }")),
            Case("memory card splits weights and KV and shows available memory", loading, listOf(
                "estimate.conservativeTotalBytes", "estimate.weightsBytes", "estimate.kvBytes",
                "R.string.model_memory_available", "availableMemory")),
            Case("memory options keep the draft", loading, listOf(
                "draft = draft.copy(idleUnloadMinutes = it.toString())", "draft = draft.copy(loadMode = it)",
                "loadModeHint(draft.loadMode)", "R.string.model_load_mode", "IDLE_MINUTE_OPTIONS")),
            Case("advanced initialization uses the shared dropdown grid", loading, listOf(
                "R.string.model_advanced_init", "R.string.model_threads_auto", "BATCH_OPTIONS.filter { it <= contextTokens }",
                "BATCH_OPTIONS.filter { it <= batch }", "draft.canSelectCacheV(it)", "draft = draft.withFlashAttention(it)",
                "GPU_LAYER_OPTIONS", "R.string.model_gpu_cpu_only", "R.string.model_gpu_all",
                "R.string.model_init_experimental_hint"), listOf("ModelNumericField(")),
            Case("delete lives in the top bar", detail.substringAfter("actions = {").substringBefore("bottomBar = {"), listOf(
                "if (entry.installed || entry.kind == null)", "onClick = onDelete", "R.string.llm_delete_button")),
        ).forEach { case ->
            case.required.forEach { assertTrue("${case.name}: missing $it", case.code.contains(it)) }
            case.forbidden.forEach { assertFalse("${case.name}: unexpected $it", case.code.contains(it)) }
        }
    }

    @Test
    fun `save persists the model configuration then applies it to the runtime before reporting success`() {
        val source = source("ModelManagementViewModel")
        val save = source.substringAfter("fun save(entry: LocalModelEntry").substringBefore("fun delete(entry: LocalModelEntry")
        val persist = save.indexOf("catalog.updateConfig(entry.id, config.normalized())")
        val apply = save.indexOf("holder.applyManagedConfiguration(entry.id)")
        val refresh = save.indexOf("refreshModels()")
        val success = save.indexOf("onSaved()")
        listOf("persist before apply" to (persist >= 0 && apply > persist),
            "apply before refresh" to (refresh > apply), "refresh before success" to (success > refresh))
            .forEach { (name, valid) -> assertTrue(name, valid) }
    }

    private fun source(name: String): String = sequenceOf(
        File("src/main/java/com/gameocr/app/ui/$name.kt"),
        File("app/src/main/java/com/gameocr/app/ui/$name.kt"),
    ).first { it.isFile }.readText()
}
