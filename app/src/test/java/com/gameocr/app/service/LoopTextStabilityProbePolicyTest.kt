package com.gameocr.app.service

import com.gameocr.app.data.OcrEngineKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopTextStabilityProbePolicyTest {

    @Test
    fun probePolicy_coversEveryOcrEngineAndEndToEndOverride() {
        data class Case(
            val engine: OcrEngineKind,
            val expectedWithoutEndToEnd: Boolean,
        )

        val localOrLan = setOf(
            OcrEngineKind.UMI_OCR,
            OcrEngineKind.LUNA_OCR,
            OcrEngineKind.ML_KIT_AUTO,
            OcrEngineKind.ML_KIT_LATIN,
            OcrEngineKind.ML_KIT_JAPANESE,
            OcrEngineKind.ML_KIT_CHINESE,
            OcrEngineKind.ML_KIT_KOREAN,
            OcrEngineKind.PADDLE_ONNX,
            OcrEngineKind.MANGA_OCR_JA,
        )
        val cases = OcrEngineKind.entries.map { engine ->
            Case(engine, engine in localOrLan)
        }

        cases.forEach { case ->
            assertEquals(
                "${case.engine} regular OCR",
                case.expectedWithoutEndToEnd,
                allowsFrequentTextStabilityProbe(case.engine, configuredEndToEnd = false),
            )
            assertEquals(
                "${case.engine} end-to-end override",
                false,
                allowsFrequentTextStabilityProbe(case.engine, configuredEndToEnd = true),
            )
        }
        assertEquals("every engine covered", OcrEngineKind.entries.size, cases.size)
    }
}
