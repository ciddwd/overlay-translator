package com.gameocr.app.translate

import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.OpenAiRequestOptions
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.RuntimeTranslationVisualContext
import com.gameocr.app.data.RuntimeVisualTextItem
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationVisualContextPolicyTest {
    @Test
    fun shouldPrepare_tableDrivenHonorsEngineModeAndFloatingAll() {
        data class Case(
            val name: String,
            val engine: TranslatorEngine,
            val mode: TranslationContextMode,
            val presentation: RenderMode,
            val mergeAll: Boolean,
            val enabled: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("OpenAI page", TranslatorEngine.OPENAI, TranslationContextMode.PAGE_CONTEXT,
                RenderMode.BLOCKS, false, true, true),
            Case("Anthropic continuous", TranslatorEngine.ANTHROPIC,
                TranslationContextMode.CONTINUOUS_CONTEXT, RenderMode.FLOATING_WINDOW, false, true, true),
            Case("OpenAI floating all quick", TranslatorEngine.OPENAI,
                TranslationContextMode.FAST_PER_SEGMENT, RenderMode.FLOATING_WINDOW, true, true, true),
            Case("OpenAI quick Blocks avoids repeated image", TranslatorEngine.OPENAI,
                TranslationContextMode.FAST_PER_SEGMENT, RenderMode.BLOCKS, false, true, false),
            Case("OpenAI quick ordinary floating", TranslatorEngine.OPENAI,
                TranslationContextMode.FAST_PER_SEGMENT, RenderMode.FLOATING_WINDOW, false, true, false),
            Case("Sakura ignores toggle", TranslatorEngine.LOCAL_SAKURA,
                TranslationContextMode.PAGE_CONTEXT, RenderMode.FLOATING_WINDOW, true, true, false),
            Case("ML Kit ignores toggle", TranslatorEngine.GOOGLE_ML_KIT,
                TranslationContextMode.PAGE_CONTEXT, RenderMode.FLOATING_WINDOW, true, true, false),
            Case("toggle off", TranslatorEngine.OPENAI, TranslationContextMode.PAGE_CONTEXT,
                RenderMode.FLOATING_WINDOW, true, false, false),
        ).forEach { case ->
            val settings = Settings(
                translatorEngine = case.engine,
                translationContextMode = case.mode,
                renderMode = case.presentation,
                mergeAdjacentBlocks = case.mergeAll,
                mergeStrength = if (case.mergeAll) MergeStrength.ALL else MergeStrength.STANDARD,
                openAiRequestOptions = OpenAiRequestOptions(sendScreenImage = case.enabled),
            )
            assertEquals(case.name, case.expected, TranslationVisualContextPolicy.shouldPrepare(settings))
        }
    }

    @Test
    fun visualPrompt_tableDrivenKeepsPlainNumberedTextAndStrictOutputContract() {
        data class Case(val combine: Boolean, val activeIds: Set<Int>, val expectedInstruction: String)
        listOf(
            Case(true, setOf(1), "全部内容翻译成一个连贯段落"),
            Case(false, setOf(1, 3), "只翻译这些编号：1,3"),
        ).forEach { case ->
            val prompt = VisualTranslationPromptPolicy.buildUserPayload(
                context = visualContext(case.combine),
                activeIds = case.activeIds,
            )
            assertTrue(case.toString(), prompt.contains(case.expectedInstruction))
            assertTrue(case.toString(), prompt.contains("#1 box=100,100,300,300\n梓ちゃん誰?"))
            assertTrue(case.toString(), prompt.contains("#2 box=400,400,600,600\nいとこだよ!"))
            assertTrue(case.toString(), prompt.contains("\"translations\""))
            assertFalse(case.toString(), prompt.contains("translation_items"))
        }
    }

    @Test
    fun openAiMessages_tableDrivenKeepTextScalarOffAndUseNativeImagePartsOn() {
        data class Case(val visual: RuntimeTranslationVisualContext?, val expectedArray: Boolean)
        val resolved = OpenAiRequestPolicy.resolve(
            text = "plain source",
            systemPromptTemplate = "translate",
            sourceDisplay = "Japanese",
            targetDisplay = "Chinese",
            runtimeContext = "",
            options = OpenAiRequestOptions(userMessageTemplate = "{text}", systemPromptSuffix = ""),
            networkRequestTimeoutSeconds = 30,
        )
        listOf(Case(null, false), Case(visualContext(false), true)).forEach { case ->
            val content = buildOpenAiChatMessages(resolved, case.visual).last().content
            assertEquals(case.toString(), case.expectedArray, content is kotlinx.serialization.json.JsonArray)
            if (case.expectedArray) {
                val parts = content.jsonArray
                assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals("plain source", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
                assertTrue(parts[1].jsonObject.getValue("image_url").jsonObject
                    .getValue("url").jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
            } else {
                assertEquals("plain source", content.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun fallbackStatus_tableDrivenOnlyRetriesSchemaAndPayloadRejections() {
        mapOf(200 to false, 400 to true, 401 to false, 404 to false, 413 to true,
            415 to true, 422 to true, 429 to false, 500 to false).forEach { (code, expected) ->
            assertEquals("HTTP $code", expected, VisualRequestFallbackPolicy.shouldRetryWithoutImage(code))
        }
    }

    @Test
    fun responseFallback_tableDrivenCoversValidInvalidRefusalAndSecondAttempt() {
        data class Case(
            val name: String,
            val visual: Boolean,
            val allowed: Boolean,
            val complete: Boolean,
            val expected: Boolean,
        )
        listOf(
            Case("valid visual JSON", true, true, true, false),
            Case("non JSON visual response", true, true, false, true),
            Case("visual refusal", true, true, false, true),
            Case("text-only request", false, true, false, false),
            Case("second text attempt", true, false, false, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                VisualResponseFallbackPolicy.shouldRetryWithoutImage(
                    visualContextPresent = case.visual,
                    fallbackAllowed = case.allowed,
                    structuredResponseComplete = case.complete,
                ),
            )
        }
    }

    private fun visualContext(combine: Boolean) = RuntimeTranslationVisualContext(
        mimeType = "image/jpeg",
        base64Data = "YWJj",
        width = 100,
        height = 200,
        byteCount = 3,
        sha256 = "hash",
        items = listOf(
            RuntimeVisualTextItem(1, "梓ちゃん誰?", 100, 100, 300, 300),
            RuntimeVisualTextItem(2, "いとこだよ!", 400, 400, 600, 600),
        ),
        combineIntoSingleOutput = combine,
    )
}
