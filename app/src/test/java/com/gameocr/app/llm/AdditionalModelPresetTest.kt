package com.gameocr.app.llm

import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.ui.ModelTrialPolicy
import com.gameocr.app.ui.modelManagementSelectionSettings
import com.gameocr.app.ui.selectedLocalModelId
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdditionalModelPresetTest {
    private val models = LlmModelKind.entries.drop(4)

    @Test
    fun removedPresetsAreAbsentAndOldIdsAreHandledSafely_tableDriven() {
        listOf(
            "LFM2_5_2_6B_Q4_K_M" to "LFM2.5-2.6B",
            "LFM2_5_1_2B_JP_202606_Q4_K_M" to "LFM2.5-1.2B-JP-202606",
            "LFM2_5_1_2B_THINKING_Q4_K_M" to "LFM2.5-1.2B-Thinking",
            "LFM2_5_8B_A1B_Q4_K_M" to "LFM2.5-8B-A1B",
            "GEMMA3_1B_Q4_K_M" to "Gemma-3-1B-it",
            "GRANITE4_1_3B_Q4_K_M" to "Granite-4.1-3B",
            "GRANITE4_2_3B_Q4_K_M" to "Granite-4.2-3B",
        ).forEach { (kind, name) ->
            assertFalse(name, LlmModelKind.entries.any { it.displayName == name })
            assertNull(kind, LlmModelKind.fromBuiltInId("builtin:$kind"))
        }
    }

    @Test
    fun approvedModelsHaveDistinctStableIdentitiesAndCorrectQuantization_tableDriven() {
        assertEquals(listOf("LFM2.5-1.2B-Instruct", "Gemma-4-E2B-it", "Gemma-4-E4B-it",
            "Gemma-3-4B-it", "TranslateGemma-4B-it"),
            models.map { it.displayName })
        assertEquals(LlmModelKind.entries.size, LlmModelKind.entries.map { it.fileName }.distinct().size)
        for (kind in models) {
            val id = LocalModelCatalog.builtInId(kind)
            assertEquals(kind, LlmModelKind.fromBuiltInId(id))
            assertTrue(kind.name, kind.usesGgufTemplate)
            assertTrue(kind.name, kind.managedViaGeneric)
            assertEquals(kind == LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M, kind.specialized)
            assertEquals(if (kind.name.startsWith("GEMMA4")) "Q4_0" else "Q4_K_M", kind.quantization)
            assertTrue(kind.name, kind.downloadUrl.endsWith("/" + kind.fileName))
            assertTrue(kind.name, kind.approxSizeMb > 0)
        }
        listOf(null, "", "imported:x", "builtin:UNKNOWN", "GEMMA3_1B_Q4_K_M").forEach {
            assertNull(it, LlmModelKind.fromBuiltInId(it))
        }
    }

    @Test
    fun selectionAndTrialKeepEachPresetIdInsteadOfFallingBackToQwen_tableDriven() {
        for (kind in models) {
            val id = LocalModelCatalog.builtInId(kind)
            val base = Settings(sourceLang = "ko", targetLang = "en")
            val selected = modelManagementSelectionSettings(base, kind, id)
            assertEquals(kind.name, TranslatorEngine.LOCAL_QWEN3, selected.translatorEngine)
            assertEquals(id, selected.localModelId)
            assertEquals(id, selectedLocalModelId(selected))
            assertEquals("ko" to "en", selected.sourceLang to selected.targetLang)
            val entry = LocalModelEntry(id, kind.displayName, kind, null, 0, "", kind.quantization, "", LocalModelConfig())
            val trial = ModelTrialPolicy.settings(base, entry, usesSample = false)
            assertEquals(id, trial.localModelId)
            assertEquals(id, selectedLocalModelId(trial))
        }
    }

    @Test
    fun allPresetsUseOneSharedDownloadSourcePolicy_tableDriven() {
        for (kind in LlmModelKind.entries) {
            assertEquals(listOf(kind.downloadUrl), LlmModelInstaller.mirrorsFor(kind, LlmMirrorChoice.HF_OFFICIAL, null))
            assertEquals(listOf(kind.downloadUrl.replace("https://huggingface.co/", "https://hf-mirror.com/")),
                LlmModelInstaller.mirrorsFor(kind, LlmMirrorChoice.HF_MIRROR, null))
            for (base in listOf("https://example.org/models", "https://example.org/models/", " https://example.org/models/ ")) {
                assertEquals(listOf("https://example.org/models/${kind.fileName}"),
                    LlmModelInstaller.mirrorsFor(kind, LlmMirrorChoice.CUSTOM, base))
            }
            for (base in listOf(null, "", "   ")) {
                assertTrue(LlmModelInstaller.mirrorsFor(kind, LlmMirrorChoice.CUSTOM, base).isEmpty())
            }
        }
    }

    @Test
    fun samplerProfilesAreIsolatedAndTemplateProfileIsReset_tableDriven() {
        val neutral = LocalLlmSamplingPolicy.IMPORTED_DEFAULTS
        val expected = models.associateWith { kind -> when (kind) {
            LlmModelKind.GEMMA4_E2B_Q4_0, LlmModelKind.GEMMA4_E4B_Q4_0 -> LocalLlmSamplingConfig(1f, 0.95f, 64, 1f, 0f, 0f)
            LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M -> LocalLlmSamplingConfig(0f, 1f, 0, 1f, 0f, 0f)
            else -> neutral
        } }
        for ((kind, config) in expected) {
            assertEquals(kind.name, config, LocalLlmSamplingPolicy.forModel(kind))
            assertEquals(kind.name, "0.0", LocalLlmSamplingPolicy.nativeEnvironment(kind)["GAMEOCR_SAMPLER_PRESENCE_PENALTY"])
            assertEquals(kind.name, if (kind == LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M) "1" else "0",
                LocalLlmSamplingPolicy.nativeEnvironment(kind)["GAMEOCR_TRANSLATEGEMMA"])
            assertEquals(kind.name, neutral, LocalLlmSamplingPolicy.forModel(kind, imported = true))
        }
    }

    @Test
    fun presetIdentityAndTypedTemplateReachEveryNativeRequestPath_tableDriven() {
        fun source(path: String) = listOf(File(path), File("../$path")).first(File::isFile).readText()
        val paths = listOf(
            "app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt" to
                "modelManagementSelectionSettings(buildSnapshot(), entry.kind, entry.id)",
            "app/src/main/java/com/gameocr/app/llm/LlamaEngineHolder.kt" to "ensureLoaded(managed?.kind ?: kind",
            "app/src/main/java/com/gameocr/app/translate/IndexTranslateTranslator.kt" to "val modelKind = runtimeModelKind(settings)",
            "llama-android/src/main/cpp/llama_template_generation.inc" to "gameocr_common_chat_templates_apply(g_chat_templates.get(), inputs)",
            "llama-android/src/main/cpp/llama_thread_policy.cpp" to "inputs.force_pure_content = true",
            "llama-android/src/main/cpp/llama_thread_policy.cpp" to "gameocr_common_chat_templates_apply(tmpls, inputs).prompt",
        )
        paths.forEach { (path, required) -> assertTrue(path, source(path).contains(required)) }
    }
}
