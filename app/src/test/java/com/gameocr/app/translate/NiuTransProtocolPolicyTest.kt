package com.gameocr.app.translate

import com.gameocr.app.data.NiuTransMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NiuTransProtocolPolicyTest {
    @Test
    fun `execution policy keeps Pro serial without changing other translators_tableDriven`() {
        data class Case(
            val name: String,
            val mode: NiuTransMode,
            val source: String,
            val prefersBatch: Boolean,
            val nativeBatch: Boolean,
        )

        listOf(
            Case("Flash explicit language", NiuTransMode.FLASH, "ja", true, true),
            Case("Flash auto source", NiuTransMode.FLASH, "auto", true, false),
            Case("Pro explicit language", NiuTransMode.PRO, "ja", false, false),
            Case("Pro auto source", NiuTransMode.PRO, "auto", false, false),
        ).forEach { case ->
            assertEquals(case.name, case.prefersBatch, NiuTransExecutionPolicy.prefersBatch(case.mode))
            assertEquals(case.name, case.nativeBatch, NiuTransExecutionPolicy.canUseNativeBatch(case.mode, case.source))
        }
    }

    @Test
    fun `Flash signature sorts fields and ignores empty or auth field_tableDriven`() {
        data class Case(
            val name: String,
            val fields: Map<String, String>,
            val expected: String,
        )

        listOf(
            Case(
                name = "single text official signing order",
                fields = linkedMapOf(
                    "to" to "zh",
                    "srcText" to "原文",
                    "timestamp" to "1722470400000",
                    "from" to "ja",
                    "appId" to "demo",
                ),
                expected = "c6518a1889e89f5bb97c5a0128099b10",
            ),
            Case(
                name = "empty optional and caller authStr are excluded",
                fields = mapOf("from" to "ja", "to" to "zh", "termLibraryId" to "", "authStr" to "bad"),
                expected = NiuTransAuthPolicy.authString(
                    "secret",
                    mapOf("from" to "ja", "to" to "zh"),
                ),
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, NiuTransAuthPolicy.authString("secret", case.fields))
        }
    }

    @Test
    fun `Flash batch planner respects item and Unicode character limits_tableDriven`() {
        data class Case(
            val name: String,
            val texts: List<String>,
            val chunkSizes: List<Int>,
            val rejected: Set<Int>,
        )

        listOf(
            Case("empty", emptyList(), emptyList(), emptySet()),
            Case("fifty stay together", List(50) { "x" }, listOf(50), emptySet()),
            Case("fifty one split", List(51) { "x" }, listOf(50, 1), emptySet()),
            Case("character boundary split", listOf("a".repeat(4_999), "bc"), listOf(1, 1), emptySet()),
            Case("surrogate pair counts as one character", listOf("😀".repeat(5_000)), listOf(1), emptySet()),
            Case("oversize does not block valid neighbors", listOf("ok", "x".repeat(5_001), "yes"), listOf(2), setOf(1)),
        ).forEach { case ->
            val plan = NiuTransBatchPolicy.plan(
                case.texts.mapIndexed { index, text -> NiuTransBatchPolicy.Entry(index, text) },
            )
            assertEquals(case.name, case.chunkSizes, plan.chunks.map { it.size })
            assertEquals(case.name, case.rejected, plan.rejectedIndexes)
            plan.chunks.flatten().forEach { entry -> assertFalse(case.name, entry.index in case.rejected) }
        }
    }

    @Test
    fun `Pro SSE event parser handles every documented event_tableDriven`() {
        val json = Json { ignoreUnknownKeys = true }
        data class Case(val name: String, val raw: String, val expected: NiuTransSseUpdate)

        listOf(
            Case("start", """{"type":"start"}""", NiuTransSseUpdate.Start),
            Case("delta", """{"type":"delta","seq":"2","delta":"译"}""", NiuTransSseUpdate.Delta(2, "译")),
            Case("done", """{"type":"done","result":"译文","finishReason":"stop"}""", NiuTransSseUpdate.Done("译文", "stop")),
            Case("error", """{"type":"error","errorCode":"10001","message":"QPS"}""", NiuTransSseUpdate.Error("10001", "QPS")),
        ).forEach { case ->
            assertEquals(case.name, case.expected, NiuTransSsePolicy.parse(case.raw, json))
        }

        assertTrue(runCatching { NiuTransSsePolicy.parse("""{"type":"unknown"}""", json) }.isFailure)
        assertTrue(runCatching { NiuTransSsePolicy.parse("not-json", json) }.isFailure)
    }

    @Test
    fun `Pro HTTP 200 JSON response preserves real success and error_tableDriven`() {
        val json = Json { ignoreUnknownKeys = true }
        data class Case(
            val name: String,
            val raw: String,
            val expectedText: String? = null,
            val expectedError: String? = null,
        )

        listOf(
            Case(
                name = "ordinary success returned for a streaming request",
                raw = """{"errorCode":"200","tgtText":"  译文  "}""",
                expectedText = "译文",
            ),
            Case(
                name = "finite done event",
                raw = """{"type":"done","result":"完成","finishReason":"stop"}""",
                expectedText = "完成",
            ),
            Case(
                name = "QPS error keeps provider code and message",
                raw = """{"errorCode":"10001","errorMsg":"超出QPS限制"}""",
                expectedError = "10001: 超出QPS限制",
            ),
            Case(
                name = "stream error keeps provider code and message",
                raw = """{"type":"error","errorCode":"13003","message":"内容过滤"}""",
                expectedError = "13003: 内容过滤",
            ),
            Case(
                name = "result error keeps provider code and message",
                raw = """{"resultCode":"10004","resultMsg":"余额不足"}""",
                expectedError = "10004: 余额不足",
            ),
            Case(
                name = "empty success is rejected",
                raw = """{"errorCode":"200","tgtText":" "}""",
                expectedError = "返回空译文",
            ),
            Case(
                name = "invalid JSON is rejected",
                raw = "not-json",
                expectedError = "响应解析失败",
            ),
        ).forEach { case ->
            val parsed = runCatching { NiuTransProJsonResponsePolicy.parse(case.raw, json) }
            if (case.expectedError == null) {
                assertEquals(case.name, case.expectedText, parsed.getOrThrow())
            } else {
                assertTrue(case.name, parsed.exceptionOrNull()?.message.orEmpty().contains(case.expectedError))
            }
        }
    }

    @Test
    fun `Pro response type detection accepts JSON media types only_tableDriven`() {
        data class Case(val contentType: String?, val expected: Boolean)

        listOf(
            Case("application/json", true),
            Case("application/json; charset=utf-8", true),
            Case("application/problem+json", true),
            Case("text/event-stream", false),
            Case("text/plain", false),
            Case(null, false),
        ).forEach { case ->
            assertEquals(
                case.contentType,
                case.expected,
                NiuTransProJsonResponsePolicy.isJsonContentType(case.contentType),
            )
        }
    }

    @Test
    fun `Pro stream end distinguishes completion JSON fallback and missing done_tableDriven`() {
        data class Case(
            val name: String,
            val completed: Boolean,
            val events: Int,
            val deltas: Int,
            val raw: String,
            val expectedType: Class<out NiuTransStreamEndResolution>,
            val expectedMessagePart: String? = null,
        )

        listOf(
            Case(
                name = "done event completed",
                completed = true,
                events = 3,
                deltas = 1,
                raw = "",
                expectedType = NiuTransStreamEndResolution.Complete::class.java,
            ),
            Case(
                name = "ordinary JSON body returned instead of SSE",
                completed = false,
                events = 0,
                deltas = 0,
                raw = """{"errorCode":"10001","errorMsg":"QPS"}""",
                expectedType = NiuTransStreamEndResolution.JsonFallback::class.java,
            ),
            Case(
                name = "delta without done is incomplete",
                completed = false,
                events = 2,
                deltas = 1,
                raw = "",
                expectedType = NiuTransStreamEndResolution.Incomplete::class.java,
                expectedMessagePart = "events=2, deltas=1",
            ),
            Case(
                name = "empty response is incomplete",
                completed = false,
                events = 0,
                deltas = 0,
                raw = "",
                expectedType = NiuTransStreamEndResolution.Incomplete::class.java,
                expectedMessagePart = "events=0, deltas=0",
            ),
        ).forEach { case ->
            val result = NiuTransStreamEndPolicy.resolve(
                completed = case.completed,
                eventCount = case.events,
                deltaCount = case.deltas,
                nonSseBody = case.raw,
            )
            assertEquals(case.name, case.expectedType, result.javaClass)
            case.expectedMessagePart?.let { expected ->
                assertTrue(case.name, (result as NiuTransStreamEndResolution.Incomplete).message.contains(expected))
            }
        }
    }

    @Test
    fun `Pro request gate permits only one active request`() = runBlocking {
        val gate = NiuTransProRequestGate(cooldownAfterCompletionMs = 0L)
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        fun markEntered(signal: CompletableDeferred<Unit>) {
            val count = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, count) }
            signal.complete(Unit)
        }

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                markEntered(firstEntered)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstEntered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                markEntered(secondEntered)
                active.decrementAndGet()
            }
        }

        assertFalse("second request must wait", secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        joinAll(first, second)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `Pro request gate releases permit after failure or cancellation_tableDriven`() = runBlocking {
        listOf("failure", "cancellation").forEach { exit ->
            val gate = NiuTransProRequestGate(cooldownAfterCompletionMs = 0L)
            when (exit) {
                "failure" -> runCatching { gate.run<Unit> { error("expected") } }
                "cancellation" -> {
                    val entered = CompletableDeferred<Unit>()
                    val request = launch(start = CoroutineStart.UNDISPATCHED) {
                        gate.run {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    entered.await()
                    request.cancelAndJoin()
                }
                else -> error("Unknown test case: $exit")
            }
            assertEquals(exit, "next", gate.run { "next" })
        }
    }

    @Test
    fun `Pro request gate applies one second cooldown after every completion_tableDriven`() = runBlocking {
        data class Case(
            val name: String,
            val firstExit: String,
            val elapsedBeforeNextMs: Long,
            val expectedWaitMs: Long,
        )

        listOf(
            Case("success immediately followed", "success", 0L, 1_000L),
            Case("success after partial cooldown", "success", 400L, 600L),
            Case("success after full cooldown", "success", 1_000L, 0L),
            Case("provider error is also paced", "failure", 25L, 975L),
        ).forEach { case ->
            var now = 10_000L
            val waits = mutableListOf<Long>()
            val gate = NiuTransProRequestGate(
                cooldownAfterCompletionMs = 1_000L,
                nowMs = { now },
                waitMs = { duration ->
                    waits += duration
                    now += duration
                },
            )

            when (case.firstExit) {
                "success" -> gate.run { "ok" }
                "failure" -> runCatching { gate.run<Unit> { error("expected") } }
                else -> error("Unknown test case: ${case.firstExit}")
            }
            now += case.elapsedBeforeNextMs
            gate.run { "next" }

            assertEquals(case.name, listOf(case.expectedWaitMs).filter { it > 0L }, waits)
        }
    }

    @Test
    fun `Pro diagnostic response removes credentials and bounds output_tableDriven`() {
        data class Case(val raw: String, val forbidden: List<String>)

        listOf(
            Case("""{"apikey":"secret","errorCode":"10001"}""", listOf("secret")),
            Case("""{"authStr":"signature","message":"failed"}""", listOf("signature")),
            Case("""{"authorization":"Bearer token","message":"failed"}""", listOf("Bearer token")),
            Case("""{"token":"private","message":"failed"}""", listOf("private")),
        ).forEach { case ->
            val sanitized = NiuTransDiagnosticPolicy.sanitize(case.raw + "x".repeat(600))
            case.forbidden.forEach { secret -> assertFalse(case.raw, sanitized.contains(secret)) }
            assertTrue(case.raw, sanitized.length <= 500)
        }
    }

    @Test
    fun `Flash batch response preserves mapping and isolates partial failures_tableDriven`() {
        val json = Json { ignoreUnknownKeys = true }
        data class Case(
            val name: String,
            val raw: String,
            val sources: List<String>,
            val expected: List<String?>?,
        )

        listOf(
            Case(
                "success with echoed source",
                """{"tgtList":[{"srcText":"甲","tgtText":"A"},{"srcText":"乙","tgtText":"B"}]}""",
                listOf("甲", "乙"),
                listOf("A", "B"),
            ),
            Case(
                "success without echoed source",
                """{"tgtList":[{"tgtText":"A"}]}""",
                listOf("甲"),
                listOf("A"),
            ),
            Case(
                "one item failure does not discard neighbors",
                """{"tgtList":[{"tgtText":"A"},{"errorCode":"13003","errorMsg":"filtered"},{"tgtText":"C"}]}""",
                listOf("甲", "乙", "丙"),
                listOf("A", null, "C"),
            ),
            Case(
                "success error code remains accepted",
                """{"tgtList":[{"errorCode":"200","tgtText":"A"}]}""",
                listOf("甲"),
                listOf("A"),
            ),
            Case(
                "count mismatch rejected",
                """{"tgtList":[{"tgtText":"A"}]}""",
                listOf("甲", "乙"),
                null,
            ),
            Case(
                "echoed source mismatch rejected",
                """{"tgtList":[{"srcText":"乙","tgtText":"A"}]}""",
                listOf("甲"),
                null,
            ),
        ).forEach { case ->
            val parsed = runCatching {
                NiuTransBatchResponsePolicy.parse(
                    json.parseToJsonElement(case.raw).jsonObject,
                    case.sources,
                )
            }
            if (case.expected == null) assertTrue(case.name, parsed.isFailure)
            else assertEquals(case.name, case.expected, parsed.getOrThrow())
        }
    }
}
