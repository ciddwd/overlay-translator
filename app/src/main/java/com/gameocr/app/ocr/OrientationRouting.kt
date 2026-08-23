package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind

/**
 * Text orientation to OCR engine routing.
 *
 * This only overrides the user-selected OCR engine when the orientation signal
 * is strong enough to indicate that another on-device engine is materially
 * better. Cloud OCR engines must be selected explicitly by the user, never as
 * an automatic fallback.
 */
object OrientationRouting {

    /**
     * Returns the OCR engine that should be used for the current frame, or null
     * when the user-selected engine should be kept.
     */
    fun resolveEngine(
        orientation: TextOrientation,
        sourceLangBcp47: String,
        userEngine: OcrEngineKind,
        hasMangaOcr: Boolean,
        baiduConfigured: Boolean
    ): OcrEngineKind? {
        val lang = sourceLangBcp47.lowercase()
        return when (orientation) {
            TextOrientation.VERTICAL_RTL ->
                resolveVerticalRtl(lang, userEngine, hasMangaOcr, baiduConfigured)
            TextOrientation.STACKED -> null
            TextOrientation.VERTICAL_LTR -> null
            TextOrientation.HORIZONTAL_LTR,
            TextOrientation.HORIZONTAL_RTL,
            TextOrientation.UNKNOWN -> null
        }
    }

    private fun resolveVerticalRtl(
        lang: String,
        userEngine: OcrEngineKind,
        hasMangaOcr: Boolean,
        baiduConfigured: Boolean
    ): OcrEngineKind? {
        // An explicitly selected OCR engine is a user decision, not a hint. Presets already choose
        // Manga OCR or Paddle when appropriate; silently replacing any of the four single-script
        // ML Kit engines makes diagnostics impure and can execute a second full OCR pass.
        if (userEngine != OcrEngineKind.ML_KIT_AUTO) return null

        val isJapanese = lang == "ja" || lang.startsWith("ja-")
        val isChinese = lang.startsWith("zh")
        val isAuto = lang == "auto"
        // Chinese vertical text: never route to manga-ocr or cloud OCR. ML Kit
        // Chinese is the only automatic on-device fallback when the user did
        // not explicitly choose PaddleOCR.
        if (isChinese) {
            return OcrEngineKind.ML_KIT_CHINESE
        }

        // Japanese and auto vertical text are most commonly manga/game UI in
        // this app, so prefer manga-ocr when the local model is available.
        if (isJapanese || isAuto) {
            if (hasMangaOcr) {
                return OcrEngineKind.MANGA_OCR_JA
            }
            return null
        }

        return null
    }
}
