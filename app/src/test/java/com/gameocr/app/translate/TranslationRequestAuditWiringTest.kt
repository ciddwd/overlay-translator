package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRequestAuditWiringTest {

    @Test
    fun debugAudit_tableDriven_coversRemoteLlmTranslationRequests() {
        val audit = source("app/src/main/java/com/gameocr/app/translate/TranslationRequestAudit.kt")
        val openAi = source("app/src/main/java/com/gameocr/app/translate/OpenAiTranslator.kt")
        val anthropic = source("app/src/main/java/com/gameocr/app/translate/AnthropicTranslator.kt")

        data class Case(val name: String, val content: String, val marker: String)
        listOf(
            Case("release builds are excluded", audit, "!BuildConfig.DEBUG"),
            Case("audit has one count header", audit, "outbound request=%s engine=%s kind=%s stream=%s"),
            Case("audit logs ordered body parts", audit, "bodyPart=%d/%d body=%s"),
            Case("response audit has one count header", audit, "inbound request=%s engine=%s kind=translation_batch bodyChars=%d"),
            Case("OpenAI contextual batch logs raw response", openAi, "engine = \"OPENAI\",\n                    body = translated"),
            Case("Anthropic contextual batch logs raw response", anthropic, "engine = \"ANTHROPIC\",\n                    body = translated"),
            Case("OpenAI normal translation", openAi, "\"OPENAI\", \"translation\", false"),
            Case("OpenAI streaming translation", openAi, "\"OPENAI\", \"translation\", true"),
            Case("OpenAI contextual batch follows the streaming switch", openAi, "\"OPENAI\", \"translation_batch\", stream"),
            Case("OpenAI dictionary", openAi, "\"OPENAI\", \"dictionary\", false"),
            Case("Anthropic normal translation", anthropic, "\"ANTHROPIC\", \"translation\", false"),
            Case("Anthropic streaming translation", anthropic, "\"ANTHROPIC\", \"translation\", true"),
            Case("Anthropic contextual batch follows the streaming switch", anthropic, "\"ANTHROPIC\", \"translation_batch\", stream"),
            Case("Anthropic dictionary", anthropic, "\"ANTHROPIC\", \"dictionary\", false"),
        ).forEach { case -> assertTrue(case.name, case.content.contains(case.marker)) }

        assertEquals("OpenAI has exactly four audited translation paths", 4, openAi.count("TranslationRequestAudit.log("))
        assertEquals("Anthropic has exactly four audited translation paths", 4, anthropic.count("TranslationRequestAudit.log("))
        assertEquals("OpenAI has one structured response audit", 1, openAi.count("TranslationRequestAudit.logStructuredResponse("))
        assertEquals("Anthropic has one structured response audit", 1, anthropic.count("TranslationRequestAudit.logStructuredResponse("))
        assertFalse("headers are never inspected", audit.contains("request.headers"))
        assertFalse("authorization is never logged", audit.contains("Authorization"))
        assertFalse("API keys are never accepted", audit.contains("apiKey"))
    }

    private fun String.count(value: String): Int = windowed(value.length).count { it == value }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
