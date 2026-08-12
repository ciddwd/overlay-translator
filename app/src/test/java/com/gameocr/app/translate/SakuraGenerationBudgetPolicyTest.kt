package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraGenerationBudgetPolicyTest {

    @Test
    fun translator_usesTokenizerBudgetAndBoundedRetryFallback_tableDriven() {
        val source = listOf(
            File("../app/src/main/java/com/gameocr/app/translate/SakuraGalTranslator.kt"),
            File("app/src/main/java/com/gameocr/app/translate/SakuraGalTranslator.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("SakuraGalTranslator.kt not found")

        data class Case(val name: String, val marker: String)
        listOf(
            Case("token count stays inside engine lifecycle session", "holder.withEngineSession(modelKind, systemPrompt)"),
            Case("source uses native tokenizer", "LlamaPromptMetrics.countTextTokens(it.joinedSource)"),
            Case("adaptive policy receives line count", "lineCount = group.sourceLines.size"),
            Case("effective budget reaches generation", "predictLength = group.generationBudget.effectiveMaxNewTokens"),
            Case("failed groups use bounded retry planning", "SakuraRetryPlanPolicy.structuralFailure("),
            Case("one context-preserving salvage stage remains", "stage = SakuraRetryStage.SALVAGE"),
            Case("unresolved items use independent recovery", "recoverIsolatedFailures("),
            Case("final recovery cannot retry recursively", "settings.copy(retryFailedTranslation = false)"),
            Case("Sakura bypasses generic runtime prompt", "override fun runtimePrompt(basePrompt: String, settings: Settings): String = basePrompt"),
            Case("quality validation runs before publishing", "val validation = SakuraOutputPolicy.validateGroup("),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }

    @Test
    fun decide_tableDriven_capsOnlyMultiLineGroupsAndKeepsSafeFallback() {
        data class Case(
            val name: String,
            val configuredMax: Int,
            val sourceTokens: Int,
            val lineCount: Int,
            val expectedMax: Int,
            val expectedAdaptive: Boolean,
        )

        listOf(
            Case("single line keeps full retry budget", 256, 20, 1, 256, false),
            Case("invalid zero lines keeps full budget", 256, 20, 0, 256, false),
            Case("short two-line group uses safety floor", 256, 20, 2, 64, true),
            Case("six-line group scales from tokenizer count", 256, 80, 6, 160, true),
            Case("large group remains capped by user setting", 256, 200, 6, 256, false),
            Case("small user limit is never increased", 48, 20, 2, 48, false),
            Case("negative tokenizer result is normalized", 256, -1, 2, 64, true),
            Case("overflow-sized input stays at configured cap", 256, Int.MAX_VALUE, 8, 256, false),
        ).forEach { case ->
            val actual = SakuraGenerationBudgetPolicy.decide(
                configuredMaxNewTokens = case.configuredMax,
                sourceTokens = case.sourceTokens,
                lineCount = case.lineCount,
            )

            assertEquals(case.name, case.sourceTokens.coerceAtLeast(0), actual.sourceTokens)
            assertEquals(case.name, case.configuredMax.coerceAtLeast(1), actual.configuredMaxNewTokens)
            assertEquals(case.name, case.expectedMax, actual.effectiveMaxNewTokens)
            assertEquals(case.name, case.expectedAdaptive, actual.adaptive)
        }
    }
}
