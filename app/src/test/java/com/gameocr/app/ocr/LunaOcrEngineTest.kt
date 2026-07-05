package com.gameocr.app.ocr

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaOcrEngineTest {

    @Test
    fun lunaEndpointUrlOrNull_normalizesBaseUrls() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("blank", "", null),
            Case("invalid", "not a url", null),
            Case("base url appends api path", " http://192.168.0.2:3456 ", "http://192.168.0.2:3456/api/ocr"),
            Case("full api path is kept", "http://pc.local:3456/api/ocr", "http://pc.local:3456/api/ocr"),
            Case("custom path appends api path", "https://ocr.example.com/luna", "https://ocr.example.com/luna/api/ocr"),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, lunaOcrEndpointUrlOrNull(case.raw))
        }
    }

    @Test
    fun lunaOcrHttpHostOrNull_onlyReturnsHttpHosts() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("http host", "http://pc-name:3456/api/ocr", "pc-name"),
            Case("http ip", "http://192.168.0.2:3456", "192.168.0.2"),
            Case("https does not need cleartext", "https://ocr.example.com/api/ocr", null),
            Case("invalid", "pc-name:3456", null),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, lunaOcrHttpHostOrNull(case.raw))
        }
    }

    @Test
    fun parseLunaOcrResponse_parsesBoxedResults() {
        val raw = """
            {
              "engine": {"id": "local", "name": "Local OCR"},
              "results": [
                {
                  "text": "first block",
                  "box": [
                    {"x": 10, "y": 20},
                    {"x": 31, "y": 18},
                    {"x": 30, "y": 52},
                    {"x": 9, "y": 50}
                  ]
                },
                {
                  "text": "second block",
                  "box": [
                    {"x": 100.4, "y": 5.4},
                    {"x": 120.6, "y": 5.4},
                    {"x": 120.6, "y": 40.6},
                    {"x": 100.4, "y": 40.6}
                  ]
                }
              ],
              "vertical": true,
              "timecost": 0.234
            }
        """.trimIndent()

        val parsed = parseLunaOcrResponse(raw)

        assertEquals("local", parsed.engineId)
        assertEquals("Local OCR", parsed.engineName)
        assertEquals(0.234, parsed.timeCostSeconds!!, 0.0001)
        assertTrue(parsed.vertical)
        assertEquals(2, parsed.blocks.size)
        assertEquals("first block", parsed.blocks[0].text)
        assertEquals(LunaOcrBounds(9, 18, 31, 52), parsed.blocks[0].boundingBox)
        assertEquals(LunaOcrBounds(100, 5, 121, 41), parsed.blocks[1].boundingBox)
    }

    @Test
    fun parseLunaOcrResponse_fallsBackToTopLevelTextAndReportsErrors() {
        val textOnly = parseLunaOcrResponse("""{"text":"only text","results":[]}""")
        assertEquals(1, textOnly.blocks.size)
        assertEquals("only text", textOnly.blocks[0].text)
        assertNull(textOnly.blocks[0].boundingBox)

        val noText = parseLunaOcrResponse("""{"results":[]}""")
        assertTrue(noText.blocks.isEmpty())

        try {
            parseLunaOcrResponse("""{"error":"OCR init fail"}""")
            throw AssertionError("Expected Luna-OCR error")
        } catch (t: RuntimeException) {
            assertTrue(t.message.orEmpty().contains("OCR init fail"))
        }
    }

    @Test
    fun lunaBoundsFromBox_mapsObjectPointBoxesToAxisAlignedBounds() {
        data class Case(
            val name: String,
            val rawBox: String?,
            val expected: LunaOcrBounds?,
        )

        val cases = listOf(
            Case(
                name = "four object points",
                rawBox = """[{"x":10,"y":20},{"x":30,"y":18},{"x":31,"y":50},{"x":9,"y":52}]""",
                expected = LunaOcrBounds(9, 18, 31, 52),
            ),
            Case(
                name = "decimal points are rounded",
                rawBox = """[{"x":100.4,"y":5.4},{"x":120.6,"y":5.4},{"x":120.6,"y":40.6},{"x":100.4,"y":40.6}]""",
                expected = LunaOcrBounds(100, 5, 121, 41),
            ),
            Case(
                name = "null box",
                rawBox = null,
                expected = null,
            ),
            Case(
                name = "malformed point",
                rawBox = """["bad"]""",
                expected = null,
            ),
        )

        cases.forEach { case ->
            val box = case.rawBox?.let { Json.parseToJsonElement(it) }
            assertEquals(case.name, case.expected, lunaBoundsFromBox(box))
        }
    }
}
