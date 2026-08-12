package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings

interface OcrEngine {
    /** 在 [bitmap] 上跑 OCR。区域识别由调用方裁剪后传入。 */
    suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind = OcrEngineKind.ML_KIT_AUTO): List<TextBlock>

    /**
     * Runs OCR with the immutable settings snapshot already captured by the caller.
     *
     * Engines that do not consume settings keep their existing implementation through this default.
     * Settings-aware engines override it so one capture does not repeatedly read and decode DataStore.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        kind: OcrEngineKind,
        settings: Settings,
    ): List<TextBlock> = recognize(bitmap, kind)

    /** 释放底层资源（ML Kit recognizer / Paddle ONNX session 等）。 */
    fun close()
}

/** 端侧模型未安装 / 损坏时抛出。CaptureService 看到这个异常会引导用户去设置页下载模型。 */
class ModelNotReadyException(message: String) : RuntimeException(message)
