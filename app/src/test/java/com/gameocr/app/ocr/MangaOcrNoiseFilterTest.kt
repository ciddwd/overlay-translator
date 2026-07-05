package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrNoiseFilterTest {

    @Test
    fun edge_noise_filter_is_conservative_for_table_driven_cases() {
        data class Case(
            val name: String,
            val text: String,
            val rect: IntRect,
            val expected: Boolean
        )

        val cases = listOf(
            Case(
                name = "drops-bottom-edge-single-cjk-noise-from-device-log",
                text = "\u4E00",
                rect = IntRect(31, 2488, 122, 2546),
                expected = true
            ),
            Case(
                name = "keeps-ascii-page-number-at-edge",
                text = "4",
                rect = IntRect(17, 596, 122, 689),
                expected = false
            ),
            Case(
                name = "keeps-multi-character-top-edge-bubble",
                text = "\u304F\u3093!!",
                rect = IntRect(41, 0, 139, 152),
                expected = false
            ),
            Case(
                name = "keeps-real-left-edge-multi-character-bubble",
                text = "\u307E\u3060\u30A8\u30C3\u30C1\u306A\u3053\u3068\u306F\u3057\u305F\u304F\u306A\u3044\u306E...",
                rect = IntRect(0, 1107, 84, 1262),
                expected = false
            ),
            Case(
                name = "keeps-single-cjk-away-from-edge",
                text = "\u4E00",
                rect = IntRect(300, 1200, 390, 1260),
                expected = false
            ),
            Case(
                name = "keeps-single-kana-at-edge",
                text = "\u304F",
                rect = IntRect(31, 2488, 122, 2546),
                expected = false
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldDropMangaOcrEdgeNoise(case.text, case.rect, imgW = 1440, imgH = 2546)
            )
        }
    }
}
