package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseVerticalFallbackPolicyTest {

    @Test
    fun low_quality_chinese_paddle_vertical_crop_triggers_rerun() {
        data class Case(
            val name: String,
            val blockCount: Int,
            val chars: Int
        )

        val cases = listOf(
            Case("empty-first-pass", blockCount = 0, chars = 0),
            Case("logcat-capture-8-shape", blockCount = 2, chars = 7),
            Case("three-short-fragments", blockCount = 3, chars = 12)
        )

        cases.forEach { case ->
            assertTrue(
                case.name,
                shouldRerunLowQualityChinesePaddleOcr(
                    sourceLangBcp47 = "zh-TW",
                    engine = OcrEngineKind.PADDLE_ONNX,
                    autoDetect = true,
                    manualOrientationLocked = false,
                    imageWidth = 485,
                    imageHeight = 860,
                    blockCount = case.blockCount,
                    portraitBlockCount = case.blockCount,
                    nonWhitespaceChars = case.chars
                )
            )
        }
    }

    @Test
    fun weak_portrait_boxes_in_square_crop_trigger_rerun() {
        assertTrue(
            "logcat-capture-4-shape",
            shouldRerunLowQualityChinesePaddleOcr(
                sourceLangBcp47 = "zh-TW",
                engine = OcrEngineKind.PADDLE_ONNX,
                autoDetect = true,
                manualOrientationLocked = false,
                imageWidth = 872,
                imageHeight = 860,
                blockCount = 2,
                portraitBlockCount = 2,
                nonWhitespaceChars = 5
            )
        )
    }

    @Test
    fun healthy_or_non_target_cases_do_not_trigger() {
        data class Case(
            val name: String,
            val sourceLang: String = "zh-TW",
            val engine: OcrEngineKind = OcrEngineKind.PADDLE_ONNX,
            val autoDetect: Boolean = true,
            val manualLocked: Boolean = false,
            val width: Int = 485,
            val height: Int = 860,
            val blockCount: Int = 2,
            val portraitBlocks: Int = 2,
            val chars: Int = 7
        )

        val cases = listOf(
            Case(name = "auto-detect-off", autoDetect = false),
            Case(name = "manual-orientation-locked", manualLocked = true),
            Case(name = "non-chinese-source", sourceLang = "ja"),
            Case(name = "non-paddle-engine", engine = OcrEngineKind.ML_KIT_CHINESE),
            Case(name = "not-a-vertical-crop", width = 872, height = 860, portraitBlocks = 0),
            Case(name = "square-crop-with-horizontal-boxes", width = 872, height = 860, portraitBlocks = 0),
            Case(name = "many-blocks", blockCount = 4, chars = 12),
            Case(name = "enough-text", blockCount = 3, chars = 24),
            Case(name = "invalid-portrait-count", portraitBlocks = 3),
            Case(name = "invalid-size", width = 0, height = 860)
        )

        cases.forEach { case ->
            assertFalse(
                case.name,
                shouldRerunLowQualityChinesePaddleOcr(
                    sourceLangBcp47 = case.sourceLang,
                    engine = case.engine,
                    autoDetect = case.autoDetect,
                    manualOrientationLocked = case.manualLocked,
                    imageWidth = case.width,
                    imageHeight = case.height,
                    blockCount = case.blockCount,
                    portraitBlockCount = case.portraitBlocks,
                    nonWhitespaceChars = case.chars
                )
            )
        }
    }
}
