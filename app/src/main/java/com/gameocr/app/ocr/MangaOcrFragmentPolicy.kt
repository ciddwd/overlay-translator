package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** Requires independent text evidence before a tiny unassigned detector island becomes OCR text. */
internal object MangaOcrFragmentPolicy {
    /** A small corner of a larger word is not independent evidence for another OCR crop. */
    private fun independentEvidence(bounds: IntRect, d: MangaBubbleDetectionPostprocessor.Detection): Boolean {
        if (d.kind == MangaBubbleDetectionPostprocessor.Kind.BUBBLE || !d.confidence.isFinite()) return false
        val overlap = (minOf(bounds.right.toFloat(), d.right) - maxOf(bounds.left.toFloat(), d.left)).coerceAtLeast(0f) *
            (minOf(bounds.bottom.toFloat(), d.bottom) - maxOf(bounds.top.toFloat(), d.top)).coerceAtLeast(0f)
        val area = bounds.width.toFloat() * bounds.height
        val detectionArea = (d.right - d.left) * (d.bottom - d.top)
        return area > 0 && detectionArea > 0 && overlap / area >= 0.60f && overlap / detectionArea >= 0.25f
    }

    fun hasTextEvidence(bounds: IntRect, detections: List<MangaBubbleDetectionPostprocessor.Detection>): Boolean =
        detections.any { d ->
            if (d.kind == MangaBubbleDetectionPostprocessor.Kind.BUBBLE || !d.confidence.isFinite()) false else {
                val overlap = (minOf(bounds.right.toFloat(), d.right) - maxOf(bounds.left.toFloat(), d.left)).coerceAtLeast(0f) *
                    (minOf(bounds.bottom.toFloat(), d.bottom) - maxOf(bounds.top.toFloat(), d.top)).coerceAtLeast(0f)
                val area = bounds.width.toFloat() * bounds.height
                area > 0 && overlap / area >= 0.60f
            }
        }

    fun dropBeforeRecognition(entry: MangaOcrBubbleGroupingPolicy.Entry,
        detections: List<MangaBubbleDetectionPostprocessor.Detection>, imageWidth: Int, imageHeight: Int): Boolean {
        if (entry.guidedSource != BubbleModelRegrouper.Source.LEGACY_FALLBACK ||
            entry.regionGranularity == TextRegionGranularity.FREE_TEXT || entry.bubble.memberIndices.size != 1
        ) return false
        val bounds = entry.bubble.contentRect
        val minDimension = minOf(imageWidth, imageHeight).coerceAtLeast(1)
        val tinySide = (minDimension * 0.032f).toInt().coerceAtLeast(16)
        return bounds.width in 1..tinySide && bounds.height in 1..tinySide &&
            bounds.width.toDouble() * bounds.height <= imageWidth.toDouble() * imageHeight * 0.0005 &&
            detections.none { independentEvidence(bounds, it) }
    }

    fun needsSecondaryCheck(text: String, entry: MangaOcrBubbleGroupingPolicy.Entry,
        detections: List<MangaBubbleDetectionPostprocessor.Detection>, imageWidth: Int, imageHeight: Int): Boolean {
        if (entry.guidedSource != BubbleModelRegrouper.Source.LEGACY_FALLBACK ||
            entry.bubble.memberIndices.size != 1 || text.count { it.isLetterOrDigit() } != 1 ||
            text.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        ) return false
        val bounds = entry.bubble.contentRect
        val maxSide = (minOf(imageWidth, imageHeight) * 0.20f).toInt().coerceAtLeast(24)
        return bounds.width in 1..maxSide && bounds.height in 1..maxSide &&
            detections.none { it.confidence >= 0.60f && independentEvidence(bounds, it) }
    }

    data class SecondaryDecision(val outputText: String?, val reason: String)

    /** An uncertain single glyph may not erase the source image based on one decoder alone. */
    fun resolveSecondary(text: String, lines: List<MangaMixedScriptPaddleLine>): SecondaryDecision {
        fun normalized(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
            .filter { it.isLetterOrDigit() }
        val reference = normalized(text)
        val reliable = lines.filter { it.confidence.isFinite() && it.confidence >= 0.90f }
        if (reliable.any { normalized(it.text) == reference }) return SecondaryDecision(text, "single_glyph_agreement")
        // One isolated DBNet member, without a competing line or ruby: accept a clearly recognized
        // short word rather than keeping the manga decoder's one-character hallucination.
        val replacement = reliable.singleOrNull()?.takeIf { line ->
            val word = normalized(line.text)
            line.confidence >= 0.95f && word.length in 2..8 && word.all { char ->
                Character.UnicodeScript.of(char.code) in setOf(Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
            }
        }
        return if (replacement != null) SecondaryDecision(replacement.text.trim(), "confirmed_short_word")
            else SecondaryDecision(null, "unconfirmed_single_glyph")
    }

    fun dropAfterRecognition(text: String, entry: MangaOcrBubbleGroupingPolicy.Entry,
        detections: List<MangaBubbleDetectionPostprocessor.Detection>, imageWidth: Int, imageHeight: Int,
        averageConfidence: Float): Boolean {
        if (entry.guidedSource != BubbleModelRegrouper.Source.LEGACY_FALLBACK ||
            entry.regionGranularity == TextRegionGranularity.FREE_TEXT || hasTextEvidence(entry.bubble.contentRect, detections)
        ) return false
        val bounds = entry.bubble.contentRect
        val maxSide = (minOf(imageWidth, imageHeight) * 0.08f).toInt().coerceAtLeast(24)
        if (bounds.width > maxSide || bounds.height > maxSide) return false
        val letters = text.count { it.isLetterOrDigit() }
        return letters == 0 || (letters <= 2 && averageConfidence.isFinite() && averageConfidence < 0.80f)
    }
}
