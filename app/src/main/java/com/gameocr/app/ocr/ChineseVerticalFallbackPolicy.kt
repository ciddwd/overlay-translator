package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import java.util.Locale

private const val MIN_VERTICAL_CROP_ASPECT = 1.25f
private const val MAX_WEAK_BLOCKS = 3
private const val MAX_WEAK_CHARS = 12
private const val MAX_WEAK_AVG_CHARS_PER_BLOCK = 5f

internal fun shouldRerunLowQualityChinesePaddleOcr(
    sourceLangBcp47: String,
    engine: OcrEngineKind,
    autoDetect: Boolean,
    manualOrientationLocked: Boolean,
    imageWidth: Int,
    imageHeight: Int,
    blockCount: Int,
    portraitBlockCount: Int,
    nonWhitespaceChars: Int
): Boolean {
    if (!autoDetect || manualOrientationLocked) return false
    if (engine != OcrEngineKind.PADDLE_ONNX) return false
    if (!sourceLangBcp47.isChineseLanguageTag()) return false
    if (imageWidth <= 0 || imageHeight <= 0) return false
    if (blockCount < 0 || blockCount > MAX_WEAK_BLOCKS) return false
    if (portraitBlockCount < 0 || portraitBlockCount > blockCount) return false
    if (nonWhitespaceChars < 0 || nonWhitespaceChars > MAX_WEAK_CHARS) return false
    val imageLooksVertical = imageHeight.toFloat() / imageWidth >= MIN_VERTICAL_CROP_ASPECT
    val blocksLookVertical = blockCount > 0 && portraitBlockCount == blockCount
    if (!imageLooksVertical && !blocksLookVertical) return false
    if (blockCount == 0) return true
    return nonWhitespaceChars.toFloat() / blockCount <= MAX_WEAK_AVG_CHARS_PER_BLOCK
}

private fun String.isChineseLanguageTag(): Boolean {
    val lang = trim().lowercase(Locale.ROOT)
    return lang == "zh" || lang.startsWith("zh-")
}
