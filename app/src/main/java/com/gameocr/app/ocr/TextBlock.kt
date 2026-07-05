package com.gameocr.app.ocr

import android.graphics.Rect

/**
 * 一段识别出的文本（一般对应原图里的一个文本块）。
 * [boundingBox] 用屏幕坐标系，方便 overlay 直接贴在原文下方。
 */
data class TextBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float = 1f,
    val recognizedLanguage: String? = null,
    val layoutOrientation: TextOrientation? = null
)
