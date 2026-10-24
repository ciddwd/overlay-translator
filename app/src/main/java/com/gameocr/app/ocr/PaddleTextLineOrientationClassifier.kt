package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.gameocr.app.R
import com.gameocr.app.util.CpuThreadPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

internal data class TextLineOrientationVote(val angle: Int, val confidence: Float)

/** Bitmap-clamped line box; plain ints so selection runs without android.graphics. */
internal data class TextLineCropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * PaddleOCR PP-LCNet text-line orientation classifier.
 *
 * The model is a two-class 0/180 classifier for cropped text-line images. This
 * app uses it only after a first OCR pass has produced boxes; a strong 180
 * majority triggers one OCR rerun on a 180-degree rotated frame.
 *
 * Inputs follow the official pipeline: one detected line per crop (the member source boxes, not
 * a merged paragraph), cropped exactly at the detector box, which already carries the unclip
 * margin, then resized to 160x80.
 */
@Singleton
class PaddleTextLineOrientationClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: OrientationModelInstaller,
) {

    private val initLock = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    suspend fun classifyFromBlocks(bitmap: Bitmap, blocks: List<TextBlock>): OrientationResult? {
        if (blocks.isEmpty()) return null
        ensureReady()
        return withContext(Dispatchers.Default) {
            val votes = mutableListOf<TextLineOrientationVote>()
            val candidates = selectLineCrops(
                lineBoxes = blocks.flatMap { block ->
                    block.sourceBoxesOrBoundingBox().map { TextLineCropBounds(it.left, it.top, it.right, it.bottom) }
                },
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
            for (bounds in candidates) {
                classifyLine(bitmap, bounds)?.let { votes += it }
            }
            val result = combineLineVotes(votes)
            if (result != null) {
                val message =
                    "[orient-textline] angle=${result.rawAngle} conf=${"%.2f".format(result.confidence)} votes=${votes.size}"
                Timber.i(message)
            } else {
                Timber.i("[orient-textline] inconclusive candidates=%d votes=%s", candidates.size,
                    votes.joinToString { "${it.angle}:${"%.2f".format(it.confidence)}" })
            }
            result
        }
    }

    private suspend fun ensureReady() = initLock.withLock {
        if (session != null) return@withLock
        val textLineModel = installer.checkInstalled()?.textLine
            ?: throw ModelNotReadyException(context.getString(R.string.err_orientation_model_not_ready))
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val availableProcessors = CpuThreadPolicy.availableProcessors()
        val threads = CpuThreadPolicy.select(availableProcessors)
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        session = e.createSession(textLineModel.absolutePath, options)
        Timber.i(
            "Paddle text-line orientation ready: %dKB availableProcessors=%d ortThreads=%d",
            textLineModel.length() / 1024,
            availableProcessors,
            threads,
        )
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        env = null
    }

    private fun classifyLine(bitmap: Bitmap, bounds: TextLineCropBounds): TextLineOrientationVote? {
        val crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
        val resized = Bitmap.createScaledBitmap(crop, INPUT_W, INPUT_H, true)
        return try {
            val e = env ?: return null
            val s = session ?: return null
            val tensor = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(bitmapToRgbNchw(resized)),
                longArrayOf(1, 3, INPUT_H.toLong(), INPUT_W.toLong()),
            )
            tensor.use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { result ->
                    val probabilities = PaddleDocOriClassifier.probabilitiesFromScores(
                        PaddleDocOriClassifier.extractScores(result.get(0).value),
                    )
                    if (probabilities.size != 2) return null
                    val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: return null
                    TextLineOrientationVote(angle = angleForClass(bestIndex), confidence = probabilities[bestIndex])
                }
            }
        } finally {
            // createBitmap/createScaledBitmap may return their input unchanged.
            if (resized !== crop && resized !== bitmap) resized.recycle()
            if (crop !== bitmap) crop.recycle()
        }
    }

    private fun bitmapToRgbNchw(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val planeSize = bitmap.width * bitmap.height
        val out = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - MEAN[0]) / STD[0]
            out[planeSize + i] = (g - MEAN[1]) / STD[1]
            out[2 * planeSize + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    companion object {
        const val SOURCE = "paddle-textline-ori"
        internal const val MIN_CONFIDENCE = 0.85f
        internal const val MIN_ANGLE_180_RATIO = 0.6f
        internal const val MIN_ANGLE_180_VOTES = 2
        internal const val MAX_LINES = 8
        private const val INPUT_H = 80
        private const val INPUT_W = 160
        internal const val MIN_CROP_SIDE = 8
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        internal fun angleForClass(classId: Int): Int = if (classId == 1) 180 else 0

        /** Largest distinct horizontal line boxes, clamped to the bitmap and never padded. */
        internal fun selectLineCrops(
            lineBoxes: List<TextLineCropBounds>,
            imageWidth: Int,
            imageHeight: Int,
        ): List<TextLineCropBounds> = lineBoxes
            .asSequence()
            .map { box ->
                TextLineCropBounds(
                    box.left.coerceIn(0, imageWidth),
                    box.top.coerceIn(0, imageHeight),
                    box.right.coerceIn(0, imageWidth),
                    box.bottom.coerceIn(0, imageHeight),
                )
            }
            .filter { it.width >= MIN_CROP_SIDE && it.height >= MIN_CROP_SIDE }
            .filter { it.width >= it.height * 1.5f }
            .distinct()
            .sortedByDescending { it.width.toLong() * it.height }
            .take(MAX_LINES)
            .toList()

        internal fun combineLineVotes(votes: List<TextLineOrientationVote>): OrientationResult? {
            val confident = votes.filter {
                (it.angle == 0 || it.angle == 180) && it.confidence in MIN_CONFIDENCE..1f
            }
            if (confident.isEmpty()) return null
            val angle180 = confident.filter { it.angle == 180 }
            val angle0 = confident.filter { it.angle == 0 }
            val total = confident.size.toFloat()
            return when {
                angle180.size >= MIN_ANGLE_180_VOTES && angle180.size / total >= MIN_ANGLE_180_RATIO -> OrientationResult(
                    orientation = TextOrientation.HORIZONTAL_LTR,
                    confidence = angle180.map { it.confidence }.average().toFloat(),
                    rawAngle = 180,
                    source = SOURCE,
                )
                angle0.size > angle180.size -> OrientationResult(
                    orientation = TextOrientation.HORIZONTAL_LTR,
                    confidence = angle0.map { it.confidence }.average().toFloat(),
                    rawAngle = 0,
                    source = SOURCE,
                )
                else -> null
            }
        }
    }
}
