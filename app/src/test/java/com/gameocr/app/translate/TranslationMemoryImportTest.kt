package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationMemoryImportTest {

    @Test
    fun parseValidJson() {
        val json = """
            {
              "packageName": "com.game.test",
              "sourceLang": "zh",
              "targetLang": "vi",
              "entries": [
                {
                  "source": "你好",
                  "target": "Xin chao"
                },
                {
                  "source": "攻击",
                  "target": "Tan cong"
                }
              ]
            }
        """.trimIndent()

        val doc = TranslationMemoryImportJson.parseDocument(json)
        assertEquals("com.game.test", doc.packageName)
        assertEquals("zh", doc.sourceLang)
        assertEquals("vi", doc.targetLang)
        assertEquals(2, doc.entries.size)
        assertEquals("你好", doc.entries[0].source)
        assertEquals("Xin chao", doc.entries[0].target)
    }

    @Test(expected = TranslationMemoryImportFormatException::class)
    fun parseMissingPackage() {
        val json = """
            {
              "sourceLang": "zh",
              "targetLang": "vi",
              "entries": [{"source": "a", "target": "b"}]
            }
        """.trimIndent()
        TranslationMemoryImportJson.parseDocument(json)
    }
}
