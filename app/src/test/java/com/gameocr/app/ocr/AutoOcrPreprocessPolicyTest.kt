package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.PreprocessOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoOcrPreprocessPolicyTest {
    @Test
    fun tonalOptions_keepOnlyInvertAndBinarize_tableDriven() {
        listOf(
            PreprocessOptions() to null,
            PreprocessOptions(upscale2x = true) to null,
            PreprocessOptions(binarize = true) to PreprocessOptions(binarize = true),
            PreprocessOptions(upscale2x = true, invert = true, binarize = true) to PreprocessOptions(invert = true, binarize = true),
        ).forEach { (input, expected) -> assertEquals(input.toString(), expected, AutoOcrPreprocessPolicy.tonalOptions(input)) }
    }

    @Test
    fun onlyEnginesThatTolerateTonalChangesReceiveThem_tableDriven() {
        listOf(
            OcrEngineKind.ML_KIT_JAPANESE to false,
            OcrEngineKind.MANGA_OCR_JA to false,
            OcrEngineKind.ML_KIT_LATIN to true,
            OcrEngineKind.ML_KIT_CHINESE to true,
            OcrEngineKind.ML_KIT_KOREAN to true,
            OcrEngineKind.ML_KIT_DEVANAGARI to true,
            OcrEngineKind.PADDLE_ONNX to true,
        ).forEach { (kind, expected) -> assertEquals(kind.name, expected, AutoOcrPreprocessPolicy.usesTonal(kind)) }
    }
}
