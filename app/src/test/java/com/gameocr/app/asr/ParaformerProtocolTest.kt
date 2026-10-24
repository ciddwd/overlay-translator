package com.gameocr.app.asr

import com.gameocr.app.data.AsrLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message shapes from the official client/server event pages (see [ParaformerRealtimeEngine]). */
class ParaformerProtocolTest {
    @Test fun runTaskMatchesTheDocumentedShape_tableDriven() {
        data class Case(val language: AsrLanguage, val hints: List<String>?)
        listOf(
            Case(AsrLanguage.AUTO, null),
            Case(AsrLanguage.JA, listOf("ja")),
            Case(AsrLanguage.YUE, listOf("yue")),
        ).forEach { case ->
            val root = Json.parseToJsonElement(ParaformerRealtimeEngine.runTask("task-1", case.language)).jsonObject
            val header = root.getValue("header").jsonObject
            assertEquals("run-task", header.getValue("action").jsonPrimitive.content)
            assertEquals("task-1", header.getValue("task_id").jsonPrimitive.content)
            assertEquals("duplex", header.getValue("streaming").jsonPrimitive.content)
            val payload = root.getValue("payload").jsonObject
            assertEquals("audio", payload.getValue("task_group").jsonPrimitive.content)
            assertEquals("asr", payload.getValue("task").jsonPrimitive.content)
            assertEquals("recognition", payload.getValue("function").jsonPrimitive.content)
            assertEquals("paraformer-realtime-v2", payload.getValue("model").jsonPrimitive.content)
            assertTrue(payload.getValue("input").jsonObject.isEmpty())
            val parameters = payload.getValue("parameters").jsonObject
            assertEquals("pcm", parameters.getValue("format").jsonPrimitive.content)
            assertEquals(16_000, parameters.getValue("sample_rate").jsonPrimitive.int)
            assertTrue(parameters.getValue("heartbeat").jsonPrimitive.boolean)
            // A short pause inside a sentence must not use the rejected 400 ms threshold.
            assertEquals(1300, parameters.getValue("max_sentence_silence").jsonPrimitive.int)
            assertFalse(parameters.getValue("semantic_punctuation_enabled").jsonPrimitive.boolean)
            assertEquals(case.toString(), case.hints, parameters["language_hints"]?.jsonArray?.map { it.jsonPrimitive.content })
        }
    }

    @Test fun finishTaskReusesTheTaskId() {
        val root = Json.parseToJsonElement(ParaformerRealtimeEngine.finishTask("task-9")).jsonObject
        val header = root.getValue("header").jsonObject
        assertEquals("finish-task", header.getValue("action").jsonPrimitive.content)
        assertEquals("task-9", header.getValue("task_id").jsonPrimitive.content)
        assertTrue(root.getValue("payload").jsonObject.getValue("input").jsonObject.isEmpty())
    }

    @Test fun serverEventsParse_tableDriven() {
        fun result(text: String, end: Boolean, heartbeat: Boolean = false, endTime: String = "920") = """
            {"header":{"task_id":"t","event":"result-generated","attributes":{}},
             "payload":{"output":{"sentence":{"begin_time":170,"end_time":$endTime,"text":"$text",
             "heartbeat":$heartbeat,"sentence_end":$end,"words":[]}},"usage":null}}
        """.trimIndent()
        data class Case(val name: String, val json: String, val expected: ParaformerEvent?)
        listOf(
            Case("started", """{"header":{"task_id":"t","event":"task-started","attributes":{}},"payload":{}}""",
                ParaformerEvent.Started),
            Case("partial", result("你好", end = false), ParaformerEvent.Result("你好", sentenceEnd = false, heartbeat = false)),
            Case("final", result("你好。", end = true), ParaformerEvent.Result("你好。", sentenceEnd = true, heartbeat = false)),
            // The docs show end_time as null on some results.
            Case("null end time", result("hi", end = false, endTime = "null"), ParaformerEvent.Result("hi", false, false)),
            Case("heartbeat", result("", end = false, heartbeat = true), ParaformerEvent.Result("", false, heartbeat = true)),
            Case("finished", """{"header":{"task_id":"t","event":"task-finished","attributes":{}},"payload":{"output":{},"usage":null}}""",
                ParaformerEvent.Finished),
            Case("failed", """{"header":{"task_id":"t","event":"task-failed","error_code":"CLIENT_ERROR","error_message":"request timeout after 23 seconds.","attributes":{}},"payload":{}}""",
                ParaformerEvent.Failed("CLIENT_ERROR", "request timeout after 23 seconds.")),
            Case("unknown event", """{"header":{"event":"something-new"}}""", null),
            Case("not json", "garbage", null),
        ).forEach { case -> assertEquals(case.name, case.expected, ParaformerEvent.parse(case.json)) }
    }

    @Test fun pcm16IsLittleEndianAndClamped_tableDriven() {
        data class Case(val sample: Float, val low: Int, val high: Int)
        listOf(
            Case(0f, 0x00, 0x00),
            Case(1f, 0xff, 0x7f),
            Case(2f, 0xff, 0x7f),
            Case(-1f, 0x01, 0x80),
            Case(-0.5f, 0x01, 0xc0),
        ).forEach { case ->
            val bytes = ParaformerRealtimeEngine.pcm16(floatArrayOf(case.sample)).toByteArray()
            assertEquals(case.toString(), 2, bytes.size)
            assertEquals(case.toString(), case.low, bytes[0].toInt() and 0xff)
            assertEquals(case.toString(), case.high, bytes[1].toInt() and 0xff)
        }
        assertEquals(0, ParaformerRealtimeEngine.pcm16(FloatArray(0)).size)
    }

    @Test fun parserNeverReturnsTextForMissingSentence() {
        assertNull(ParaformerEvent.parse("""{"header":{"event":"result-generated"},"payload":{"output":{}}}"""))
        assertFalse(ParaformerEvent.parse("""{"header":{"event":"task-started"}}""") is ParaformerEvent.Result)
    }
}
