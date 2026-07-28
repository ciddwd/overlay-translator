package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduFanyiBatchPolicyTest {

    @Test
    fun `batch query keeps exactly one API line per OCR block`() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val expected: String,
        )

        val cases = listOf(
            Case("single line", listOf("hello"), "hello"),
            Case("internal LF", listOf("first\nsecond"), "first second"),
            Case("internal CRLF", listOf("first\r\nsecond"), "first second"),
            Case("internal CR", listOf("first\rsecond"), "first second"),
            Case("blank lines and indentation", listOf(" first \n\n  second "), "first second"),
            Case(
                "multiple OCR blocks",
                listOf("first\nblock", "second\r\nblock", "third"),
                "first block\nsecond block\nthird",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                BaiduFanyiBatchPolicy.buildQuery(case.sources),
            )
            assertEquals(
                "${case.name} line count",
                case.sources.size,
                BaiduFanyiBatchPolicy.buildQuery(case.sources).lineSequence().count(),
            )
        }
    }

    @Test
    fun `result count validation rejects every index mismatch`() {
        data class Case(
            val name: String,
            val expected: Int,
            val actual: Int,
            val shouldThrow: Boolean,
        )

        val cases = listOf(
            Case("exact empty", expected = 0, actual = 0, shouldThrow = false),
            Case("exact batch", expected = 4, actual = 4, shouldThrow = false),
            Case("missing result", expected = 4, actual = 3, shouldThrow = true),
            Case("extra result", expected = 4, actual = 5, shouldThrow = true),
        )

        cases.forEach { case ->
            val failure = runCatching {
                BaiduFanyiBatchPolicy.requireResultCount(case.expected, case.actual)
            }.exceptionOrNull()
            assertEquals(case.name, case.shouldThrow, failure != null)
            if (case.shouldThrow) {
                assertTrue(case.name, failure is TranslationException)
            }
        }
    }

    @Test
    fun `v2 cache namespace isolates pre-fix results`() {
        val source = "first\nsecond"
        val settings = Settings()
        val cache = TranslationCache()
        val legacyKey = cache.key(source, "baidu-fanyi-zh", "zh", "")
        cache.put(legacyKey, "wrong shifted translation", settings)

        val currentKey = cache.key(
            source,
            BaiduFanyiBatchPolicy.cacheModel(targetCode = "zh"),
            "zh",
            "",
        )

        assertNull(cache.get(currentKey, settings))
    }
}
