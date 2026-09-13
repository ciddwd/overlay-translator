package com.gameocr.app.ocr

import com.gameocr.app.data.AutoOcrSettings

internal data class LanguageScore(val language: String, val confidence: Float)
internal data class AutoOcrEvidence(val text: String, val language: String?, val confidence: Float)

/** Language ID is text evidence, not proof that OCR saw every glyph in the image. */
internal object AutoOcrLanguagePolicy {
    fun choose(text: String, candidates: List<LanguageScore>): String? {
        val letters = text.count(Char::isLetter)
        if (letters < 2) return null
        val sorted = candidates.filter { it.language != "und" && it.confidence.isFinite() }
            .sortedByDescending { it.confidence }
        val best = sorted.firstOrNull() ?: return null
        val code = AutoOcrSettings.languageKey(best.language)
        val kana = text.count { it in '\u3040'..'\u30ff' || it in '\uff65'..'\uff9f' }
        val hangul = text.count { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' || it in '\u3130'..'\u318f' }
        val han = text.count { it in '\u3400'..'\u9fff' }
        if (kana > 0 && code != "ja") return null
        if (hangul > 0 && code != "ko") return null
        if (code == "ja" && kana == 0 && letters < 8) return null
        // Short shared Han words and Latin UI tokens are insufficient routing evidence.
        if (kana == 0 && hangul == 0 && letters < 4) return null
        val threshold = if (han == letters) 0.85f else 0.65f
        if (best.confidence < threshold) return null
        if (best.confidence - (sorted.getOrNull(1)?.confidence ?: 0f) < 0.15f) return null
        return code
    }

    fun weight(evidence: AutoOcrEvidence): Float = evidence.text.count(Char::isLetter)
        .coerceAtMost(400) * evidence.confidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f).orZero()

    private fun Float?.orZero(): Float = this ?: 0f

    fun score(evidence: List<AutoOcrEvidence>): Float = evidence.sumOf {
        (weight(it) * if (it.language == null) 0.15f else 1f).toDouble()
    }.toFloat()

    fun dominant(evidence: List<AutoOcrEvidence>): String? {
        val total = evidence.sumOf { weight(it).toDouble() }
        if (total < 12) return null
        val grouped = evidence.filter { it.language != null }.groupBy { it.language!! }
            .mapValues { (_, items) -> items.sumOf { weight(it).toDouble() } }
        val top = grouped.maxByOrNull { it.value } ?: return null
        return top.key.takeIf { top.value / total >= 0.75 }
    }
}
