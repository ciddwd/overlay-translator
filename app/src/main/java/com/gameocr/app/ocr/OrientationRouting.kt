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
        val isJapanese = lang == "ja" || lang.startsWith("ja-")
        val isChinese = lang.startsWith("zh")
        val isAuto = lang == "auto"
        val userAlreadyChoseCloudOcr = userEngine == OcrEngineKind.BAIDU ||
            userEngine == OcrEngineKind.TENCENT ||
            userEngine == OcrEngineKind.YOUDAO ||
            userEngine == OcrEngineKind.PADDLE_AI_STUDIO

        if (
            userAlreadyChoseCloudOcr ||
            userEngine == OcrEngineKind.PADDLE_ONNX ||
            userEngine == OcrEngineKind.UMI_OCR ||
            userEngine == OcrEngineKind.LUNA_OCR
        ) {
            return null
        }

        // Chinese vertical text: never route to manga-ocr or cloud OCR. ML Kit
        // Chinese is the only automatic on-device fallback when the user did
        // not explicitly choose PaddleOCR.
        if (isChinese) {
            return if (userEngine == OcrEngineKind.ML_KIT_CHINESE) null else OcrEngineKind.ML_KIT_CHINESE
        }

        // Japanese and auto vertical text are most commonly manga/game UI in
        // this app, so prefer manga-ocr when the local model is available.
        if (isJapanese || isAuto) {
            if (hasMangaOcr) {
                return if (userEngine == OcrEngineKind.MANGA_OCR_JA) null else OcrEngineKind.MANGA_OCR_JA
            }
            return null
        }

        return null
    }
}
