package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.gameocr.app.util.CpuThreadPolicy
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runtime adapter for the Apache-2.0 comic bubble RT-DETR-v2 INT8 model.
 *
 * The model is bundled in the APK and pinned by size plus SHA-256. Source:
 * https://huggingface.co/ogkalu/comic-text-and-bubble-detector
 */
internal object MangaBubbleDetectionDebugEngine {
    data class Output(
        val detections: List<MangaBubbleDetectionPostprocessor.Detection>,
        val textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        val sessionPrepareMs: Long,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val postprocessMs: Long,
        val totalMs: Long,
    )

    private val inferenceLock = Mutex()
    private var session: OrtSession? = null
    private var loadedModelStamp: Pair<String, Long>? = null
    fun modelFile(context: Context): File? = BubbleDetectionModelProvider.prepare(context)

    fun isInstalled(context: Context): Boolean = modelFile(context) != null

    suspend fun runIfInstalled(context: Context, bitmap: Bitmap): Output? {
        val model = modelFile(context) ?: return null
        return inferenceLock.withLock {
            withContext(Dispatchers.Default) {
                runInference(model, bitmap)
            }
        }
    }

    private fun runInference(model: File, bitmap: Bitmap): Output {
        val totalStartedAt = SystemClock.elapsedRealtime()
        val sessionStartedAt = SystemClock.elapsedRealtime()
        val activeSession = ensureSession(model)
        val sessionPrepareMs = SystemClock.elapsedRealtime() - sessionStartedAt

        val preprocessStartedAt = SystemClock.elapsedRealtime()
        val inputBuffer = allocateFloatBuffer(3 * INPUT_SIZE * INPUT_SIZE)
        bitmapToResizedRgbNchw(bitmap, inputBuffer)
        val originalSizeBuffer = allocateLongBuffer(2).apply {
            put(bitmap.width.toLong())
            put(bitmap.height.toLong())
            rewind()
        }
        val preprocessMs = SystemClock.elapsedRealtime() - preprocessStartedAt

        val environment = OrtEnvironment.getEnvironment()
        val imageTensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        val originalSizeTensor = OnnxTensor.createTensor(
            environment,
            originalSizeBuffer,
            longArrayOf(1, 2),
        )
        val inferenceStartedAt = SystemClock.elapsedRealtime()
        val raw = imageTensor.use { image ->
            originalSizeTensor.use { originalSize ->
                activeSession.run(
                    mapOf(
                        pickName(activeSession.inputNames, IMAGE_INPUT_NAME) to image,
                        pickName(activeSession.inputNames, SIZE_INPUT_NAME) to originalSize,
                    )
                ).use { result ->
                    val labels = readLabels(result.get(0).value)
                    val boxes = readBoxes(result.get(1).value)
                    val scores = readScores(result.get(2).value)
                    RawOutput(labels = labels, boxes = boxes, scores = scores)
                }
            }
        }
        val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
        val postprocessStartedAt = SystemClock.elapsedRealtime()
        val allDetections = MangaBubbleDetectionPostprocessor.processAll(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            labels = raw.labels,
            boxes = raw.boxes,
            scores = raw.scores,
        )
        val detections = allDetections.filter { detection ->
            detection.kind == MangaBubbleDetectionPostprocessor.Kind.BUBBLE
        }
        val textDetections = allDetections.filter { detection ->
            detection.kind != MangaBubbleDetectionPostprocessor.Kind.BUBBLE
        }
        val postprocessMs = SystemClock.elapsedRealtime() - postprocessStartedAt
        return Output(
            detections = detections,
            textDetections = textDetections,
            sessionPrepareMs = sessionPrepareMs,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            postprocessMs = postprocessMs,
            totalMs = SystemClock.elapsedRealtime() - totalStartedAt,
        )
    }

    private fun ensureSession(model: File): OrtSession {
        val stamp = model.absolutePath to model.lastModified()
        session?.takeIf { loadedModelStamp == stamp }?.let { return it }
        runCatching { session?.close() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(
                CpuThreadPolicy.select(CpuThreadPolicy.availableProcessors())
            )
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        }
        return try {
            OrtEnvironment.getEnvironment().createSession(model.absolutePath, options).also {
                session = it
                loadedModelStamp = stamp
            }
        } finally {
            options.close()
        }
    }

    private fun bitmapToResizedRgbNchw(
        bitmap: Bitmap,
        output: FloatBuffer,
    ) {
        val resized = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
        try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val planeSize = INPUT_SIZE * INPUT_SIZE
            output.clear()
            pixels.forEachIndexed { index, color ->
                output.put(index, Color.red(color) / 255f)
                output.put(planeSize + index, Color.green(color) / 255f)
                output.put(planeSize * 2 + index, Color.blue(color) / 255f)
            }
            output.position(planeSize * 3)
            output.flip()
        } finally {
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        }
    }

    private fun pickName(names: Set<String>, preferred: String): String =
        preferred.takeIf(names::contains) ?: names.first()

    private fun readLabels(value: Any): LongArray = when (value) {
        is LongArray -> value
        is Array<*> -> value.firstOrNull() as? LongArray
        else -> null
    } ?: error("Unexpected RT-DETR labels output: ${value.javaClass.name}")

    private fun readScores(value: Any): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.firstOrNull() as? FloatArray
        else -> null
    } ?: error("Unexpected RT-DETR scores output: ${value.javaClass.name}")

    private fun readBoxes(value: Any): Array<FloatArray> {
        if (value !is Array<*>) {
            error("Unexpected RT-DETR boxes output: ${value.javaClass.name}")
        }
        val first = value.firstOrNull()
        val rows = if (first is Array<*>) first else value
        return rows.map { row ->
            row as? FloatArray
                ?: error("Unexpected RT-DETR box row: ${row?.javaClass?.name}")
        }.toTypedArray()
    }

    private fun allocateFloatBuffer(elements: Int): FloatBuffer =
        ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun allocateLongBuffer(elements: Int): LongBuffer =
        ByteBuffer.allocateDirect(elements * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()

    private data class RawOutput(
        val labels: LongArray,
        val boxes: Array<FloatArray>,
        val scores: FloatArray,
    )

    private const val INPUT_SIZE = 640
    private const val IMAGE_INPUT_NAME = "images"
    private const val SIZE_INPUT_NAME = "orig_target_sizes"
}
