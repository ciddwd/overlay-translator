package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicRequestOptionsWiringTest {
    @Test
    fun remoteLlmTranslations_useSharedPolicyForFastPageAndContinuousModes_tableDriven() {
        data class EngineCase(
            val name: String,
            val path: String,
            val systemMarker: String,
            val userMarker: String,
            val maxTokensMarker: String,
            val temperatureMarker: String,
            val topPMarker: String,
        )
        val engines = listOf(
            EngineCase(
                name = "OpenAI compatible",
                path = "src/main/java/com/gameocr/app/translate/OpenAiTranslator.kt",
                systemMarker = "ChatMessage(role = \"system\", content = resolved.systemMessage)",
                userMarker = "ChatMessage(role = \"user\", content = resolved.userMessage)",
                maxTokensMarker = "maxTokens = resolved.maxTokens",
                temperatureMarker = "temperature = resolved.temperature",
                topPMarker = "topP = resolved.topP",
            ),
            EngineCase(
                name = "Anthropic compatible",
                path = "src/main/java/com/gameocr/app/translate/AnthropicTranslator.kt",
                systemMarker = "systemPrompt = resolvedRequest.systemMessage",
                userMarker = "userText = resolvedRequest.userMessage",
                maxTokensMarker = "resolvedRequest.maxTokens ?: TRANSLATION_MAX_TOKENS",
                temperatureMarker = "temperature = resolvedRequest.temperature",
                topPMarker = "topP = resolvedRequest.topP",
            ),
        )
        data class MarkerCase(val name: String, val marker: String)
        val sharedMarkers = listOf(
            MarkerCase("fast per-segment request", "resolveRequest(trimmed, settings)"),
            MarkerCase("shared template and encoding policy", "OpenAiRequestPolicy.resolve("),
            MarkerCase("page and continuous payload", "StructuredBatchPromptPolicy.buildUserPayload("),
            MarkerCase("page and continuous contract", "StructuredBatchPromptPolicy.buildSystemSuffix("),
            MarkerCase("pre-encoded structured payload", "textAlreadyPrepared = true"),
            MarkerCase("shared remote timeout", "withApiTimeout(resolvedRequest.timeoutSeconds)"),
            MarkerCase("request options isolate translation cache", "resolvedRequest.cacheFingerprint"),
        )

        engines.forEach { engine ->
            val source = sourceFile(engine.path).readText()
            sharedMarkers.forEach { marker ->
                assertTrue("${engine.name}/${marker.name}", source.contains(marker.marker))
            }
            assertTrue("${engine.name}/resolved system prompt", source.contains(engine.systemMarker))
            assertTrue("${engine.name}/resolved user template", source.contains(engine.userMarker))
            assertTrue("${engine.name}/resolved max tokens", source.contains(engine.maxTokensMarker))
            assertTrue("${engine.name}/resolved temperature", source.contains(engine.temperatureMarker))
            assertTrue("${engine.name}/resolved optional top_p", source.contains(engine.topPMarker))
            assertFalse("${engine.name}/legacy fixed prompt", source.contains("translationPrompt("))
        }
    }

    @Test
    fun outboundEncodingFlags_doNotEnterOcrDisplayOrTranslationMemory_tableDriven() {
        data class Case(val name: String, val directory: String)

        listOf(
            Case("OCR engines", "src/main/java/com/gameocr/app/ocr"),
            Case("overlay rendering", "src/main/java/com/gameocr/app/overlay"),
            Case("capture service", "src/main/java/com/gameocr/app/service"),
            Case("translation memory and glossary", "src/main/java/com/gameocr/app/glossary"),
        ).forEach { case ->
            val offenders = sourceDirectory(case.directory)
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val text = file.readText()
                    text.contains("encodeUserTextBase64") ||
                        text.contains("encodeUserTextUnicode")
                }
                .map(File::getPath)
                .toList()

            assertTrue("${case.name}: $offenders", offenders.isEmpty())
        }
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")

    private fun sourceDirectory(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isDirectory)
        ?: error("Source directory not found: $path")
}
