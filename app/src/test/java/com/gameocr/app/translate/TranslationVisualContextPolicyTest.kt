package com.gameocr.app.translate

import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.OpenAiRequestOptions
import com.gameocr.app.data.RemoteImageDetail
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.RuntimeTranslationVisualContext
import com.gameocr.app.data.RuntimeVisualTextItem
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationVisualContextPolicyTest {
    @Test
    fun shouldPrepare_tableDrivenHonorsToggleAndEngineAcrossEveryMode() {
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
            Case("OpenAI quick Blocks", TranslatorEngine.OPENAI,
                TranslationContextMode.FAST_PER_SEGMENT, RenderMode.BLOCKS, false, true, true),
            Case("OpenAI quick ordinary floating", TranslatorEngine.OPENAI,
                TranslationContextMode.FAST_PER_SEGMENT, RenderMode.FLOATING_WINDOW, false, true, true),
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
    fun visualPrompt_tableDrivenUsesUnorderedGeometryAndModeSpecificStrictContract() {
        data class Case(
            val combine: Boolean,
            val activeIds: Set<Int>,
            val expectedRequestKey: String,
            val expectedSystemMarker: String,
        )
        listOf(
            Case(true, setOf(1), "result_id", "ocr_corrections"),
            Case(false, setOf(1, 2), "translation_ids", "ocr_corrections"),
        ).forEach { case ->
            val prompt = VisualTranslationPromptPolicy.buildUserPayload(
                context = visualContext(case.combine),
                activeIds = case.activeIds,
            )
            val payload = Json.parseToJsonElement(prompt).jsonObject
            val items = payload.getValue("visual_items").jsonArray
            assertEquals(case.toString(), 2, items.size)
            assertEquals("梓ちゃん誰?", items[0].jsonObject.getValue("source").jsonPrimitive.content)
            assertEquals(
                listOf("100", "100", "300", "300"),
                items[0].jsonObject.getValue("box").jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(case.toString(), case.expectedRequestKey in payload)

            val system = VisualTranslationPromptPolicy.buildSystemSuffix(
                visual = visualContext(case.combine),
                context = com.gameocr.app.data.RuntimeTranslationPromptContext(),
            )
            assertTrue(case.toString(), system.contains(case.expectedSystemMarker))
            assertTrue(case.toString(), system.contains("ordered_ids"))
            assertTrue(case.toString(), system.contains("confidence is at least 0.8"))
            assertTrue(case.toString(), system.contains("speakers, actions, relationships"))
            assertTrue(case.toString(), system.contains("visual_items is an unordered set") || !case.combine)
            assertFalse(case.toString(), system.contains("Translate only translation_items"))
        }
    }

    @Test
    fun visualResponse_tableDrivenValidatesOrderCorrectionsAndTranslationMapping() {
        data class Case(
            val name: String,
            val raw: String,
            val combine: Boolean,
            val expectedIndexes: List<Int>,
            val expectedComplete: Boolean,
            val expectedOrder: List<Int> = emptyList(),
            val expectedCorrectionIds: List<Int> = emptyList(),
            val expectedTranslation: String? = null,
        )
        listOf(
            Case(
                "valid reordered merged output with confident OCR correction",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ！","confidence":0.96}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                true,
                listOf(2, 1),
                listOf(2),
                "译文",
            ),
            Case(
                "numeric string ids remain compatible",
                """{"ordered_ids":["2","1"],"ocr_corrections":[],"translations":[{"id":"1","translation":"译文"}]}""",
                true,
                listOf(0),
                true,
                listOf(2, 1),
                expectedTranslation = "译文",
            ),
            Case(
                "merged output accepts complete per-item translations in reading order",
                """{"ordered_ids":[2,1],"ocr_corrections":[],"translations":[{"id":1,"translation":"梓酱是谁？"},{"id":2,"translation":"她是我表妹。"}]}""",
                true,
                listOf(0),
                true,
                listOf(2, 1),
                expectedTranslation = "她是我表妹。 梓酱是谁？",
            ),
            Case(
                "merged output accepts strict alternate value fields",
                """{"ordered_ids":[1,2],"ocr_corrections":[],"translations":[{"id":"2","text":"她是我表妹。"},{"id":"1","result":"梓酱是谁？"}]}""",
                true,
                listOf(0),
                true,
                listOf(1, 2),
                expectedTranslation = "梓酱是谁？ 她是我表妹。",
            ),
            Case(
                "ordinary visual batch also requires complete reading order",
                """{"ordered_ids":[2,1],"ocr_corrections":[],"translations":[{"id":2,"translation":"乙"},{"id":1,"translation":"甲"}]}""",
                false,
                listOf(0, 1),
                true,
                listOf(2, 1),
            ),
            Case(
                "merged per-item output rejects a missing visual id",
                """{"ordered_ids":[2,1],"ocr_corrections":[],"translations":[{"id":1,"translation":"甲"},{"id":3,"translation":"丙"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "merged per-item output rejects duplicate visual ids",
                """{"ordered_ids":[2,1],"ocr_corrections":[],"translations":[{"id":1,"translation":"甲"},{"id":1,"translation":"重复"},{"id":2,"translation":"乙"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "merged per-item output rejects blank translations",
                """{"ordered_ids":[2,1],"ocr_corrections":[],"translations":[{"id":1,"translation":"甲"},{"id":2,"translation":"  "}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "missing order is rejected",
                """{"ocr_corrections":[],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "missing correction array is rejected",
                """{"ordered_ids":[2,1],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "duplicate order id is rejected",
                """{"ordered_ids":[1,1],"ocr_corrections":[],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "unknown order id is rejected",
                """{"ordered_ids":[1,3],"ocr_corrections":[],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "extra root field is rejected",
                """{"ordered_ids":[2,1],"ocr_corrections":[],"translations":[{"id":1,"translation":"译文"}],"note":"x"}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "correction source must exactly match immutable OCR source",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"wrong source","corrected_source":"いとこだよ！","confidence":0.95}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "duplicate correction id is rejected",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ！","confidence":0.95},{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ。","confidence":0.96}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "unknown correction id is rejected",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":3,"source":"x","corrected_source":"y","confidence":0.95}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "non merged retry cannot correct an inactive item",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ！","confidence":0.95}],"translations":[{"id":1,"translation":"甲"}]}""",
                false,
                listOf(0),
                false,
            ),
            Case(
                "unchanged correction is rejected",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ!","confidence":0.95}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "low confidence correction is rejected",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ！","confidence":0.79}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
            Case(
                "out of range confidence is rejected",
                """{"ordered_ids":[2,1],"ocr_corrections":[{"id":2,"source":"いとこだよ!","corrected_source":"いとこだよ！","confidence":1.1}],"translations":[{"id":1,"translation":"译文"}]}""",
                true,
                listOf(0),
                false,
            ),
        ).forEach { case ->
            val result = VisualTranslationResponsePolicy.validateAndNormalize(
                raw = case.raw,
                context = visualContext(case.combine),
                expectedIndexes = case.expectedIndexes,
                json = Json { ignoreUnknownKeys = false },
            )
            assertEquals(case.name, case.expectedComplete, result.complete)
            assertEquals(case.name, case.expectedOrder, result.orderedIds)
            assertEquals(case.name, case.expectedCorrectionIds, result.ocrCorrections.map { it.id })
            if (case.expectedComplete) {
                assertTrue(case.name, result.normalizedPayload.orEmpty().contains("\"translations\""))
                assertFalse(case.name, result.normalizedPayload.orEmpty().contains("ordered_ids"))
                assertFalse(case.name, result.normalizedPayload.orEmpty().contains("ocr_corrections"))
                val normalized = Json.parseToJsonElement(result.normalizedPayload.orEmpty()).jsonObject
                case.expectedTranslation?.let { expectedTranslation ->
                    val translation = normalized.getValue("translations").jsonArray
                        .single().jsonObject.values.last().jsonPrimitive.content
                    assertEquals(case.name, expectedTranslation, translation)
                }
            } else {
                assertEquals(case.name, null, result.normalizedPayload)
            }
        }
    }

    @Test
    fun openAiMessages_tableDrivenKeepTextScalarOffAndControlImageDetail() {
        data class Case(
            val name: String,
            val visual: RuntimeTranslationVisualContext?,
            val detail: RemoteImageDetail,
            val customDetail: String = "",
            val expectedDetail: String?,
        )
        val resolved = OpenAiRequestPolicy.resolve(
            text = "plain source",
            systemPromptTemplate = "translate",
            sourceDisplay = "Japanese",
            targetDisplay = "Chinese",
            runtimeContext = "",
            options = OpenAiRequestOptions(userMessageTemplate = "{text}", systemPromptSuffix = ""),
            networkRequestTimeoutSeconds = 30,
        )
        listOf(
            Case("no visual context", null, RemoteImageDetail.AUTO, expectedDetail = null),
            Case("omit field", visualContext(false), RemoteImageDetail.OMIT, expectedDetail = null),
            Case("low", visualContext(false), RemoteImageDetail.LOW, expectedDetail = "low"),
            Case("auto", visualContext(false), RemoteImageDetail.AUTO, expectedDetail = "auto"),
            Case("high", visualContext(false), RemoteImageDetail.HIGH, expectedDetail = "high"),
            Case(
                "custom provider value",
                visualContext(false),
                RemoteImageDetail.CUSTOM,
                customDetail = "original",
                expectedDetail = "original",
            ),
            Case(
                "blank custom omits field",
                visualContext(false),
                RemoteImageDetail.CUSTOM,
                customDetail = "  ",
                expectedDetail = null,
            ),
        ).forEach { case ->
            val options = OpenAiRequestOptions(
                imageDetail = case.detail,
                customImageDetail = case.customDetail,
            )
            val content = buildOpenAiChatMessages(
                resolved = resolved,
                visualContext = case.visual,
                imageDetail = options.imageDetailWireValue(),
            ).last().content
            assertEquals(case.name, case.visual != null, content is kotlinx.serialization.json.JsonArray)
            if (case.visual != null) {
                val parts = content.jsonArray
                assertEquals("text", parts[0].jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals("plain source", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
                val imageUrl = parts[1].jsonObject.getValue("image_url").jsonObject
                assertTrue(imageUrl.getValue("url").jsonPrimitive.content
                    .startsWith("data:image/jpeg;base64,"))
                assertEquals(case.name, case.expectedDetail, imageUrl["detail"]?.jsonPrimitive?.content)
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
