package com.gameocr.app.ocr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Reused only under the detector's inference lock; the direct buffers back ORT tensors. */
internal class MangaBubbleInputWorkspace(val inputSide: Int) {
    init { require(inputSide > 0) }
    val pixels = IntArray(inputSide * inputSide)
    private val nchw = FloatArray(pixels.size * 3)
    val imageBuffer: FloatBuffer = ByteBuffer.allocateDirect(nchw.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    val sizeBuffer: LongBuffer = ByteBuffer.allocateDirect(2 * Long.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asLongBuffer()

    fun pack(originalWidth: Int, originalHeight: Int) {
        require(originalWidth > 0 && originalHeight > 0)
        val plane = pixels.size
        for (i in pixels.indices) {
            val color = pixels[i]
            nchw[i] = (color ushr 16 and 255) / 255f
            nchw[plane + i] = (color ushr 8 and 255) / 255f
            nchw[2 * plane + i] = (color and 255) / 255f
        }
        // One bulk transfer replaces three JNI FloatBuffer.put calls per pixel.
        imageBuffer.clear()
        imageBuffer.put(nchw)
        imageBuffer.flip()
        sizeBuffer.clear()
        sizeBuffer.put(originalWidth.toLong()).put(originalHeight.toLong())
        sizeBuffer.flip()
    }
}
