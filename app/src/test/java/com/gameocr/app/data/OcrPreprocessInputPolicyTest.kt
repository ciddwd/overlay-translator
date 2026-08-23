package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrPreprocessInputPolicyTest {

    @Test
    fun tonalDetailPolicy_tableDriven_onlyAppliesToModelsThatNeedOriginalImageDetail() {
        data class Case(
            val engine: OcrEngineKind,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(OcrEngineKind.ML_KIT_JAPANESE, true),
            Case(OcrEngineKind.MANGA_OCR_JA, true),
            Case(OcrEngineKind.ML_KIT_AUTO, false),
            Case(OcrEngineKind.ML_KIT_LATIN, false),
            Case(OcrEngineKind.ML_KIT_CHINESE, false),
            Case(OcrEngineKind.ML_KIT_KOREAN, false),
            Case(OcrEngineKind.PADDLE_ONNX, false),
            Case(OcrEngineKind.BAIDU, false),
        )

        cases.forEach { case ->
            assertEquals(case.engine.name, case.expected, case.engine.needsRawBitmap)
        }
    }
}
