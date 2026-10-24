package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoOcrRegionSelectionTest {
    private data class Region(val start: Int, val end: Int, val text: String, val lang: String?, val confidence: Float)
    private fun select(values: List<Region>) = selectAutoOcrRegions(values,
        { AutoOcrEvidence(it.text, it.lang, it.confidence) },
        { a, b -> minOf(a.end, b.end) > maxOf(a.start, b.start) })

    @Test fun capturedChineseUiRejectsLowConfidenceForeignScriptCandidates_tableDriven() {
        // Captured on the same Chinese UI, before refinement/translation (2026-10-17).
        val startup = Region(0, 10, "自启动", null, .7265625f)
        val shizuku = Region(20, 30, "正在使用 Shizuku截屏", null, .77253604f)
        val cases = listOf(
            listOf(Region(0, 10, "自烏効", null, .3294271f),
                Region(0, 10, "m까관", "ko", .13541667f), startup) to startup,
            listOf(Region(20, 30, "正在使用 Shizuku 載昇", "zh", .75811297f),
                Region(20, 30, "표표훈표 Shizuku", "ko", .5536222f), shizuku) to shizuku,
        )
        cases.forEach { (candidates, expected) ->
            for (input in listOf(candidates, candidates.reversed())) assertEquals(setOf(expected), select(input).toSet())
        }
    }

    @Test fun realKoreanCompetesEquallyOnPureAndMixedPages_tableDriven() {
        val korean = listOf(
            Region(0, 10, "설정", "ko", .94f),
            Region(20, 30, "확인", null, .92f), // Unknown language is equally valid for short Korean.
            Region(40, 50, "K팝", "ko", .90f),
            Region(60, 70, "안녕하세요. 오늘 날씨가 좋습니다.", "ko", .93f),
        )
        val alternatives = korean.map { it.copy(text = "自启动", lang = "zh", confidence = .10f) }
        val chinese = Region(80, 90, "自启动", null, .7265625f)
        val wrongKorean = chinese.copy(text = "m까관", lang = "ko", confidence = .13541667f)
        listOf(
            (korean + alternatives) to korean,
            (korean + alternatives + chinese + wrongKorean) to (korean + chinese),
        ).forEach { (candidates, expected) ->
            for (input in listOf(candidates, candidates.reversed())) assertEquals(expected.toSet(), select(input).toSet())
        }
    }

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
