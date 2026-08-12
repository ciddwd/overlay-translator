package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMt2PromptWiringTest {

    @Test
    fun translator_usesOnlyTypedOfficialPromptContext() {
        val source = source("app/src/main/java/com/gameocr/app/translate/HyMt2Translator.kt")

        data class Case(val name: String, val marker: String)
        listOf(
            Case("official policy builds prompt", "HyMt2PromptPolicy.build("),
            Case("typed runtime context is passed", "context = settings.runtimeTranslationPromptContext"),
            Case("Hy-MT2 has no system prompt", "override val systemPrompt: String? = null"),
            Case("generic context wrapping is bypassed", "override fun runtimePrompt(basePrompt: String, settings: Settings): String = basePrompt"),
            Case("stream output waits for validation", "bufferGeneratedOutputUntilValidated: Boolean = true"),
            Case("generated output uses Hy-MT2 policy", "HyMt2OutputPolicy.inspect("),
            Case("harmless single prefix is normalized", "normalizeHarmlessSingleItemPrefix("),
            Case("recovery removes background only", "HyMt2PromptPolicy.buildWithoutBackground("),
            Case("context requests receive a dynamic native line cap", "HyMt2GenerationBoundaryPolicy.maxOutputLines("),
            Case("native line cap is marked invalid", "markNativeBatchLineLimitAsInvalid: Boolean = true"),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }

        assertFalse("generic JSON context must not be embedded by Hy-MT2", source.contains("runtimeTranslationContext +"))
        assertFalse("model name must not select a remote protocol", source.contains("settings.model"))
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
