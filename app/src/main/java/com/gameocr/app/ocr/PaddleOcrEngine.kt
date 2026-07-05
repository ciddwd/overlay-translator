package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.PaddleModelVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal enum class PaddleCropOrientation {
    ORIGINAL,
    ROTATED_90,
}

internal data class PaddleRecognitionCandidate(
    val text: String,
    val score: Float,
    val orientation: PaddleCropOrientation,
    val targetWidth: Int = 0,
    val ctcSteps: Int = 0,
    val numClasses: Int = 0,
    val nonBlankSteps: Int = 0,
    val outOfRangeIdx: Int = 0,
    val emittedChars: Int = text.count { !it.isWhitespace() },
    val elapsedMs: Long = 0L,
)

internal data class PaddleProbMapStats(
    val width: Int,
    val height: Int,
    val min: Float,
    val max: Float,
    val mean: Float,
    val aboveBinThresh: Int,
    val aboveScoreThresh: Int,
) {
    val pixels: Int get() = width * height
    val aboveBinRatio: Float get() = if (pixels == 0) 0f else aboveBinThresh.toFloat() / pixels
    val aboveScoreRatio: Float get() = if (pixels == 0) 0f else aboveScoreThresh.toFloat() / pixels

    fun toLogString(): String =
        "probMap=${width}x$height min=${min.fmt3()} max=${max.fmt3()} mean=${mean.fmt3()} " +
            "aboveBin=$aboveBinThresh/${aboveBinRatio.fmt3()} aboveScore=$aboveScoreThresh/${aboveScoreRatio.fmt3()}"
}

internal data class PaddleLogTextStats(
    val length: Int,
    val nonWhitespace: Int,
    val cjk: Int,
    val ascii: Int,
    val punctuation: Int,
    val whitespace: Int,
    val sample: String,
) {
    fun toLogString(): String =
        "len=$length nonWs=$nonWhitespace cjk=$cjk ascii=$ascii punct=$punctuation ws=$whitespace sample=\"$sample\""
}

internal fun paddleProbMapStats(
    probMap: Array<FloatArray>,
    binThresh: Float,
    scoreThresh: Float,
): PaddleProbMapStats {
    val height = probMap.size
    val width = probMap.firstOrNull()?.size ?: 0
    if (height == 0 || width == 0) {
        return PaddleProbMapStats(width = width, height = height, min = 0f, max = 0f, mean = 0f, aboveBinThresh = 0, aboveScoreThresh = 0)
    }
    var min = Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    var sum = 0.0
    var count = 0
    var aboveBin = 0
    var aboveScore = 0
    for (row in probMap) {
        for (value in row) {
            if (value < min) min = value
            if (value > max) max = value
            if (value >= binThresh) aboveBin++
            if (value >= scoreThresh) aboveScore++
            sum += value
            count++
        }
    }
    return PaddleProbMapStats(
        width = width,
        height = height,
        min = min,
        max = max,
        mean = if (count == 0) 0f else (sum / count).toFloat(),
        aboveBinThresh = aboveBin,
        aboveScoreThresh = aboveScore,
    )
}

internal fun paddleLogTextStats(text: String, maxSampleChars: Int = 160): PaddleLogTextStats {
    var cjk = 0
    var ascii = 0
    var punctuation = 0
    var whitespace = 0
    text.forEach { ch ->
        when {
            ch.isWhitespace() -> whitespace++
            ch.isCjkForOcrLog() -> cjk++
            ch.code in 0x20..0x7E -> ascii++
        }
        if (ch.isPunctuationForOcrLog()) punctuation++
    }
    return PaddleLogTextStats(
        length = text.length,
        nonWhitespace = text.length - whitespace,
        cjk = cjk,
        ascii = ascii,
        punctuation = punctuation,
        whitespace = whitespace,
        sample = text.forPaddleOcrLog(maxSampleChars),
    )
}

internal fun String.forPaddleOcrLog(maxChars: Int = 160): String {
    val normalized = replace("\r", "\\r").replace("\n", "\\n")
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "..."
}

private fun Char.isCjkForOcrLog(): Boolean =
    code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF ||
        code in 0x3040..0x30FF ||
        code in 0xAC00..0xD7AF

private fun Char.isPunctuationForOcrLog(): Boolean {
    val type = Character.getType(this).toInt()
    return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
        type == Character.DASH_PUNCTUATION.toInt() ||
        type == Character.START_PUNCTUATION.toInt() ||
        type == Character.END_PUNCTUATION.toInt() ||
        type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
        type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
        type == Character.OTHER_PUNCTUATION.toInt()
}

private fun Float.fmt3(): String = String.format(Locale.US, "%.3f", this)

private fun Rect.toPaddleLogString(): String =
    "($left,$top,$right,$bottom ${width()}x${height()})"

private fun DBPostprocessor.Quad.toPaddleLogString(): String {
    val angle = kotlin.math.atan2(p1.y - p0.y, p1.x - p0.x) * 180f / kotlin.math.PI.toFloat()
    val bounds = axisAlignedBounds()
    return "center=(${centerX.fmt3()},${centerY.fmt3()}) size=${width.fmt3()}x${height.fmt3()} " +
        "angle=${angle.fmt3()} bounds=(${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]}) " +
        "p0=(${p0.x.fmt3()},${p0.y.fmt3()}) p1=(${p1.x.fmt3()},${p1.y.fmt3()}) " +
        "p2=(${p2.x.fmt3()},${p2.y.fmt3()}) p3=(${p3.x.fmt3()},${p3.y.fmt3()})"
}

private fun PaddleRecognitionCandidate.toPaddleLogString(): String =
    "orientation=${orientation.name} score=${score.fmt3()} quality=${paddleRecognitionQuality(this).fmt3()} " +
        "targetW=$targetWidth elapsed=${elapsedMs}ms ctcSteps=$ctcSteps classes=$numClasses " +
        "nonBlank=$nonBlankSteps emitted=$emittedChars outOfRange=$outOfRangeIdx " +
        "textStats=${paddleLogTextStats(text).toLogString()} text='${text.forPaddleOcrLog()}'"

internal fun choosePaddleRecognitionCandidate(
    candidates: List<PaddleRecognitionCandidate>,
): PaddleRecognitionCandidate? = candidates.maxWithOrNull(
    compareBy<PaddleRecognitionCandidate> { paddleRecognitionQuality(it) }
        .thenBy { it.text.count { ch -> !ch.isWhitespace() } }
        .thenBy { it.score }
        .thenBy { if (it.orientation == PaddleCropOrientation.ORIGINAL) 1 else 0 }
)

internal fun paddleRecognitionQuality(candidate: PaddleRecognitionCandidate): Float {
    val nonSpaceChars = candidate.text.count { !it.isWhitespace() }
    if (nonSpaceChars == 0) return -1f
    val usefulChars = candidate.text.count { !it.isWhitespace() && it.isLetterOrDigit() }
    val usefulRatio = usefulChars.toFloat() / nonSpaceChars
    return candidate.score.coerceIn(0f, 1f) +
        nonSpaceChars.coerceAtMost(20) * 0.06f +
        usefulRatio * 0.15f
}

/**
 * PaddleOCR PP-OCRv5/v6 端侧识别。基于 ONNX Runtime Android，不需要打包 native .so。
 *
 * 支持两个模型版本（通过 [PaddleModelVersion] 切换）：
 * - V5_MOBILE: PP-OCRv5 mobile（det + rec + keys.txt）
 * - V6_TINY: PP-OCRv6 tiny（det + rec + inference.yml 内嵌字典）
 *
 * 数据流：
 * Bitmap → detect (DBNet) → boundingBox 列表 → 对每个 box 裁剪 + 仿射归一 → CRNN → CTC decode → 文本
 */
@Singleton
class PaddleOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: PaddleModelInstaller,
    private val settingsRepository: com.gameocr.app.data.SettingsRepository,
    private val logRepository: com.gameocr.app.data.LogRepository
) : OcrEngine {

    private val initLock = Mutex()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var keys: List<String> = emptyList()
    /** 当前已加载的模型版本。版本切换时 invalidate session 强制重新加载。 */
    private var loadedVersion: PaddleModelVersion? = null
    private val runCounter = AtomicLong(0L)

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        ensureReady()
        val s = settingsRepository.get()
        return withContext(Dispatchers.Default) {
            runFull(bitmap, s.dbnetProbThresh, s.dbnetBoxScoreThresh, s.dbnetUnclipRatio)
        }
    }

    suspend fun ensureReady() = initLock.withLock {
        val s = settingsRepository.get()
        val version = s.paddleModelVersion
        val initStart = System.currentTimeMillis()
        Timber.i(
            "PaddleOCR ensureReady requested version=%s loaded=%s detSession=%s recSession=%s",
            version.name,
            loadedVersion?.name ?: "null",
            detSession != null,
            recSession != null,
        )
        // 版本切换时释放旧 session，强制重新加载
        if (loadedVersion != version) {
            Timber.i("PaddleOCR switching model version from %s to %s", loadedVersion?.name ?: "null", version.name)
            runCatching { detSession?.close() }
            runCatching { recSession?.close() }
            detSession = null
            recSession = null
            loadedVersion = null
        }
        if (detSession != null && recSession != null) {
            Timber.i("PaddleOCR ensureReady reuse version=%s keys=%d", version.name, keys.size)
            return@withLock
        }
        val files = modelInstaller.checkInstalled(version)
        if (files == null) {
            Timber.w("PaddleOCR model not ready version=%s", version.name)
            throw ModelNotReadyException(context.getString(R.string.err_paddle_not_ready))
        }
        Timber.i(
            "PaddleOCR model files version=%s total=%dB det=%s(%dB) rec=%s(%dB) keys=%s(%dB)",
            version.name,
            files.totalBytes,
            files.det.absolutePath,
            files.det.length(),
            files.rec.absolutePath,
            files.rec.length(),
            files.keys.absolutePath,
            files.keys.length(),
        )
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        detSession = e.createSession(files.det.absolutePath, OrtSession.SessionOptions())
        recSession = e.createSession(files.rec.absolutePath, OrtSession.SessionOptions())
        // PP-OCRv5: keys.txt 每行一个字符，末尾可能有多余 \r
        // PP-OCRv6: 字典内嵌在 inference.yml 的 character_dict 字段
        keys = if (files.keys.name.endsWith(".yml") || files.keys.name.endsWith(".yaml")) {
            parseYamlDict(files.keys)
        } else {
            files.keys.readLines()
                .map { it.trim('\r', '\n', ' ', '\t') }
                .filter { it.isNotEmpty() }
        }
        loadedVersion = version
        Timber.i(
            "PaddleOCR ready version=%s elapsed=%dms det=%dKB rec=%dKB keys=%d firstKey='%s' lastKey='%s'",
            version.name,
            System.currentTimeMillis() - initStart,
            files.det.length() / 1024,
            files.rec.length() / 1024,
            keys.size,
            keys.firstOrNull().orEmpty().forPaddleOcrLog(40),
            keys.lastOrNull().orEmpty().forPaddleOcrLog(40),
        )
    }

    /**
     * 解析 PP-OCRv6 inference.yml 中内嵌的 character_dict。
     * YAML 格式：在 `character_dict:` 之后每行以 `  - ` 开头的都是一个字符。
     * 简单行解析，不引入 YAML 库依赖。
     */
    private fun parseYamlDict(file: File): List<String> {
        val lines = file.readLines()
        val result = mutableListOf<String>()
        var inDict = false
        for (line in lines) {
            val trimmed = line.trimEnd('\r', '\n')
            if (trimmed.trimStart() == "character_dict:") {
                inDict = true
                continue
            }
            if (inDict) {
                // YAML list item: "  - <value>"
                val match = Regex("^\\s+-\\s+(.+)$").matchEntire(trimmed)
                if (match != null) {
                    // 去掉可能的单引号/双引号包裹
                    var value = match.groupValues[1].trim()
                    if ((value.startsWith("'") && value.endsWith("'")) ||
                        (value.startsWith("\"") && value.endsWith("\""))) {
                        value = value.substring(1, value.length - 1)
                    }
                    // YAML 转义字符
                    value = value.replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\\", "\\")
                    result.add(value)
                } else if (trimmed.isNotBlank() && !trimmed.trimStart().startsWith("#")) {
                    // 遇到非 list item 的非空行，说明 character_dict 结束
                    break
                }
            }
        }
        Timber.i("Parsed YAML dict: %d characters", result.size)
        return result
    }

    private fun runFull(
        bitmap: Bitmap,
        binThresh: Float = DET_PROB_THRESH,
        scoreThresh: Float = DET_BOX_SCORE_THRESH,
        unclipRatio: Float = DET_UNCLIP_RATIO,
    ): List<TextBlock> {
        val runId = runCounter.incrementAndGet()
        val ver = loadedVersion?.name ?: "?"
        val t0 = System.currentTimeMillis()
        Timber.i(
            "PaddleOCR run#%d begin version=%s bitmap=%dx%d probThresh=%.3f boxScoreThresh=%.3f unclip=%.3f detLimit=%d recH=%d recMaxW=%d keys=%d",
            runId,
            ver,
            bitmap.width,
            bitmap.height,
            binThresh,
            scoreThresh,
            unclipRatio,
            DET_LIMIT_SIDE_LEN,
            REC_TARGET_H,
            REC_MAX_W,
            keys.size,
        )
        val quads = detectQuads(bitmap, binThresh, scoreThresh, unclipRatio, runId)
        val tDet = System.currentTimeMillis() - t0
        Timber.i(
            "PaddleOCR run#%d det done version=%s elapsed=%dms quads=%d bitmap=%dx%d",
            runId,
            ver,
            tDet,
            quads.size,
            bitmap.width,
            bitmap.height,
        )
        if (quads.isEmpty()) {
            Timber.i("PaddleOCR run#%d summary version=%s no quads total=%dms", runId, ver, System.currentTimeMillis() - t0)
            return emptyList()
        }
        val sorted = quads.sortedWith(compareBy({ it.centerY }, { it.centerX }))
        sorted.forEachIndexed { i, quad ->
            Timber.i("PaddleOCR run#%d quad[%d] %s", runId, i, quad.toPaddleLogString())
        }
        val tRecStart = System.currentTimeMillis()
        val results = sorted.mapIndexedNotNull { i, quad ->
            val text = recognizeQuad(bitmap, quad, runId, i).trim()
            val bounds = quad.axisAlignedBounds()
            val rect = Rect(
                bounds[0].coerceAtLeast(0),
                bounds[1].coerceAtLeast(0),
                bounds[2].coerceAtMost(bitmap.width),
                bounds[3].coerceAtMost(bitmap.height)
            )
            Timber.i(
                "PaddleOCR run#%d result[%d] rect=%s textStats=%s text='%s'",
                runId,
                i,
                rect.toPaddleLogString(),
                paddleLogTextStats(text).toLogString(),
                text.forPaddleOcrLog(),
            )
            if (text.isEmpty()) {
                Timber.i("PaddleOCR run#%d result[%d] dropped empty text rect=%s", runId, i, rect.toPaddleLogString())
                null
            } else {
                TextBlock(text = text, boundingBox = rect, confidence = 1f, recognizedLanguage = "auto")
            }
        }
        val tRec = System.currentTimeMillis() - tRecStart
        val tTotal = System.currentTimeMillis() - t0
        val joined = results.joinToString(" | ") { it.text.forPaddleOcrLog(80) }
        Timber.i(
            "PaddleOCR run#%d summary version=%s det=%dms rec=%dms total=%dms quads=%d kept=%d dropped=%d joined='%s'",
            runId,
            ver,
            tDet,
            tRec,
            tTotal,
            quads.size,
            results.size,
            quads.size - results.size,
            joined,
        )
        logRepository.info(
            com.gameocr.app.data.LogRepository.Category.OCR,
            "[%s] det=%dms rec=%dms total=%dms %d quads→%d results %dx%d".format(
                ver, tDet, tRec, tTotal, quads.size, results.size, bitmap.width, bitmap.height
            )
        )
        return results
    }

    /**
     * DBNet 检测：把 bitmap 缩到 [DET_LIMIT_SIDE_LEN] 输入，输出 prob map → DBPostprocessor 提取旋转矩形。
     *
     * `internal` 可见性：同包 [MangaOcrEngine] 复用此检测器（仅检测、不识别），把 DBNet 出框
     * 直接喂给 manga-ocr 做整气泡识别。调用前必须先 [ensureReady]——MangaOcrEngine 会负责。
     */
    internal fun detectQuads(
        bitmap: Bitmap,
        binThresh: Float = DET_PROB_THRESH,
        scoreThresh: Float = DET_BOX_SCORE_THRESH,
        unclipRatio: Float = DET_UNCLIP_RATIO,
        runId: Long? = null,
    ): List<DBPostprocessor.Quad> {
        val trace = runId?.let { "run#$it " }.orEmpty()
        val session = detSession ?: run {
            Timber.w("PaddleOCR ${trace}detect skipped: detSession=null")
            return emptyList()
        }
        val e = env ?: run {
            Timber.w("PaddleOCR ${trace}detect skipped: env=null")
            return emptyList()
        }
        val (resized, scale) = resizeKeepingAspect(bitmap, DET_LIMIT_SIDE_LEN)
        val (rW, rH) = resized.width to resized.height
        Timber.i(
            "PaddleOCR ${trace}det input src=%dx%d resized=%dx%d scaleBack=%.4f tensor=[1,3,%d,%d] probThresh=%.3f boxScoreThresh=%.3f unclip=%.3f",
            bitmap.width,
            bitmap.height,
            rW,
            rH,
            scale,
            rH,
            rW,
            binThresh,
            scoreThresh,
            unclipRatio,
        )

        try {
            val inputTensor = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(bitmapToNCHW(resized, DET_MEAN, DET_STD)),
                longArrayOf(1, 3, rH.toLong(), rW.toLong())
            )
            return inputTensor.use { tensor ->
                val inferStart = System.currentTimeMillis()
                session.run(mapOf(session.inputNames.first() to tensor)).use { res ->
                    val inferMs = System.currentTimeMillis() - inferStart
                    val out = res.get(0).value as Array<Array<Array<FloatArray>>>
                    val probMap = out[0][0]
                    val stats = paddleProbMapStats(probMap, binThresh, scoreThresh)
                    val postStart = System.currentTimeMillis()
                    val quads = DBPostprocessor.extractQuads(
                        probMap = probMap,
                        scale = scale,
                        binThresh = binThresh,
                        scoreThresh = scoreThresh,
                        unclipRatio = unclipRatio
                    )
                    val postMs = System.currentTimeMillis() - postStart
                    Timber.i(
                        "PaddleOCR ${trace}det output %s infer=%dms post=%dms quads=%d",
                        stats.toLogString(),
                        inferMs,
                        postMs,
                        quads.size,
                    )
                    quads
                }
            }
        } finally {
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        }
    }

    /**
     * CRNN 识别：用 [Matrix.setPolyToPoly] 把 Quad 4 点透视矫正到水平矩形 → resize 到 H=48 → 跑 onnx → CTC decode。
     */
    private fun recognizeQuad(src: Bitmap, quad: DBPostprocessor.Quad, runId: Long, boxIndex: Int): String {
        val session = recSession ?: run {
            Timber.w("PaddleOCR run#%d rec[%d] skipped: recSession=null", runId, boxIndex)
            return ""
        }
        val e = env ?: run {
            Timber.w("PaddleOCR run#%d rec[%d] skipped: env=null", runId, boxIndex)
            return ""
        }
        val crop = warpCropQuad(src, quad) ?: run {
            Timber.w("PaddleOCR run#%d rec[%d] warp failed quad=%s", runId, boxIndex, quad.toPaddleLogString())
            return ""
        }
        return try {
            Timber.i(
                "PaddleOCR run#%d rec[%d] crop=%dx%d tryRotated=%s quad=%s",
                runId,
                boxIndex,
                crop.width,
                crop.height,
                shouldTryRotatedCrop(crop),
                quad.toPaddleLogString(),
            )
            val candidates = mutableListOf<PaddleRecognitionCandidate>()
            candidates += recognizeCrop(crop, PaddleCropOrientation.ORIGINAL, session, e, runId, boxIndex)
            if (shouldTryRotatedCrop(crop)) {
                val rotated = rotateCrop90(crop)
                if (rotated != null) {
                    try {
                        Timber.i(
                            "PaddleOCR run#%d rec[%d] rotatedCrop=%dx%d",
                            runId,
                            boxIndex,
                            rotated.width,
                            rotated.height,
                        )
                        candidates += recognizeCrop(rotated, PaddleCropOrientation.ROTATED_90, session, e, runId, boxIndex)
                    } finally {
                        rotated.recycle()
                    }
                } else {
                    Timber.w("PaddleOCR run#%d rec[%d] rotateCrop90 failed", runId, boxIndex)
                }
            }

            val best = choosePaddleRecognitionCandidate(candidates)
            Timber.i(
                "PaddleOCR run#%d rec[%d] selected=%s candidates=%s",
                runId,
                boxIndex,
                best?.toPaddleLogString() ?: "NONE",
                candidates.joinToString(" || ") { it.toPaddleLogString() },
            )
            best?.text.orEmpty()
        } finally {
            crop.recycle()
        }
    }

    private fun recognizeCrop(
        crop: Bitmap,
        orientation: PaddleCropOrientation,
        session: OrtSession,
        e: OrtEnvironment,
        runId: Long,
        boxIndex: Int,
    ): PaddleRecognitionCandidate {
        val startMs = System.currentTimeMillis()
        val ratio = REC_TARGET_H.toFloat() / crop.height
        val targetW = (crop.width * ratio).toInt().coerceAtLeast(8).coerceAtMost(REC_MAX_W)
        val resized = if (crop.width == targetW && crop.height == REC_TARGET_H) {
            crop
        } else {
            Bitmap.createScaledBitmap(crop, targetW, REC_TARGET_H, true)
        }

        return try {
            val tensor = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(bitmapToNCHW(resized, REC_MEAN, REC_STD)),
                longArrayOf(1, 3, REC_TARGET_H.toLong(), targetW.toLong())
            )
            tensor.use { t ->
                session.run(mapOf(session.inputNames.first() to t)).use { res ->
                    val out = res.get(0).value as Array<Array<FloatArray>>
                    val decoded = ctcDecodeWithStats(out[0])
                    val elapsedMs = System.currentTimeMillis() - startMs
                    val candidate = PaddleRecognitionCandidate(
                        text = decoded.text,
                        score = decoded.score,
                        orientation = orientation,
                        targetWidth = targetW,
                        ctcSteps = decoded.steps,
                        numClasses = decoded.numClasses,
                        nonBlankSteps = decoded.nonBlankSteps,
                        outOfRangeIdx = decoded.outOfRangeIdx,
                        emittedChars = decoded.emittedChars,
                        elapsedMs = elapsedMs,
                    )
                    Timber.i(
                        "PaddleOCR run#%d rec[%d] candidate orientation=%s crop=%dx%d input=%dx%d elapsed=%dms score=%.3f quality=%.3f ctcSteps=%d classes=%d nonBlank=%d emitted=%d outOfRange=%d textStats=%s text='%s'",
                        runId,
                        boxIndex,
                        orientation.name,
                        crop.width,
                        crop.height,
                        targetW,
                        REC_TARGET_H,
                        elapsedMs,
                        candidate.score,
                        paddleRecognitionQuality(candidate),
                        candidate.ctcSteps,
                        candidate.numClasses,
                        candidate.nonBlankSteps,
                        candidate.emittedChars,
                        candidate.outOfRangeIdx,
                        paddleLogTextStats(candidate.text).toLogString(),
                        candidate.text.forPaddleOcrLog(),
                    )
                    candidate
                }
            }
        } finally {
            if (resized !== crop && !resized.isRecycled) resized.recycle()
        }
    }

    /**
     * 用 [Matrix.setPolyToPoly] 实现 cv2.warpPerspective 等价：把 4 点 quad 映射到水平
     * 矩形（cropW x cropH）。竖排（高 > 宽 1.5 倍）裁出后旋转 90 度，让 rec 模型按横排理解。
     */
    private fun warpCropQuad(src: Bitmap, quad: DBPostprocessor.Quad): Bitmap? {
        val w1 = hypotF(quad.p1.x - quad.p0.x, quad.p1.y - quad.p0.y)
        val w2 = hypotF(quad.p2.x - quad.p3.x, quad.p2.y - quad.p3.y)
        val h1 = hypotF(quad.p3.x - quad.p0.x, quad.p3.y - quad.p0.y)
        val h2 = hypotF(quad.p2.x - quad.p1.x, quad.p2.y - quad.p1.y)
        val cropW = maxOf(w1, w2).toInt().coerceAtLeast(2)
        val cropH = maxOf(h1, h2).toInt().coerceAtLeast(2)
        if (cropW > 4096 || cropH > 4096) return null

        val srcPts = floatArrayOf(
            quad.p0.x, quad.p0.y,
            quad.p1.x, quad.p1.y,
            quad.p2.x, quad.p2.y,
            quad.p3.x, quad.p3.y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            cropW.toFloat(), 0f,
            cropW.toFloat(), cropH.toFloat(),
            0f, cropH.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return null

        val out = runCatching {
            Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        Canvas(out).drawBitmap(src, matrix, WARP_PAINT)

        return out
    }

    private fun shouldTryRotatedCrop(crop: Bitmap): Boolean =
        crop.height > crop.width * VERTICAL_CROP_RATIO

    private fun rotateCrop90(crop: Bitmap): Bitmap? {
        val rotateMatrix = Matrix().apply { postRotate(90f) }
        return runCatching {
            Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, rotateMatrix, true)
        }.getOrNull()
    }

    private fun hypotF(dx: Float, dy: Float): Float = kotlin.math.hypot(dx, dy)

    /** CTC greedy decode：每一步取最大概率，去重 + 去 blank（idx=0）。 */
    private fun ctcDecodeWithStats(logits: Array<FloatArray>): CtcDecodeResult {
        val sb = StringBuilder()
        var prev = -1
        var nonBlankSteps = 0
        var outOfRangeIdx = 0
        var emittedScoreTotal = 0f
        var emittedChars = 0
        val numClasses = logits.firstOrNull()?.size ?: 0
        for (step in logits) {
            var best = 0
            var bestVal = step[0]
            for (i in 1 until step.size) {
                if (step[i] > bestVal) { bestVal = step[i]; best = i }
            }
            if (best != 0 && best != prev) {
                nonBlankSteps++
                val keyIdx = best - 1
                when {
                    keyIdx in keys.indices -> {
                        sb.append(keys[keyIdx])
                        emittedScoreTotal += bestVal
                        emittedChars++
                    }
                    keyIdx == keys.size -> {
                        sb.append(' ')
                        emittedScoreTotal += bestVal
                        emittedChars++
                    }
                    else -> outOfRangeIdx++
                }
            }
            prev = best
        }
        if (sb.isEmpty()) {
            Timber.w("CTC empty: T=%d C=%d keys=%d nonBlank=%d outOfRange=%d",
                logits.size, numClasses, keys.size, nonBlankSteps, outOfRangeIdx)
        }
        return CtcDecodeResult(
            text = sb.toString(),
            score = if (emittedChars == 0) 0f else emittedScoreTotal / emittedChars,
            steps = logits.size,
            numClasses = numClasses,
            nonBlankSteps = nonBlankSteps,
            outOfRangeIdx = outOfRangeIdx,
            emittedChars = emittedChars,
        )
    }

    private data class CtcDecodeResult(
        val text: String,
        val score: Float,
        val steps: Int,
        val numClasses: Int,
        val nonBlankSteps: Int,
        val outOfRangeIdx: Int,
        val emittedChars: Int,
    )

    /** 等比缩放到最长边 = [limitSideLen]，且边长是 32 的倍数（DBNet 要求）。返回 (resized, scaleBackFactor)。 */
    private fun resizeKeepingAspect(bitmap: Bitmap, limitSideLen: Int): Pair<Bitmap, Float> {
        val w = bitmap.width; val h = bitmap.height
        val ratio = limitSideLen.toFloat() / maxOf(w, h)
        var newW = (w * ratio).toInt()
        var newH = (h * ratio).toInt()
        newW = ((newW + 31) / 32 * 32).coerceAtLeast(32)
        newH = ((newH + 31) / 32 * 32).coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        return scaled to (1f / ratio)
    }

    /**
     * Bitmap → CHW float[]，按 mean / std 归一化。
     *
     * PaddleOCR 训练时用 OpenCV 读图（默认 BGR），推理也必须 BGR 顺序，否则
     * channel 错位导致识别全错（输出 blank 或乱码英文）。Android Bitmap 默认 ARGB
     * 即 RGB 顺序，必须在这里 swap。
     */
    private fun bitmapToNCHW(bitmap: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
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
            arr[i] = (b - mean[0]) / std[0]
            arr[planeSize + i] = (g - mean[1]) / std[1]
            arr[2 * planeSize + i] = (r - mean[2]) / std[2]
        }
        return arr
    }

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        runCatching { env?.close() }
        detSession = null
        recSession = null
        env = null
    }

    companion object {
        private const val DET_LIMIT_SIDE_LEN = 2400
        private const val DET_PROB_THRESH = 0.3f
        private const val MIN_BOX_AREA = 16
        private const val DET_BOX_SCORE_THRESH = 0.6f
        private const val DET_UNCLIP_RATIO = 1.6f
        private const val VERTICAL_CROP_RATIO = 1.5f
        private const val REC_TARGET_H = 48
        private const val REC_MAX_W = 480
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val WARP_PAINT = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
    }
}
