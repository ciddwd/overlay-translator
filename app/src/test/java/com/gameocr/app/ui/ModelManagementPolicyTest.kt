package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.llm.LocalModelCatalog
import com.gameocr.app.llm.LocalModelConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagementPolicyTest {
    @Test
    fun `sampling slider values round trip with valid steps and parameter boundaries`() {
        data class Case(val parameter: String, val raw: Float, val range: ClosedFloatingPointRange<Float>,
            val step: Float, val expected: String)
        listOf(
            Case("temperature", 0.7f, 0f..2f, 0.01f, "0.7"),
            Case("temperature", 0.734f, 0f..2f, 0.01f, "0.73"),
            Case("temperature", -0.1f, 0f..2f, 0.01f, "0.0"),
            Case("temperature", 2.1f, 0f..2f, 0.01f, "2.0"),
            Case("top_p", 0f, 0.01f..1f, 0.01f, "0.01"),
            Case("top_p", 0.8f, 0.01f..1f, 0.01f, "0.8"),
            Case("top_p", 1.1f, 0.01f..1f, 0.01f, "1.0"),
            Case("repeat_penalty", 0.4f, 0.5f..2f, 0.01f, "0.5"),
            Case("repeat_penalty", 1.05f, 0.5f..2f, 0.01f, "1.05"),
            Case("repeat_penalty", 2.1f, 0.5f..2f, 0.01f, "2.0"),
        ).forEach { case ->
            val value = modelSliderValue(case.raw, case.range, case.step)
            val encoded = modelSamplingValueLabel(value)
            assertEquals(case.toString(), case.expected, encoded)
            val base = ModelConfigDraft.from(LocalModelConfig())
            val draft = when (case.parameter) {
                "temperature" -> base.copy(temperature = encoded)
                "top_p" -> base.copy(topP = encoded)
                else -> base.copy(repeatPenalty = encoded)
            }
            val parsed = draft.parse()
            assertNotNull(case.toString(), parsed)
            assertEquals(draft, ModelConfigDraft.restore(draft.serialize()))
        }
    }

    @Test
    fun `official restore clears custom overrides and preserves other settings`() {
        val base = ModelConfigDraft.from(LocalModelConfig())
        listOf(
            base,
            base.copy(unlocked = true, prompt = "extra", temperature = "0.9", topP = "0.8", topK = "20", repeatPenalty = "1.2"),
            base.copy(unlocked = true, prompt = "extra", context = "invalid", output = "512", gpuLayers = "0"),
        ).forEach { draft ->
            val restored = draft.restoreOfficialOverrides()
            assertFalse(restored.unlocked)
            listOf(restored.prompt, restored.temperature, restored.topP, restored.topK, restored.repeatPenalty)
                .forEach { assertEquals("", it) }
            assertEquals(draft, restored.copy(unlocked = draft.unlocked, prompt = draft.prompt,
                temperature = draft.temperature, topP = draft.topP, topK = draft.topK, repeatPenalty = draft.repeatPenalty))
            assertEquals(restored, restored.restoreOfficialOverrides())
        }
    }

    @Test
    fun `Sakura selection preserves languages for the shared settings dialog`() {
        listOf("ja" to "zh-CN", "be" to "zh-CN", "auto" to "zh-CN", "ja" to "en")
            .forEach { (source, target) ->
                val selected = modelManagementSelectionSettings(
                    Settings(sourceLang = source, targetLang = target, translatorEngine = TranslatorEngine.GOOGLE),
                    LlmModelKind.SAKURA_1_5B_Q4,
                    "sakura",
                )
                assertEquals(TranslatorEngine.LOCAL_SAKURA, selected.translatorEngine)
                assertEquals(source, selected.sourceLang)
                assertEquals(target, selected.targetLang)
                assertNull(selected.localModelId)
            }
    }

    @Test
    fun `each built in and imported model has the right selection identity`() {
        val cases = listOf(
            LlmModelKind.SAKURA_1_5B_Q4 to TranslatorEngine.LOCAL_SAKURA,
            LlmModelKind.HY_MT2_1_8B_Q4_K_M to TranslatorEngine.LOCAL_HY_MT2,
            LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M to TranslatorEngine.LOCAL_INDEX_TRANSLATE,
            LlmModelKind.QWEN3_0_6B_Q8_0 to TranslatorEngine.LOCAL_QWEN3,
        )
        for ((kind, engine) in cases) {
            assertEquals(kind.name, engine, localModelTranslatorEngine(kind))
            assertEquals(kind.name, LocalModelCatalog.builtInId(kind), selectedLocalModelId(Settings(translatorEngine = engine)))
        }
        val imported = "imported:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertEquals(imported, selectedLocalModelId(Settings(translatorEngine = TranslatorEngine.LOCAL_QWEN3, localModelId = imported)))
        assertEquals(TranslatorEngine.LOCAL_QWEN3, localModelTranslatorEngine(null))
        assertNull(selectedLocalModelId(Settings(translatorEngine = TranslatorEngine.OPENAI, localModelId = imported)))
        assertEquals(LocalModelCatalog.builtInId(LlmModelKind.HY_MT2_1_8B_Q4_K_M),
            selectedLocalModelId(Settings(translatorEngine = TranslatorEngine.LOCAL_HY_MT2, localModelId = imported)))
    }

    @Test
    fun `per model parameter drafts handle defaults boundaries and malformed input`() {
        val base = ModelConfigDraft.from(LocalModelConfig())
        val valid = listOf(
            base,
            base.copy(context = "512", output = "16", temperature = "0", topP = "0.01", topK = "0", repeatPenalty = "0.5"),
            base.copy(context = "4096", output = "4088", temperature = "2", topP = "1", topK = "200", repeatPenalty = "2"),
            base.copy(temperature = " ", topP = "", topK = "", repeatPenalty = ""),
        )
        for (draft in valid) assertNotNull(draft.toString(), draft.parse())
        val invalid = listOf(
            base.copy(context = ""), base.copy(context = "511"), base.copy(context = "4097"),
            base.copy(output = "15"), base.copy(output = "2048"), base.copy(output = "3000"),
            base.copy(temperature = "NaN"), base.copy(temperature = "Infinity"), base.copy(temperature = "2.1"),
            base.copy(topP = "0"), base.copy(topP = "1.01"), base.copy(topK = "-1"), base.copy(topK = "1.2"),
            base.copy(topK = "201"), base.copy(repeatPenalty = "0.49"), base.copy(repeatPenalty = "2.01"),
            base.copy(temperature = "word"), base.copy(context = "1e3"),
        )
        for (draft in invalid) assertNull(draft.toString(), draft.parse())
    }

    @Test
    fun `draft round trip keeps all settings and blank optional fields are defaults`() {
        val config = LocalModelConfig(4096, 1024, "Keep names {source} {target}", 0.7f, 0.8f, 20, 1.1f,
            generationThreads = 3, promptThreads = 8, batchTokens = 1024, microBatchTokens = 256,
            useMmap = false, useMlock = true, cacheTypeK = "q8_0", cacheTypeV = "q4_0",
            flashAttention = "on", gpuLayers = -1, idleUnloadMinutes = 0)
        val draft = ModelConfigDraft.from(config)
        assertEquals(config, draft.parse())
        assertEquals(draft, ModelConfigDraft.restore(draft.serialize()))
        assertEquals(LocalModelConfig(), ModelConfigDraft.from(LocalModelConfig()).parse())
    }

    @Test
    fun `initialization drafts cover relational limits automatic threads and native options`() {
        val base = ModelConfigDraft.from(LocalModelConfig())
        data class Case(val name: String, val draft: ModelConfigDraft, val valid: Boolean)
        listOf(
            Case("defaults", base, true),
            Case("minimum batches and thread counts", base.copy(context = "512", batchTokens = "32", microBatchTokens = "32",
                generationThreads = "1", promptThreads = "1", gpuLayers = "-1", idleUnloadMinutes = "0"), true),
            Case("maximum batches and thread counts", base.copy(context = "4096", batchTokens = "2048", microBatchTokens = "2048",
                generationThreads = "64", promptThreads = "64", gpuLayers = "999", idleUnloadMinutes = "60"), true),
            Case("automatic thread counts", base.copy(generationThreads = " ", promptThreads = ""), true),
            Case("batch larger than context", base.copy(context = "512", batchTokens = "1024"), false),
            Case("micro batch larger than batch", base.copy(batchTokens = "256", microBatchTokens = "512"), false),
            Case("batch below minimum", base.copy(batchTokens = "31"), false),
            Case("micro batch below minimum", base.copy(microBatchTokens = "31"), false),
            Case("batch above maximum", base.copy(context = "4096", batchTokens = "2049"), false),
            Case("micro batch above maximum", base.copy(context = "4096", batchTokens = "2048", microBatchTokens = "2049"), false),
            Case("zero generation threads", base.copy(generationThreads = "0"), false),
            Case("zero prompt threads", base.copy(promptThreads = "0"), false),
            Case("too many generation threads", base.copy(generationThreads = "65"), false),
            Case("too many prompt threads", base.copy(promptThreads = "65"), false),
            Case("fractional threads", base.copy(generationThreads = "1.5"), false),
            Case("malformed prompt threads", base.copy(promptThreads = "auto"), false),
            Case("unsupported key cache type", base.copy(cacheTypeK = "unknown"), false),
            Case("unsupported value cache type", base.copy(cacheTypeV = "unknown"), false),
            Case("unsupported flash mode", base.copy(flashAttention = "unknown"), false),
            Case("negative GPU layers below automatic", base.copy(gpuLayers = "-2"), false),
            Case("GPU layer limit", base.copy(gpuLayers = "1000"), false),
            Case("malformed GPU layers", base.copy(gpuLayers = "1e2"), false),
            Case("negative idle timeout", base.copy(idleUnloadMinutes = "-1"), false),
            Case("idle timeout limit", base.copy(idleUnloadMinutes = "61"), false),
            Case("fractional idle timeout", base.copy(idleUnloadMinutes = "1.5"), false),
        ).forEach { case -> assertEquals(case.name, case.valid, case.draft.parse() != null) }
        ModelConfigDraft.CACHE_TYPES.forEach { cache ->
            assertNotNull(cache, base.copy(cacheTypeK = cache, cacheTypeV = cache).parse())
        }
        ModelConfigDraft.FLASH_ATTENTION_OPTIONS.forEach { mode ->
            assertNotNull(mode, base.copy(flashAttention = mode).parse())
        }
        ModelConfigDraft.FLASH_ATTENTION_OPTIONS.forEach { mode ->
            ModelConfigDraft.CACHE_TYPES.forEach { cache ->
                assertEquals("$mode / $cache", mode != "off" || cache == "f16",
                    base.copy(flashAttention = mode, cacheTypeV = cache).parse() != null)
            }
        }
        assertNull(base.copy(generationThreads = " ", promptThreads = "").parse()?.generationThreads)
        assertNull(base.copy(generationThreads = " ", promptThreads = "").parse()?.promptThreads)
    }

    @Test
    fun `saved draft restores every initialization field and retains invalid edits safely`() {
        val base = ModelConfigDraft.from(LocalModelConfig())
        val drafts = listOf(
            base,
            base.copy(context = "broken", generationThreads = "0", promptThreads = " ", useMmap = false,
                useMlock = true, cacheTypeK = "q8_0", cacheTypeV = "q4_0", flashAttention = "off", gpuLayers = "", idleUnloadMinutes = "60"),
        )
        drafts.forEach { assertEquals(it.toString(), it, ModelConfigDraft.restore(it.serialize())) }
        listOf(emptyList(), base.serialize().dropLast(3), base.serialize() + "extra",
            base.serialize().toMutableList().apply { this[11] = "yes" },
            base.serialize().toMutableList().apply { this[12] = "1" },
            base.serialize().toMutableList().apply { this[18] = "no" },
            base.serialize().toMutableList().apply { this[19] = "maybe" })
            .forEach { assertNull(it.toString(), ModelConfigDraft.restore(it)) }
        // State saved before the context/unlock options existed restores with their defaults.
        val custom = base.copy(includeContext = false, unlocked = true)
        assertEquals(base, ModelConfigDraft.restore(custom.serialize().dropLast(2)))
        assertEquals(base.copy(includeContext = false), ModelConfigDraft.restore(custom.serialize().dropLast(1)))
        assertEquals(custom, ModelConfigDraft.restore(custom.serialize()))
    }

    @Test
    fun `GPU and flash editors preserve valid explicit native configurations`() {
        val base = ModelConfigDraft.from(LocalModelConfig())
        assertFalse(base.gpuEnabled())
        assertEquals("-1", base.withGpuEnabled(true).gpuLayers)
        listOf("-1", "1", "999", "").forEach { raw ->
            val draft = base.copy(gpuLayers = raw)
            assertTrue(raw, draft.gpuEnabled())
            assertEquals(raw, "0", draft.withGpuEnabled(false).gpuLayers)
        }
        ModelConfigDraft.FLASH_ATTENTION_OPTIONS.forEach { mode ->
            ModelConfigDraft.CACHE_TYPES.forEach { cache ->
                val draft = base.copy(cacheTypeV = cache).withFlashAttention(mode)
                assertEquals("$mode / $cache", if (mode == "off") "f16" else cache, draft.cacheTypeV)
                assertNotNull("$mode / $cache", draft.parse())
                ModelConfigDraft.CACHE_TYPES.forEach { candidate ->
                    assertEquals("$mode / $candidate", mode != "off" || candidate == "f16", draft.canSelectCacheV(candidate))
                }
                assertFalse(draft.canSelectCacheV("unknown"))
            }
        }
        listOf(true, false).forEach { mmap ->
            listOf(true, false).forEach { mlock ->
                val draft = base.copy(useMmap = mmap, useMlock = mlock)
                val config = draft.parse()
                assertNotNull(config)
                assertEquals(mmap, config?.useMmap)
                assertEquals(mlock, config?.useMlock)
                assertEquals(draft, ModelConfigDraft.restore(draft.serialize()))
            }
        }
    }

    @Test
    fun `reuse numeric parameter labels without a misleading zero default`() {
        listOf("Temperature: 0.0" to "Temperature", "温度：0.0" to "温度", "top_p: 0.0" to "top_p")
            .forEach { (input, expected) -> assertEquals(expected, modelParameterLabel(input)) }
    }

    @Test
    fun `model page hides internal templates and keeps translation prompt and explicit compact actions`() {
        val source = sequenceOf(
            File("src/main/java/com/gameocr/app/ui/ModelManagementScreen.kt"),
            File("app/src/main/java/com/gameocr/app/ui/ModelManagementScreen.kt"),
        ).first { it.isFile }.readText()
        listOf("entry.chatTemplate", "showTemplate", "Icons.Default.Settings")
            .forEach { hidden -> assertFalse(hidden, source.contains(hidden)) }
        assertTrue(source.contains("R.string.model_manager_translation_prompt"))
        assertTrue(source.contains("value = draft.prompt"))
        val trial = source.substringAfter("trial?.let { state ->").substringBefore("importSheet?.let { state ->")
        val guidance = trial.indexOf("R.string.model_trial_hint")
        assertTrue(guidance >= 0 && guidance < trial.indexOf("OutlinedTextField("))
        val card = source.substringAfter("private fun ManagedModelCard(").substringBefore("private fun ModelTabs(")
        listOf(
            "Button(onClick = onUse, enabled = !busy && useEnabled", "OutlinedButton(onClick = onDownload",
            "OutlinedButton(onClick = onCancel", "R.string.model_in_use",
            "IconButton(onClick = onDetails, enabled = !busy)", "contentDescription = stringResource(R.string.main_settings)",
            "onClick = onTrySample", "enabled = !busy && useEnabled && entry.installed",
            "Icons.Outlined.Science", "R.string.model_trial_title",
            "Text(size, style = MaterialTheme.typography.labelSmall", "ModelDownloadWorker.KEY_DOWNLOADED",
        ).forEach { assertTrue(it, card.contains(it)) }
        listOf("entry.quantization", "entry.architecture", "if (entry.installed) IconButton")
            .forEach { assertFalse(it, card.contains(it)) }
        val heading = card.substringBefore("horizontalArrangement = Arrangement.End")
        assertTrue(heading.contains("Text(modelManagerDisplayName(entry)"))
        assertTrue(heading.contains("Text(size, style = MaterialTheme.typography.labelSmall"))
        assertTrue(heading.contains("verticalAlignment = Alignment.Top"))
        assertFalse(heading.contains("onClick = onUse"))
        assertFalse(heading.contains("onClick = onTrySample"))
        assertTrue(card.contains("horizontalArrangement = Arrangement.End"))
        val actions = card.substringAfter("horizontalArrangement = Arrangement.End")
        assertTrue(actions.indexOf("Row(") < actions.indexOf("entry.installed && selected"))
        assertTrue(source.contains("onTrySample = { viewModel.trySample(entry, entry.config) }"))
        val list = source.substringAfter("private fun ModelManagementScreen(").substringBefore("importSheet?.let { state ->")
        listOf("models.filter { it.specialized }", "models.filterNot { it.specialized }",
            "R.string.model_group_specialized_hint", "R.string.model_group_general_hint",
            "entries.sortedByDescending { it.installed }", "LoadedModelBanner(loaded, busy = busy, onRelease = viewModel::unload)",
            "bottomBar = {", "R.string.model_import_button")
            .forEach { assertTrue(it, list.contains(it)) }
        listOf("R.string.model_manager_downloaded", "R.string.model_manager_available")
            .forEach { assertFalse(it, list.contains(it)) }
        // Import opens the picker directly; explanation, progress and checks live in the import sheet.
        assertTrue(list.contains("onClick = { importLauncher.launch(arrayOf(\"*/*\")) }"))
        assertTrue(source.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(source.contains("uri?.let(viewModel::importModel)"))
    }

    @Test
    fun `model shortcuts and manager keep the outer draft and save selection only through explicit actions`() {
        fun source(name: String): String = sequenceOf(
            File("src/main/java/com/gameocr/app/ui/$name.kt"),
            File("app/src/main/java/com/gameocr/app/ui/$name.kt"),
        ).first { it.isFile }.readText()
        val settings = source("SettingsScreen")
        val settingsViewModel = source("SettingsViewModel")
        val manager = source("ModelManagementScreen")
        val modelViewModel = source("ModelManagementViewModel")
        val back = settings.substringAfter("if (showModelManagement) {")
            .substringAfter("onBack =").substringBefore("onModelSelected =")
        val selection = settings.substringAfter("if (showModelManagement) {")
            .substringAfter("onModelSelected =").substringBefore("return")
        val shortcuts = settings.substringAfter("quickLocalModels.forEach { entry ->")
            .substringBefore("settings_translator_group_cloud_llm")
        val importSheet = manager.substringAfter("importSheet?.let { state ->")
            .substringBefore("models.firstOrNull { it.id == pendingDeleteId }")
        val discard = modelViewModel.substringAfter("fun discardImport()").substringBefore("fun download(")
        data class Case(val name: String, val code: String, val required: List<String>, val forbidden: List<String> = emptyList())
        listOf(
            Case("independent local ID draft", settings,
                listOf("var localModelId by remember { mutableStateOf<String?>(null) }")),
            Case("preset and imported settings load the ID", settings.substringAfter("fun applyPresetSettingsToUi(s: Settings)").take(3_000),
                listOf("localModelId = s.localModelId")),
            Case("normal initial page load reads the ID", settings.substringAfter("val s = viewModel.loadForScreen(context)").take(6_000),
                listOf("localModelId = s.localModelId")),
            Case("snapshot uses draft ID, gated to generic local models", settings.substringAfter("fun buildSnapshot(): Settings").take(2_000),
                listOf("localModelId = localModelId.takeIf { translatorEngine == TranslatorEngine.LOCAL_QWEN3 }")),
            Case("save passes the local ID draft", settings.substringAfter("val doSave: suspend () -> Unit = {").take(14_000),
                listOf("viewModel.save(", "localModelId = localModelId,")),
            Case("save persists the selected ID", settingsViewModel,
                listOf("localModelId: String? = null", "localModelId = localModelId.takeIf { translatorEngine == TranslatorEngine.LOCAL_QWEN3 }")),
            Case("model shortcuts are names followed by an explicitly named manager chip", shortcuts,
                listOf("FilterChip(", "Text(modelManagerDisplayName(entry))", "MoreOptionsChip(",
                    "label = stringResource(R.string.model_manager_title)", "enabled = localLlmDeviceCapable",
                    "onClick = { showModelManagement = true }")),
            Case("parameters shortcut opens the selected model's own page", shortcuts,
                listOf("isLocalLlmEngine(translatorEngine) && localLlmDeviceCapable",
                    "it.id == selectedLocalModelId(buildSnapshot())",
                    "modelManagementDetailsId = parameterModel.id", "R.string.settings_local_model_parameters"),
                listOf("Slider(")),
            Case("manager closes straight back from a directly opened model page", manager,
                listOf("var detailsId by rememberSaveable { mutableStateOf(initialDetailsId) }",
                    "onBack = { if (initialDetailsId != null) onBack() else detailsId = null }")),
            Case("return only closes the manager", back,
                listOf("showModelManagement = false"), listOf("viewModel.load", "translatorEngine", "localModelId")),
            Case("explicit selection updates only engine and ID", selection,
                listOf("localModelId = saved.localModelId", "initialSettings = initialSettings?.copy(",
                    "translatorEngine = saved.translatorEngine", "selectTranslatorEngine(saved.translatorEngine)"),
                listOf("applyPresetSettingsToUi(saved)")),
            Case("card Use forwards the explicit callback", manager,
                listOf("onModelSelected: (Settings) -> Unit = {}", "viewModel.select(entry, onModelSelected)")),
            Case("view model confirms persisted selection before callback", modelViewModel,
                listOf("val savedSettings = repository.get()", "selectedLocalModelId(savedSettings) == current.id", "onSelected(savedSettings)")),
            Case("import sheet finishes through explicit selection", importSheet,
                listOf("ModelImportSheet(", "onCancel = viewModel::discardImport",
                    "viewModel.finishImport(name, onModelSelected)", "it.entryId == state.entry.id")),
            Case("cancel stops a copy or removes only the model this import added", discard,
                listOf("ModelImportStage.COPYING, ModelImportStage.CHECKING -> cancelImport()",
                    "ModelImportStage.DONE -> state.entry?.let { entry -> delete(entry) {} }")),
        ).forEach { case ->
            case.required.forEach { marker -> assertTrue("${case.name}: missing $marker", case.code.contains(marker)) }
            case.forbidden.forEach { marker -> assertFalse("${case.name}: unexpected $marker", case.code.contains(marker)) }
        }
    }

    @Test
    fun `on device translation has one wrapping group with both shortcut families and both More actions`() {
        val source = sequenceOf(
            File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
            File("app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
        ).first { it.isFile }.readText()
        val engine = source.substringAfter("item(key = SectionKeys.TRANSLATE) {")
            .substringBefore("R.string.settings_translator_group_cloud_llm")
        val sharedRow = bracedBodyAt(engine, engine.indexOf("FlowRow("))
        val language = sharedRow.indexOf("quickMlKitSources.forEach")
        val languageMore = sharedRow.indexOf("showMlKitMoreLanguages = true")
        val models = sharedRow.indexOf("quickLocalModels.forEach")
        val modelMore = sharedRow.indexOf("label = stringResource(R.string.model_manager_title)")
        data class Case(val name: String, val valid: Boolean)
        listOf(
            Case("one existing on-device heading", Regex("R\\.string\\.settings_translator_group_on_device\\b").findAll(engine).count() == 1),
            Case("no separate local-LLM heading", !engine.contains("R.string.settings_translator_group_local_llm")),
            Case("one common FlowRow before cloud engines", Regex("\\bFlowRow\\s*\\(").findAll(engine).count() == 1),
            Case("language shortcuts stay inside the common row", language >= 0),
            Case("model shortcuts stay inside the same row", models > language && language >= 0),
            Case("language More keeps its own language-picker action", languageMore > language && languageMore < models),
            Case("model More keeps its own manager action", modelMore > models && models >= 0),
            Case("named shortcut chips remain in the common row", sharedRow.contains("MoreOptionsChip(")),
            Case("wrapping is width-driven, not a forced item count", !engine.contains("maxItemsInEachRow")),
        ).forEach { case -> assertTrue(case.name, case.valid) }
    }

    /** Ignore quoted strings when checking which callbacks belong to a layout's actual braces. */
    private fun bracedBodyAt(source: String, start: Int): String {
        require(start >= 0) { "Expected a FlowRow" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "Expected a FlowRow body" }
        var depth = 1
        var quoted = false
        var escaped = false
        var index = open + 1
        while (index < source.length && depth > 0) {
            val ch = source[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        require(depth == 0) { "Unclosed FlowRow body" }
        return source.substring(open + 1, index - 1)
    }
}
