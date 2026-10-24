package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrHalfTurnRerunPolicyTest {
    private fun blocks(vararg lines: Pair<String, Float>) =
        lines.map { (text, confidence) -> TextBlock(text, Rect(), confidence) }

    // Device log 2026-10-22, V5_ESLAV: an upright page read in full ...
    private val upright = blocks(
        "Колинда — обрядовая поздравительная и" to 0.981f,
        "благопожелательная песня, исполняемая" to 0.969f,
        "преимущественно в канун Рождества; в зимнем" to 0.971f,
        "репертуаре она занимает центральное место." to 0.949f,
        "Наряду с традиционными колиндами бытуют" to 0.986f,
    )

    // ... and the same page after the wrong 180-degree rerun: only fragments above the 0.5 cut.
    private val rotatedGarbage = blocks(
        "х - аинф и ф и" to 0.578f,
        "и  хи и" to 0.593f,
    )

    @Test fun wrongHalfTurnRerunDoesNotReplaceAConfidentReading() {
        assertFalse(OcrHalfTurnRerunPolicy.prefersRerun(first = upright, rerun = rotatedGarbage))
    }

    @Test fun genuineUpsideDownPageIsStillCorrected() {
        assertTrue(OcrHalfTurnRerunPolicy.prefersRerun(first = rotatedGarbage, rerun = upright))
    }

    @Test fun rerunMustReadClearlyBetterNotJustContainText_tableDriven() {
        data class Case(val first: List<TextBlock>, val rerun: List<TextBlock>, val accepted: Boolean)
        val line = "одноимённом романе Джоан Роулинг."
        listOf(
            Case(upright, emptyList(), accepted = false),
            Case(upright, blocks("   " to 0.9f), accepted = false),
            Case(emptyList(), blocks(line to 0.9f), accepted = true),
            // Engines without confidences (always 1.0) compare plain character counts.
            Case(blocks(line to 1f), blocks(line to 1f), accepted = false),
            Case(blocks("ии" to 1f), blocks(line to 1f), accepted = true),
            // A near tie keeps what was already read.
            Case(blocks(line to 0.90f), blocks(line to 0.95f), accepted = false),
        ).forEachIndexed { index, case ->
            val actual = OcrHalfTurnRerunPolicy.prefersRerun(case.first, case.rerun)
            if (case.accepted) assertTrue("case $index", actual) else assertFalse("case $index", actual)
        }
    }

    @Test fun onlyAConfidentMultiLineFirstPassSkipsTheRerun_tableDriven() {
        assertTrue(OcrHalfTurnRerunPolicy.firstPassIsReliable(upright))
        assertFalse(OcrHalfTurnRerunPolicy.firstPassIsReliable(rotatedGarbage))
        // Too few lines or too little text is not enough evidence to overrule the hint.
        assertFalse(OcrHalfTurnRerunPolicy.firstPassIsReliable(upright.take(2)))
        assertFalse(OcrHalfTurnRerunPolicy.firstPassIsReliable(blocks("NOS" to 0.95f, "SON" to 0.95f, "ON" to 0.95f)))
        // Blank blocks do not count as read lines.
        assertFalse(OcrHalfTurnRerunPolicy.firstPassIsReliable(upright.take(2) + blocks(" " to 0.99f)))
    }
}
