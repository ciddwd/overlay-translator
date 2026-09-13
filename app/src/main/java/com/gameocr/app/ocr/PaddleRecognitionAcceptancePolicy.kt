package com.gameocr.app.ocr

/** Applied to final recognition candidates, after same-model crop refinement. */
internal object PaddleRecognitionAcceptancePolicy {
    const val MINIMUM_SCORE = 0.5f
    fun accepts(text: String, score: Float): Boolean =
        text.isNotBlank() && score.isFinite() && score in MINIMUM_SCORE..1f
}
