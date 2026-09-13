package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoOcrRegionSelectionTest {
    private data class Region(val start: Int, val end: Int, val text: String, val lang: String?, val confidence: Float)
    private fun select(values: List<Region>) = selectAutoOcrRegions(values,
        { AutoOcrEvidence(it.text, it.lang, it.confidence) },
        { a, b -> minOf(a.end, b.end) > maxOf(a.start, b.start) })

    @Test fun mixedPageKeepsEachLanguagesBestRegion_tableDriven() {
        val ja = Region(0, 10, "こんにちは。何かご希望はありますか", "ja", .9f)
        val zh = Region(20, 30, "您好您有什么特别的需求吗", "zh", .9f)
        val ko = Region(40, 50, "안녕하세요 특별한 요청이 있으신가요", "ko", .9f)
        val en = Region(60, 70, "Hello do you have any requests", "en", .9f)
        val good = listOf(ja, zh, ko, en)
        val wrong = good.map { it.copy(text = "오인식", lang = "ko", confidence = .1f) }
        for (input in listOf(good + wrong, wrong + good, (good + wrong).reversed())) {
            assertEquals(good.toSet(), select(input).toSet())
        }
    }

    @Test fun overlapEmptyAndUnknownCases_tableDriven() {
        val first = Region(0, 10, "こんにちは", "ja", .9f)
        val next = Region(10, 20, "안녕하세요", "ko", .9f)
        val short = Region(30, 40, "OK", null, .8f)
        val cases = listOf(
            emptyList<Region>() to emptySet(),
            listOf(first, first.copy(start = 1, end = 11, confidence = .5f)) to setOf(first),
            listOf(first, next) to setOf(first, next),
            listOf(first, short) to setOf(first, short),
            listOf(first.copy(text = "！ ？")) to emptySet(),
            listOf(first.copy(text = "")) to emptySet(),
            listOf(first, first.copy(confidence = Float.NaN)) to setOf(first),
        )
        cases.forEach { (input, expected) -> assertEquals(expected, select(input).toSet()) }
    }
}
