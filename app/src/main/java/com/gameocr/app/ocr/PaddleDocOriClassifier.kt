package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.gameocr.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * PaddleOCR PP-LCNet document-orientation classifier.
 *
 * The upstream ONNX model is a 0/90/180/270 whole-image rotation classifier, not
 * a five-class text-layout classifier. Runtime routing therefore uses it only as
 * a conservative pre-OCR signal: high-confidence 90/270 outputs become a
 * vertical-text hint, while 0/180 and low-confidence outputs remain UNKNOWN and
 * let post-OCR bbox geometry make the final call.
 */
@Singleton
class PaddleDocOriClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: OrientationModelInstaller,
) : OrientationClassifier {

    private val initLock = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    override suspend fun classifyFromBitmap(bitmap: Bitmap): OrientationResult {
        ensureReady()
        return withContext(Dispatchers.Default) {
            val s = session ?: throw ModelNotReadyException(context.getString(R.string.err_orientation_model_not_ready))
            val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val input = bitmapToInputTensorData(bitmap)
            val tensor = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            )
            tensor.use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { result ->
                    val scores = extractScores(result.get(0).value)
                    if (scores.isEmpty()) {
                        return@withContext OrientationResult(
                            orientation = TextOrientation.UNKNOWN,
                            confidence = 0f,
                            rawAngle = 0,
                            source = SOURCE,
                        )
                    }
                    val probabilities = softmax(scores)
                    val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                    val angle = LABEL_ANGLES.getOrElse(bestIndex) { 0 }
                    val confidence = probabilities[bestIndex]
                    val orientation = orientationForAngle(angle, confidence)
                    val out = OrientationResult(
                        orientation = orientation,
                        confidence = confidence,
                        rawAngle = angle,
                        source = SOURCE,
                    )
                    val message =
                        "[orient-pre] doc-ori angle=${out.rawAngle} conf=${"%.2f".format(out.confidence)} -> ${out.orientation.name}"
                    Timber.i(message)
                    out
                }
            }
        }
    }

    override fun refineWithBoxes(
        prelim: OrientationResult,
        blocks: List<TextBlock>,
        bitmapW: Int,
        bitmapH: Int,
    ): OrientationResult = prelim

    suspend fun ensureReady() = initLock.withLock {
        if (session != null) return@withLock
        val files = installer.checkInstalled()
            ?: throw ModelNotReadyException(context.getString(R.string.err_orientation_model_not_ready))
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        session = e.createSession(files.model.absolutePath, OrtSession.SessionOptions())
        Timber.i("Paddle doc orientation ready: %dKB", files.model.length() / 1024)
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        // OrtEnvironment.getEnvironment() is shared with Paddle OCR engines. Do not close it here.
        env = null
    }

    private fun bitmapToInputTensorData(bitmap: Bitmap): FloatArray {
        val resized = resizeShort(bitmap, RESIZE_SHORT)
        val cropped = centerCrop(resized, INPUT_SIZE)
        return try {
            bitmapToRgbNchw(cropped)
        } finally {
            if (cropped !== resized) cropped.recycle()
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun resizeShort(bitmap: Bitmap, shortSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val scale = shortSide.toFloat() / minOf(w, h).coerceAtLeast(1)
        val newW = (w * scale).roundToInt().coerceAtLeast(shortSide)
        val newH = (h * scale).roundToInt().coerceAtLeast(shortSide)
        return if (newW == w && newH == h) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        }
    }

    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        val x = ((bitmap.width - size) / 2).coerceAtLeast(0)
        val y = ((bitmap.height - size) / 2).coerceAtLeast(0)
        val w = minOf(size, bitmap.width - x)
        val h = minOf(size, bitmap.height - y)
        return if (x == 0 && y == 0 && w == bitmap.width && h == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, x, y, w, h)
        }
    }

    private fun bitmapToRgbNchw(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val arr = FloatArray(3 * w * h)
        val planeSize = w * h
        for (i in 0 until planeSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            arr[i] = (r - MEAN[0]) / STD[0]
            arr[planeSize + i] = (g - MEAN[1]) / STD[1]
            arr[2 * planeSize + i] = (b - MEAN[2]) / STD[2]
        }
        return arr
    }

    companion object {
        const val SOURCE = "paddle-doc-ori"
        internal const val MIN_VERTICAL_CONFIDENCE = 0.65f
        private const val RESIZE_SHORT = 256
        private const val INPUT_SIZE = 224
        private val LABEL_ANGLES = intArrayOf(0, 90, 180, 270)
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        internal fun orientationForAngle(angle: Int, confidence: Float): TextOrientation {
            if (confidence < MIN_VERTICAL_CONFIDENCE) return TextOrientation.UNKNOWN
            val normalized = ((angle % 360) + 360) % 360
            return when (normalized) {
                90, 270 -> TextOrientation.VERTICAL_RTL
                else -> TextOrientation.UNKNOWN
            }
        }

        internal fun softmax(scores: FloatArray): FloatArray {
            if (scores.isEmpty()) return FloatArray(0)
            val max = scores.maxOrNull() ?: 0f
            val exps = FloatArray(scores.size)
            var sum = 0.0
            for (i in scores.indices) {
                val v = exp((scores[i] - max).toDouble())
                exps[i] = v.toFloat()
                sum += v
            }
            if (sum <= 0.0 || !sum.isFinite()) return FloatArray(scores.size) { 1f / scores.size }
            return FloatArray(scores.size) { i -> (exps[i] / sum).toFloat() }
        }

        internal fun extractScores(value: Any?): FloatArray {
            return when (value) {
                is FloatArray -> value
                is Array<*> -> {
                    if (value.isEmpty()) {
                        FloatArray(0)
                    } else {
                        extractScores(value[0])
                    }
                }
                else -> FloatArray(0)
            }
        }
    }
}
