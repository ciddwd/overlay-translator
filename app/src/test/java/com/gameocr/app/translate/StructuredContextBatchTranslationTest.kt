package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.RuntimeGlossaryTerm
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.OpenAiRequestOptions
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StructuredContextBatchTranslationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun promptPolicy_encodesOnlySourceValuesAndLeavesProtocolToSharedRequestPolicy_tableDriven() {
        data class Case(
            val name: String,
            val options: OpenAiRequestOptions,
            val expectedSource: String,
        )

        listOf(
            Case("plain", OpenAiRequestOptions(), "原文"),
            Case(
                "Base64",
                OpenAiRequestOptions(encodeUserTextBase64 = true),
                "5Y6f5paH",
            ),
            Case(
                "Unicode",
                OpenAiRequestOptions(encodeUserTextUnicode = true),
                "\\u539F\\u6587",
            ),
        ).forEach { case ->
            val payload = StructuredBatchPromptPolicy.buildUserPayload(
                StructuredBatchAttempt(listOf("原文"), listOf(0)),
                case.options,
            )
            val root = json.parseToJsonElement(payload).jsonObject
            val item = root.getValue("translation_items").jsonArray.single().jsonObject
            val suffix = StructuredBatchPromptPolicy.buildSystemSuffix(
                RuntimeTranslationPromptContext(),
                case.options,
            )

            assertEquals(case.name, setOf("translation_items"), root.keys)
            assertEquals(case.name, setOf("id", "source"), item.keys)
            assertEquals(case.name, 1, item.getValue("id").jsonPrimitive.content.toInt())
            assertEquals(case.name, case.expectedSource, item.getValue("source").jsonPrimitive.content)
            assertFalse(case.name, suffix.contains("Encoded source protocol"))
            assertFalse(case.name, suffix.contains("Decode each encoded source"))
            assertFalse(case.name, suffix.contains("Decode every escape"))
        }
    }

    @Test
    fun selectionPolicy_tableDriven_preservesFastAndSingleItemBehavior() {
        data class Case(
            val name: String,
            val mode: TranslationContextMode,
            val count: Int,
            val supported: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("fast remains per segment", TranslationContextMode.FAST_PER_SEGMENT, 3, true, false),
            Case("page uses one request", TranslationContextMode.PAGE_CONTEXT, 3, true, true),
            Case("continuous uses one request", TranslationContextMode.CONTINUOUS_CONTEXT, 3, true, true),
            Case("unsupported engine remains unchanged", TranslationContextMode.PAGE_CONTEXT, 3, false, false),
            Case("single item keeps normal path", TranslationContextMode.PAGE_CONTEXT, 1, true, false),
            Case("empty page keeps normal path", TranslationContextMode.PAGE_CONTEXT, 0, true, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                StructuredContextBatchSelectionPolicy.shouldUse(case.mode, case.count, case.supported),
            )
        }
    }

    @Test
    fun promptPolicy_initialRequest_containsEachCurrentSourceExactlyOnce() {
        val sources = listOf("line-a", "line-b", "line-c")
        val attempt = StructuredBatchAttempt(sources, sources.indices.toList())

        val payload = json.parseToJsonElement(
            StructuredBatchPromptPolicy.buildUserPayload(attempt),
        ).jsonObject
        val items = payload.getValue("translation_items").jsonArray

        assertEquals(listOf(1, 2, 3), items.map { it.jsonObject.getValue("id").jsonPrimitive.content.toInt() })
        assertEquals(sources, items.map { it.jsonObject.getValue("source").jsonPrimitive.content })
        assertFalse(payload.containsKey("context_items"))
        sources.forEach { source ->
            assertEquals(source, 1, StructuredBatchPromptPolicy.buildUserPayload(attempt).countOccurrences(source))
        }
    }

    @Test
    fun promptPolicy_retry_keepsStableIdAndMovesOtherLinesToContext() {
        val payload = json.parseToJsonElement(
            StructuredBatchPromptPolicy.buildUserPayload(
                StructuredBatchAttempt(listOf("a", "b", "c"), listOf(1)),
            ),
        ).jsonObject

        assertEquals(
            listOf(2 to "b"),
            payload.getValue("translation_items").jsonArray.map { item ->
                item.jsonObject.getValue("id").jsonPrimitive.content.toInt() to
                    item.jsonObject.getValue("source").jsonPrimitive.content
            },
        )
        assertEquals(
            listOf(1 to "a", 3 to "c"),
            payload.getValue("context_items").jsonArray.map { item ->
                item.jsonObject.getValue("id").jsonPrimitive.content.toInt() to
                    item.jsonObject.getValue("source").jsonPrimitive.content
            },
        )
    }

    @Test
    fun promptPolicy_systemContext_excludesDuplicatedCurrentPage() {
        val suffix = StructuredBatchPromptPolicy.buildSystemSuffix(
            RuntimeTranslationPromptContext(
                currentApplication = "reader-app",
                glossary = listOf(RuntimeGlossaryTerm("senpai", "senior")),
                currentPage = listOf("must-not-repeat-a", "must-not-repeat-b"),
                previousFrame = listOf(RuntimeDialogueTurn("previous-source", "previous-target")),
            ),
        )

        assertTrue(suffix.contains("reader-app"))
        assertTrue(suffix.contains("senpai"))
        assertTrue(suffix.contains("senior"))
        assertTrue(suffix.contains("previous-source"))
        assertTrue(suffix.contains("previous-target"))
        assertFalse(suffix.contains("must-not-repeat-a"))
        assertFalse(suffix.contains("must-not-repeat-b"))
    }

    @Test
    fun promptPolicy_systemContext_keepsReusedOrAlreadyResolvedCurrentLines() {
        val suffix = StructuredBatchPromptPolicy.buildSystemSuffix(
            context = RuntimeTranslationPromptContext(
                currentPage = listOf("reused", "pending-a", "pending-b"),
            ),
            activeSources = listOf("pending-a", "pending-b"),
        )

        assertTrue(suffix.contains("current_page_context"))
        assertTrue(suffix.contains("reused"))
        assertFalse(suffix.contains("pending-a"))
        assertFalse(suffix.contains("pending-b"))
    }

    @Test
    fun promptPolicy_retry_sendsEveryCurrentPageSourceExactlyOnceAcrossUserAndSystemMessages() {
        val sources = listOf("first", "retry-me", "last")
        val attempt = StructuredBatchAttempt(sources, listOf(1))
        val requestText = StructuredBatchPromptPolicy.buildUserPayload(attempt) +
            StructuredBatchPromptPolicy.buildSystemSuffix(
                context = RuntimeTranslationPromptContext(currentPage = sources),
                activeSources = attempt.allSources,
            )

        sources.forEach { source ->
            assertEquals(source, 1, requestText.countOccurrences(source))
        }
    }

    @Test
    fun responseParser_tableDriven_mapsByIdAndRejectsAmbiguity() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: Map<Int, String>,
            val unresolved: List<Int>,
            val duplicates: Set<Int> = emptySet(),
            val unknown: Set<Int> = emptySet(),
            val structured: Boolean = true,
        )

        listOf(
            Case(
                "response order is irrelevant",
                """{"translations":[{"id":3,"translation":"C"},{"id":1,"translation":"A"},{"id":2,"translation":"B"}]}""",
                mapOf(0 to "A", 1 to "B", 2 to "C"),
                emptyList(),
            ),
            Case(
                "sole non-id string field name is irrelevant",
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"source":"B"},{"result":"C","id":3}]}""",
                mapOf(0 to "A", 1 to "B", 2 to "C"),
                emptyList(),
            ),
            Case(
                "third item field is rejected",
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"source":"B","confidence":0.9},{"id":3,"translation":"C"}]}""",
                emptyMap(),
                listOf(0, 1, 2),
            ),
            Case(
                "non-string values are rejected",
                """{"translations":[{"id":1,"translation":1},{"id":2,"translation":{"text":"B"}},{"id":3,"translation":["C"]}]}""",
                emptyMap(),
                listOf(0, 1, 2),
            ),
            Case(
                "top level extra field rejects whole payload",
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"translation":"B"},{"id":3,"translation":"C"}],"note":"extra"}""",
                emptyMap(),
                listOf(0, 1, 2),
                structured = false,
            ),
            Case(
                "bare array rejects whole payload",
                """[{"id":1,"translation":"A"},{"id":2,"translation":"B"},{"id":3,"translation":"C"}]""",
                emptyMap(),
                listOf(0, 1, 2),
                structured = false,
            ),
            Case(
                "missing id retries only missing item",
                """{"translations":[{"id":1,"translation":"A"},{"id":3,"translation":"C"}]}""",
                emptyMap(),
                listOf(0, 1, 2),
            ),
            Case(
                "duplicate id is ambiguous",
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"translation":"B1"},{"id":2,"translation":"B2"},{"id":3,"translation":"C"}]}""",
                emptyMap(),
                listOf(0, 1, 2),
                duplicates = setOf(2),
            ),
            Case(
                "unknown id rejects the whole batch",
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"translation":"B"},{"id":3,"translation":"C"},{"id":9,"translation":"X"}]}""",
                emptyMap(),
                listOf(0, 1, 2),
                unknown = setOf(9),
            ),
            Case(
                "blank translation stays unresolved",
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"translation":"  "},{"id":3,"translation":"C"}]}""",
                emptyMap(),
                listOf(0, 1, 2),
            ),
            Case(
                "numeric string id is accepted",
                """{"translations":[{"id":"1","translation":"A"},{"id":"2","translation":"B"},{"id":"3","translation":"C"}]}""",
                mapOf(0 to "A", 1 to "B", 2 to "C"),
                emptyList(),
            ),
            Case(
                "fenced json with prose is accepted",
                "result follows\n```json\n{\"translations\":[{\"id\":1,\"translation\":\"A\"},{\"id\":2,\"translation\":\"B\"},{\"id\":3,\"translation\":\"C\"}]}\n```",
                mapOf(0 to "A", 1 to "B", 2 to "C"),
                emptyList(),
            ),
            Case(
                "malformed output resolves nothing",
                "not-json",
                emptyMap(),
                listOf(0, 1, 2),
                structured = false,
            ),
        ).forEach { case ->
            val actual = StructuredBatchResponseParser.parse(case.raw, listOf(0, 1, 2), json)
            assertEquals(case.name, case.expected, actual.translationsByIndex)
            assertEquals(case.name, case.unresolved, actual.unresolvedIndexes)
            assertEquals(case.name, case.duplicates, actual.duplicateIds)
            assertEquals(case.name, case.unknown, actual.unknownIds)
            assertEquals(case.name, case.structured, actual.structuredPayloadFound)
        }
    }

    @Test
    fun responseParser_observedEighteenToSeventeenDrift_rejectsTheEntireBatch() {
        val expectedIndexes = (0 until 18).toList()
        val raw = buildString {
            append("{\"translations\":[")
            (1 until 18).forEach { id ->
                if (id > 1) append(',')
                append("{\"id\":$id,\"translation\":\"T$id\"}")
            }
            append("]}")
        }

        val actual = StructuredBatchResponseParser.parse(raw, expectedIndexes, json)

        assertTrue(actual.structuredPayloadFound)
        assertEquals(emptyMap<Int, String>(), actual.translationsByIndex)
        assertEquals(expectedIndexes, actual.unresolvedIndexes)
    }

    @Test
    fun runner_normalPage_usesExactlyOneRequestAndMapsById() = runBlocking {
        val calls = mutableListOf<List<Int>>()
        val updates = mutableListOf<BatchTranslationUpdate>()

        val result = StructuredBatchTranslationRunner.translate(
            sources = listOf("a", "b", "c"),
            json = json,
            onUpdate = updates::add,
        ) { attempt ->
            calls += attempt.activeIndexes
            """{"translations":[{"id":3,"translation":"C"},{"id":1,"translation":"A"},{"id":2,"translation":"B"}]}"""
        }

        assertEquals(listOf(listOf(0, 1, 2)), calls)
        assertEquals(listOf("A", "B", "C"), result)
        assertEquals(setOf(0, 1, 2), updates.map(BatchTranslationUpdate::index).toSet())
    }

    @Test
    fun runner_partialResponse_retriesTheWholeBatchBeforeAcceptingAnything() = runBlocking {
        val calls = mutableListOf<List<Int>>()

        val result = StructuredBatchTranslationRunner.translate(
            sources = listOf("a", "b", "c"),
            json = json,
            onUpdate = { },
        ) { attempt ->
            calls += attempt.activeIndexes
            if (calls.size == 1) {
                """{"translations":[{"id":1,"translation":"A"},{"id":3,"translation":"C"}]}"""
            } else {
                """{"translations":[{"id":1,"translation":"A"},{"id":2,"translation":"B"},{"id":3,"translation":"C"}]}"""
            }
        }

        assertEquals(listOf(listOf(0, 1, 2), listOf(0, 1, 2)), calls)
        assertEquals(listOf("A", "B", "C"), result)
    }

    @Test
    fun runner_partialResponse_withoutRetry_keepsResolvedItemsAndStopsAfterOneRequest() = runBlocking {
        val calls = mutableListOf<List<Int>>()

        val result = StructuredBatchTranslationRunner.translate(
            sources = listOf("a", "b", "c"),
            json = json,
            onUpdate = { },
            retryEnabled = false,
        ) { attempt ->
            calls += attempt.activeIndexes
            """{"translations":[{"id":1,"translation":"A"},{"id":3,"translation":"C"}]}"""
        }

        assertEquals(listOf(listOf(0, 1, 2)), calls)
        assertEquals(listOf(null, null, null), result)
    }

    @Test
    fun runner_unstructuredWholePage_splitsWithoutReturningToPerItemImmediately() = runBlocking {
        val calls = mutableListOf<List<Int>>()

        val result = StructuredBatchTranslationRunner.translate(
            sources = listOf("a", "b", "c", "d"),
            json = json,
            onUpdate = { },
        ) { attempt ->
            calls += attempt.activeIndexes
            when (attempt.activeIndexes) {
                listOf(0, 1, 2, 3) -> "invalid-root"
                listOf(0, 1) -> """{"translations":[{"id":2,"translation":"B"},{"id":1,"translation":"A"}]}"""
                listOf(2, 3) -> """{"translations":[{"id":4,"translation":"D"},{"id":3,"translation":"C"}]}"""
                else -> error("Unexpected split ${attempt.activeIndexes}")
            }
        }

        assertEquals(
            listOf(listOf(0, 1, 2, 3), listOf(0, 1, 2, 3), listOf(0, 1), listOf(2, 3)),
            calls,
        )
        assertEquals(listOf("A", "B", "C", "D"), result)
    }

    @Test
    fun runner_persistentMalformedOutput_usesRequestBudgetFairlyAcrossSiblingGroups_tableDriven() = runBlocking {
        data class Case(
            val name: String,
            val sources: List<String>,
            val expectedCalls: List<List<Int>>,
        )

        listOf(
            Case(
                name = "even page",
                sources = listOf("a", "b", "c", "d"),
                expectedCalls = listOf(
                    listOf(0, 1, 2, 3),
                    listOf(0, 1, 2, 3),
                    listOf(0, 1),
                    listOf(2, 3),
                    listOf(0, 1),
                    listOf(2, 3),
                ),
            ),
            Case(
                name = "odd page",
                sources = listOf("a", "b", "c", "d", "e"),
                expectedCalls = listOf(
                    listOf(0, 1, 2, 3, 4),
                    listOf(0, 1, 2, 3, 4),
                    listOf(0, 1),
                    listOf(2, 3, 4),
                    listOf(0, 1),
                    listOf(2, 3, 4),
                ),
            ),
        ).forEach { case ->
            val calls = mutableListOf<List<Int>>()

            val result = StructuredBatchTranslationRunner.translate(
                sources = case.sources,
                json = json,
                onUpdate = { },
                maxRequests = 6,
            ) { attempt ->
                calls += attempt.activeIndexes
                "invalid-root"
            }

            assertEquals(case.name, case.expectedCalls, calls)
            assertEquals(case.name, List(case.sources.size) { null }, result)
        }
    }

    @Test
    fun runner_transportFailure_withoutRetry_propagatesAfterOneRequest() = runBlocking {
        val calls = mutableListOf<List<Int>>()
        try {
            StructuredBatchTranslationRunner.translate(
                sources = listOf("a", "b", "c"),
                json = json,
                onUpdate = { },
                retryEnabled = false,
            ) { attempt ->
                calls += attempt.activeIndexes
                throw TranslationException("network failed")
            }
            fail("Expected transport failure")
        } catch (error: TranslationException) {
            assertEquals("network failed", error.message)
        }
        assertEquals(listOf(listOf(0, 1, 2)), calls)
    }

    @Test
    fun runner_transportFailure_withRetry_retriesWholeActiveGroupOnlyOnce() = runBlocking {
        val calls = mutableListOf<List<Int>>()

        val result = StructuredBatchTranslationRunner.translate(
            sources = listOf("a", "b"),
            json = json,
            onUpdate = { },
            retryEnabled = true,
        ) { attempt ->
            calls += attempt.activeIndexes
            if (calls.size == 1) throw TranslationException("temporary network failure")
            """{"translations":[{"id":1,"translation":"A"},{"id":2,"translation":"B"}]}"""
        }

        assertEquals(listOf(listOf(0, 1), listOf(0, 1)), calls)
        assertEquals(listOf("A", "B"), result)
    }

    @Test
    fun transportRetryPolicy_tableDriven_doesNotRepeatCompletedTimeouts() {
        data class Case(val name: String, val error: Throwable, val expected: Boolean)

        listOf(
            Case("ordinary connection failure remains retryable", IOException("reset"), true),
            Case("direct timeout is not repeated", InterruptedIOException("timeout"), false),
            Case(
                "wrapped timeout is not repeated",
                TranslationException("request failed", InterruptedIOException("timeout")),
                false,
            ),
            Case("structured response failure remains retryable", TranslationException("empty"), true),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                StructuredBatchTransportRetryPolicy.shouldRetry(case.error),
            )
        }
    }

    @Test
    fun runner_nonRetryableTransportFailure_tableDriven_stopsAfterFirstRequest() = runBlocking {
        data class Case(val name: String, val error: Throwable)

        listOf(
            Case("direct timeout", InterruptedIOException("timeout")),
            Case(
                "wrapped timeout",
                TranslationException("request failed", InterruptedIOException("timeout")),
            ),
        ).forEach { case ->
            val calls = mutableListOf<List<Int>>()
            try {
                StructuredBatchTranslationRunner.translate(
                    sources = listOf("a", "b"),
                    json = json,
                    onUpdate = { },
                    retryEnabled = true,
                    shouldRetryTransportFailure = StructuredBatchTransportRetryPolicy::shouldRetry,
                ) { attempt ->
                    calls += attempt.activeIndexes
                    throw case.error
                }
                fail("${case.name}: expected transport failure")
            } catch (error: Throwable) {
                assertTrue(case.name, error === case.error)
            }
            assertEquals(case.name, listOf(listOf(0, 1)), calls)
        }
    }

    @Test
    fun runner_coroutineCancellation_isNeverRetried() = runBlocking {
        val calls = mutableListOf<List<Int>>()
        val cancellation = CancellationException("cancelled")

        try {
            StructuredBatchTranslationRunner.translate(
                sources = listOf("a", "b"),
                json = json,
                onUpdate = { },
                retryEnabled = true,
            ) { attempt ->
                calls += attempt.activeIndexes
                throw cancellation
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertTrue(error === cancellation)
        }
        assertEquals(listOf(listOf(0, 1)), calls)
    }

    private fun String.countOccurrences(value: String): Int {
        if (value.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val index = indexOf(value, from)
            if (index < 0) return count
            count += 1
            from = index + value.length
        }
    }
}
