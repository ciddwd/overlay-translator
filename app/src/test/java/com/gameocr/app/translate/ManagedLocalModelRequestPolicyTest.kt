package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.Settings
import com.gameocr.app.llm.LocalModelConfig
import com.gameocr.app.llm.LocalModelLoadMode
import com.gameocr.app.llm.LlmModelKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLocalModelRequestPolicyTest {
    @Test
    fun instructionsPreserveTemplatesAndSource_tableDriven() {
        val source = "こんにちは\n{name}: 42"
        val baseline = Settings(sourceLang = "ja", targetLang = "en")
        val templates = listOf(
            GenericLocalPromptPolicy.build(source, baseline),
            IndexTranslatePromptPolicy.build(source, baseline.targetLang, baseline.sourceLang),
        )
        for (template in templates) {
            for (instruction in listOf("", "  ", "Use plain English.", "Keep names.\nUse concise sentences.")) {
                val settings = baseline.copy(runtimeLocalModelConfig = LocalModelConfig(translationPrompt = instruction))
                val actual = ManagedLocalModelRequestPolicy.withInstructions(template, settings)
                if (instruction.isBlank()) {
                    assertEquals(template, actual)
                } else {
                    assertEquals("Additional translation instructions:\n${instruction.trim()}\n\n$template", actual)
                    assertTrue(actual.contains(source))
                    assertEquals(1, Regex("Additional translation instructions:").findAll(actual).count())
                }
                assertFalse(actual.contains("<|im_start|>"))
            }
        }
    }

    @Test
    fun variablesApplyOnlyToInstructionsNotSource_tableDriven() {
        val source = "Literal {source} and {target}; do not change {text}."
        for ((from, expected) in listOf("ja" to "日语", "ko" to "韩语", "auto" to "automatically detected source language")) {
            val settings = Settings(
                sourceLang = from, targetLang = "en",
                runtimeLocalModelConfig = LocalModelConfig(translationPrompt = "Translate {source} into {target}. Keep {text} literal."),
            )
            val template = GenericLocalPromptPolicy.build(source, settings)
            val actual = ManagedLocalModelRequestPolicy.withInstructions(template, settings)
            assertTrue(from, actual.startsWith("Additional translation instructions:\nTranslate $expected into 英语. Keep {text} literal."))
            assertTrue(from, actual.endsWith(source))
            assertTrue(from, actual.endsWith(template))
        }
    }

    @Test
    fun modelAndInferenceChangesCannotReuseOutput_tableDriven() {
        val config = LocalModelConfig(translationPrompt = "Keep names.")
        val baseline = Settings(
            sourceLang = "ja", targetLang = "en", localModelId = "imported-a",
            runtimeLocalModelConfig = config,
        )
        val baselineKey = cacheKey(baseline)
        val cases = listOf(
            "another imported weight" to baseline.copy(localModelId = "imported-b"),
            "builtin weight" to baseline.copy(localModelId = null),
            "instructions" to baseline.copy(runtimeLocalModelConfig = config.copy(translationPrompt = "Use informal speech.")),
            "physical context" to baseline.copy(localLlmContextSize = 4096),
            "output limit" to baseline.copy(localLlmMaxNewTokens = 512),
            "configured context" to baseline.copy(runtimeLocalModelConfig = config.copy(contextTokens = 4096)),
            "configured output" to baseline.copy(runtimeLocalModelConfig = config.copy(maxNewTokens = 512)),
            "temperature" to baseline.copy(runtimeLocalModelConfig = config.copy(temperature = 0.4f)),
            "top P" to baseline.copy(runtimeLocalModelConfig = config.copy(topP = 0.7f)),
            "top K" to baseline.copy(runtimeLocalModelConfig = config.copy(topK = 30)),
            "repeat penalty" to baseline.copy(runtimeLocalModelConfig = config.copy(repeatPenalty = 1.2f)),
            "source language" to baseline.copy(sourceLang = "ko"),
            "target language" to baseline.copy(targetLang = "zh-CN"),
        )
        cases.forEach { (name, settings) -> assertNotEquals(name, baselineKey, cacheKey(settings)) }
        assertEquals(baselineKey, cacheKey(baseline.copy()))
        assertEquals(baselineKey, cacheKey(baseline.copy(runtimeLocalModelConfig = config.copy(translationPrompt = "  Keep names.  "))))
    }

    @Test
    fun speedAndMemorySettingsKeepCachedOutput_tableDriven() {
        val config = LocalModelConfig(translationPrompt = "Keep names.")
        val baseline = Settings(sourceLang = "ja", targetLang = "en", localModelId = "imported-a",
            runtimeLocalModelConfig = config)
        listOf(
            config.copy(generationThreads = 2), config.copy(promptThreads = 3), config.copy(batchTokens = 256),
            config.copy(microBatchTokens = 128), config.copy(loadMode = LocalModelLoadMode.NONE), config.copy(loadMode = LocalModelLoadMode.DIRECT_IO),
            config.copy(gpuLayers = 8), config.copy(idleUnloadMinutes = 0), config.copy(flashAttention = "on"),
        ).forEach { changed ->
            assertEquals(changed.toString(), cacheKey(baseline), cacheKey(baseline.copy(runtimeLocalModelConfig = changed)))
        }
        assertNotEquals(cacheKey(baseline),
            cacheKey(baseline.copy(runtimeLocalModelConfig = config.copy(cacheTypeK = "q8_0"))))
    }

    @Test
    fun recoveryRetainsModelInstructionButOmitsHistory() {
        val settings = Settings(
            targetLang = "en", localModelId = "imported-a",
            runtimeLocalModelConfig = LocalModelConfig(translationPrompt = "Use formal speech."),
            runtimeTranslationPromptContext = RuntimeTranslationPromptContext(
                previousFrame = listOf(RuntimeDialogueTurn("昨日", "yesterday")),
            ),
        )
        val recoverySettings = settings.copy(runtimeTranslationPromptContext = RuntimeTranslationPromptContext())
        val recovered = ManagedLocalModelRequestPolicy.withInstructions(
            GenericLocalPromptPolicy.build("猫", recoverySettings), recoverySettings,
        )
        assertTrue(recovered.contains("Use formal speech."))
        assertFalse(recovered.contains("yesterday"))
        assertEquals(settings.localModelId, recoverySettings.localModelId)
        assertEquals(settings.runtimeLocalModelConfig, recoverySettings.runtimeLocalModelConfig)
    }

    @Test
    fun everyTranslatorSessionUsesItsManagedRequestSnapshot_tableDriven() {
        val files = listOf("LocalLlamaTranslator.kt", "IndexTranslateTranslator.kt", "SakuraGalTranslator.kt")
        for (name in files) {
            val text = source("app/src/main/java/com/gameocr/app/translate/$name")
            assertFalse(name, text.contains("holder.withEngineSession("))
            assertTrue(name, text.contains("holder.withManagedEngineSession("))
            assertTrue(name, text.contains("managedRuntimePrompt("))
        }
    }

    private fun cacheKey(settings: Settings): String = LocalLlamaTranslationCacheKey.build(
        cache = TranslationCache(), source = "猫", modelKind = LlmModelKind.QWEN3_0_6B_Q8_0,
        sourceLang = settings.sourceLang, targetLang = settings.targetLang,
        maxNewTokens = settings.localLlmMaxNewTokens, systemPrompt = null,
        userPrompt = ManagedLocalModelRequestPolicy.cacheFingerprint(LlmModelKind.QWEN3_0_6B_Q8_0, settings, "translate 猫"),
    )

    @Test
    fun textVariableReplacesBuiltInInstructionAndKeepsContext_tableDriven() {
        val source = "猫が {target} と言った"
        val glossary = RuntimeTranslationPromptContext(
            glossary = listOf(com.gameocr.app.data.RuntimeGlossaryTerm("猫", "Cat")),
        )
        data class Case(val template: String, val context: RuntimeTranslationPromptContext, val expected: String)
        listOf(
            Case("Translate {source} to {target}:\n{text}", RuntimeTranslationPromptContext(),
                "Translate 日语 to 英语:\n$source"),
            Case("{source_lang} -> {target_lang}\n{text}", RuntimeTranslationPromptContext(),
                "日语 -> 英语\n$source"),
            Case("{text}", glossary,
                GenericLocalPromptPolicy.contextSection(source, Settings(runtimeTranslationPromptContext = glossary)) + source),
        ).forEach { case ->
            val settings = Settings(sourceLang = "ja", targetLang = "en", runtimeTranslationPromptContext = case.context,
                runtimeLocalModelConfig = LocalModelConfig(translationPrompt = case.template))
            val actual = ManagedLocalModelRequestPolicy.withInstructions(
                GenericLocalPromptPolicy.build(source, settings), settings, source)
            assertEquals(case.template, case.expected, actual)
            assertFalse(actual.contains("Additional translation instructions"))
        }
    }

    @Test
    fun specializedModels_receiveStoredInstructionsOnlyWhenUnlocked() {
        val text = source("app/src/main/java/com/gameocr/app/translate/LocalLlamaTranslator.kt")
        val body = text.substringAfter("protected open fun managedRuntimePrompt(").substringBefore("protected open fun runtimePrompt(")
        val unlockedBranch = body.substringAfter("settings.runtimeLocalModelConfig?.unlocked == true ->")
            .substringBefore("else ->")
        // Unlocked: extra requirements only. No source text, so {text} can never replace the official template.
        assertTrue(unlockedBranch.contains("ManagedLocalModelRequestPolicy.withInstructions(runtimePrompt(basePrompt, settings), settings)"))
        assertTrue(body.substringAfter("else ->").contains("runtimePrompt(basePrompt, settings)"))
        assertEquals(setOf(LlmModelKind.SAKURA_1_5B_Q4, LlmModelKind.HY_MT2_1_8B_Q4_K_M,
            LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M, LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M),
            LlmModelKind.entries.filter { it.specialized }.toSet())
    }

    @Test
    fun unlockedRequirementsAreAddedBeforeTheOfficialPromptWithoutReplacingIt() {
        val official = SakuraPromptPolicy.build("猫", RuntimeTranslationPromptContext())
        val settings = Settings(sourceLang = "ja", targetLang = "zh-CN",
            runtimeLocalModelConfig = LocalModelConfig(translationPrompt = "人名保留音译。{text}", unlocked = true))
        val actual = ManagedLocalModelRequestPolicy.withInstructions(official, settings)
        assertTrue(actual.endsWith(official))
        assertTrue("{text} stays literal without a source", actual.contains("人名保留音译。{text}"))
    }

    @Test
    fun officialTemplates_renderForEveryTranslationModel() {
        LlmModelKind.entries.filter { it.specialized }.forEach { kind ->
            val template = OfficialPromptTemplate.render(kind, "ja", "zh-CN", "{原文}")
            assertTrue(kind.name, template != null && template.contains("{原文}"))
        }
        assertEquals(null, OfficialPromptTemplate.render(LlmModelKind.QWEN3_0_6B_Q8_0, "ja", "zh-CN", "{原文}"))
        assertTrue(OfficialPromptTemplate.render(LlmModelKind.SAKURA_1_5B_Q4, "ja", "zh-CN", "{原文}")!!
            .startsWith(SakuraPromptPolicy.SYSTEM_PROMPT))
    }

    private fun source(path: String): String = listOf(File("../$path"), File(path))
        .firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
