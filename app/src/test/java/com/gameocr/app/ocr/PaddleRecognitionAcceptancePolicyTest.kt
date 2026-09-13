package com.gameocr.app.ocr

import org.junit.Assert.*
import org.junit.Test

class PaddleRecognitionAcceptancePolicyTest {
    @Test fun score_table_filters_final_outputs_without_language_or_phrase_exceptions() {
        for (text in listOf("1D", "所以所者", "성공", "武", "!")) {
            listOf(0f to false, .214f to false, .275f to false, .486f to false,
                .499f to false, .5f to true, .888f to true, 1f to true,
                -1f to false, Float.NaN to false, Float.POSITIVE_INFINITY to false, 1.1f to false)
                .forEach { (score, keep) -> assertEquals("$text/$score", keep, PaddleRecognitionAcceptancePolicy.accepts(text, score)) }
        }
        listOf("", " ", "\n\t").forEach { assertFalse(PaddleRecognitionAcceptancePolicy.accepts(it, 1f)) }
    }
}
