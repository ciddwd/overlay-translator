package com.gameocr.app.ocr

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UmiOcrEngineTest {

    @Test
    fun umiEndpointUrlOrNull_normalizesBaseUrls() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("blank", "", null),
            Case("invalid", "not a url", null),
            Case("base url appends api path", " http://192.168.0.2:1224 ", "http://192.168.0.2:1224/api/ocr"),
            Case("full api path is kept", "http://pc.local:1224/api/ocr", "http://pc.local:1224/api/ocr"),
            Case("custom path appends api path", "https://ocr.example.com/umi", "https://ocr.example.com/umi/api/ocr"),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, umiOcrEndpointUrlOrNull(case.raw))
        }
    }

    @Test
    fun umiOcrHttpHostOrNull_onlyReturnsHttpHosts() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("http host", "http://pc-name:1224/api/ocr", "pc-name"),
            Case("http ip", "http://192.168.0.2:1224", "192.168.0.2"),
            Case("https does not need cleartext", "https://ocr.example.com/api/ocr", null),
            Case("invalid", "pc-name:1224", null),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, umiOcrHttpHostOrNull(case.raw))
        }
    }

    @Test
    fun umiOcrLanguageConfigFor_mapsCommonSourceLanguages() {
        data class Case(
            val sourceLang: String,
            val expected: String?,
        )

        val cases = listOf(
            Case("auto", null),
            Case("zh-TW", "models/config_chinese_cht.txt"),
            Case("zh-Hant-HK", "models/config_chinese_cht.txt"),
            Case("zh-CN", "models/config_chinese.txt"),
            Case("zh", "models/config_chinese.txt"),
            Case("ja-JP", "models/config_japan.txt"),
            Case("ko", "models/config_korean.txt"),
            Case("en-US", "models/config_en.txt"),
            Case("ru-RU", "models/config_cyrillic.txt"),
            Case("fr", null),
        )

        cases.forEach { case ->
            assertEquals(case.sourceLang, case.expected, umiOcrLanguageConfigFor(case.sourceLang))
        }
    }

    @Test
    fun parseUmiOcrResponse_parsesDictBlocks() {
        val raw = """
            {
              "code": 100,
              "data": [
                {
                  "text": "first block",
                  "score": 0.98,
                  "box": [[10,20],[30,18],[31,50],[9,52]],
                  "end": "\n"
                },
                {
                  "text": "second block",
                  "score": 1.2,
                  "box": [[100.4,5.4],[120.6,5.4],[120.6,40.6],[100.4,40.6]]
                }
              ],
              "time": 0.123,
              "timestamp": 1780000000
            }
        """.trimIndent()

        val parsed = parseUmiOcrResponse(raw)

        assertEquals(100, parsed.code)
        assertEquals(0.123, parsed.serviceTimeSeconds!!, 0.0001)
        assertEquals(2, parsed.blocks.size)
        assertEquals("first block", parsed.blocks[0].text)
        assertEquals(0.98f, parsed.blocks[0].score, 0.0001f)
        assertEquals("\n", parsed.blocks[0].end)
        assertEquals(1f, parsed.blocks[1].score, 0.0001f)
    }

    @Test
    fun parseUmiOcrResponse_handlesNoTextAndErrors() {
        val noText = parseUmiOcrResponse("""{"code":101,"data":"no text","time":0.02}""")
        assertEquals(101, noText.code)
        assertTrue(noText.blocks.isEmpty())
        assertEquals("no text", noText.message)

        try {
            parseUmiOcrResponse("""{"code":400,"data":"bad request"}""")
            throw AssertionError("Expected Umi-OCR error")
        } catch (t: RuntimeException) {
            assertTrue(t.message.orEmpty().contains("code=400"))
        }
    }

    @Test
    fun umiBoundsFromBox_mapsPointBoxesToAxisAlignedBounds() {
        data class Case(
            val name: String,
            val rawBox: String?,
            val expected: UmiOcrBounds?,
        )

        val cases = listOf(
            Case(
                name = "four point integer box",
                rawBox = "[[10,20],[30,18],[31,50],[9,52]]",
                expected = UmiOcrBounds(9, 18, 31, 52),
            ),
            Case(
                name = "decimal points are rounded",
                rawBox = "[[100.4,5.4],[120.6,5.4],[120.6,40.6],[100.4,40.6]]",
                expected = UmiOcrBounds(100, 5, 121, 41),
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
            assertEquals(case.name, case.expected, umiBoundsFromBox(box))
        }
    }
}
