package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.dbnetUnclipRatioFor
import com.gameocr.app.util.CpuThreadPolicy
import com.gameocr.app.util.InferenceTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext

/**
 * manga-ocr 端侧 OCR 引擎（[OcrEngineKind.MANGA_OCR_JA]）。
 *
 * **流水线**：
 * 1. 复用 [PaddleOcrEngine.detectQuads] 跑 DBNet 出文本行级 quads
 * 2. 转轴对齐 rect → [BubbleClusterer] 聚成「漫画气泡」级矩形
 * 3. 每个气泡 [Bitmap.createBitmap] 裁出 crop → ViT encoder (224×224) → 自回归 greedy decoder
 * 4. 输出 [TextBlock]（每气泡一段，[recognizedLanguage] = "ja"）
 *
 * **不变量**：
 * - 传入 bitmap 是 raw 的（CaptureService 已按 [OcrEngineKind.needsRawBitmap] 跳过 invert/binarize），
 *   只可能 upscale 过。坐标除 2 的缩回逻辑在 CaptureService 端统一处理。
 * - 不走 `warpCropQuad` 透视矫正——manga-ocr 训练时见的是矩形气泡 crop，不是窄条
 * - encoder hidden state 一次跑，复用给所有 decoder 步骤；input_ids 每步重建（长度+1）
 * - 每生成一个 token 检查 [coroutineContext.ensureActive]，用户取消时立刻退出 generate loop
 *
 * **模型来源**：l0wgear/manga-ocr-2025-onnx（基于 kha-white 训练，jzhang533 2025 改进版，
 * Optimum 导出 ONNX）。encoder hidden dim **192**（ViT-Small），decoder vocab **6144**。
 *
 * Phase 0 PoC 验证：8 张日漫页 5 分制均分 4.19 vs PPOCRv5 mobile 1.25，Δ=+2.94。
 * 单气泡 CPU 推理 30~300ms（远低于原估 1.5s），FP32 直接搬已可用，无需量化 / KV cache。
 */
@Singleton
class MangaOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paddle: PaddleOcrEngine,
    private val installer: MangaOcrModelInstaller,
    private val settingsRepository: com.gameocr.app.data.SettingsRepository
) : OcrEngine {

    private val initLock = Mutex()
    private var env: OrtEnvironment? = null
    private var encSession: OrtSession? = null
    private var decSession: OrtSession? = null
    private var id2tok: Array<String> = emptyArray()

    // 启动时自适应解析（不同 Optimum 版本输入/输出名可能不一致）
    private var encOutputName: String = "last_hidden_state"
    private var decInputIdsName: String = "input_ids"
    private var decHiddenName: String = "encoder_hidden_states"
    private val availableProcessors by lazy { CpuThreadPolicy.availableProcessors() }
    private val ortThreads by lazy { CpuThreadPolicy.select(availableProcessors) }

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val startedAt = SystemClock.elapsedRealtime()
        val mangaReadyStartedAt = SystemClock.elapsedRealtime()
        ensureReady()
        val mangaReadyMs = InferenceTiming.elapsedMs(mangaReadyStartedAt, SystemClock.elapsedRealtime())
        // DBNet 也得就绪。Paddle 和 manga-ocr 都未就绪时分别抛各自的 ModelNotReadyException
        val paddleReadyStartedAt = SystemClock.elapsedRealtime()
        paddle.ensureReady()
        val paddleReadyMs = InferenceTiming.elapsedMs(paddleReadyStartedAt, SystemClock.elapsedRealtime())
        val s = settingsRepository.get()
        val results = withContext(Dispatchers.Default) { runFull(bitmap, s) }
        Timber.tag(PERF_TAG).i(
            "recognize totalMs=%d mangaReadyMs=%d paddleReadyMs=%d bitmap=%dx%d blocks=%d ortThreads=%d",
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            mangaReadyMs,
            paddleReadyMs,
            bitmap.width,
            bitmap.height,
            results.size,
            ortThreads,
        )
        return results
    }

    suspend fun ensureReady() = initLock.withLock {
        if (encSession != null && decSession != null) return@withLock
        val startedAt = SystemClock.elapsedRealtime()
        val files = installer.checkInstalled()
            ?: throw ModelNotReadyException(context.getString(R.string.err_manga_ocr_not_ready))
        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtSession.SessionOptions().apply {
            // 端侧推理别抢主线程；2 thread 实测够用
            setIntraOpNumThreads(ortThreads)
        }
        val enc = e.createSession(files.encoder.absolutePath, opts)
        val dec = e.createSession(files.decoder.absolutePath, opts)

        // 解析 vocab —— 行号即 token id（6144 行 BertJapaneseTokenizer 风格）
        val tokens = files.vocab.readText(Charsets.UTF_8).split('\n')
            .map { it.trim('\r', '\n', ' ', '\t') }
        if (tokens.size < 100 ||
            tokens.getOrNull(PAD_ID) != "[PAD]" ||
            tokens.getOrNull(CLS_ID) != "[CLS]" ||
            tokens.getOrNull(SEP_ID) != "[SEP]"
        ) {
            enc.close()
            dec.close()
            throw ModelNotReadyException(
                context.getString(R.string.err_manga_ocr_not_ready) + " (vocab broken)"
            )
        }
        id2tok = tokens.toTypedArray()

        // 自适应输入/输出名
        encOutputName = pickName(enc.outputNames, "last_hidden_state", "hidden_states", "output_0")
        decInputIdsName = pickName(dec.inputNames, "input_ids", "decoder_input_ids")
        decHiddenName = pickName(dec.inputNames, "encoder_hidden_states", "encoder_outputs", "last_hidden_state")

        encSession = enc
        decSession = dec
        Timber.i(
            "MangaOcr ready: enc=%dKB dec=%dKB vocab=%d encOut=%s decIn=%s decHidden=%s",
            files.encoder.length() / 1024, files.decoder.length() / 1024, tokens.size,
            encOutputName, decInputIdsName, decHiddenName
        )
        Timber.tag(PERF_TAG).i(
            "init totalMs=%d encKb=%d decKb=%d vocab=%d availableProcessors=%d ortThreads=%d",
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            files.encoder.length() / 1024,
            files.decoder.length() / 1024,
            tokens.size,
            availableProcessors,
            ortThreads,
        )
    }

    private fun pickName(names: Set<String>, vararg candidates: String): String {
        for (c in candidates) if (c in names) return c
        // 兜底用第一个名字（不应该走到这里，走到说明 Optimum 改了导出格式）
        Timber.w("MangaOcr unknown name set %s; candidates=%s; falling back to first", names, candidates.toList())
        return names.firstOrNull() ?: candidates.first()
    }

    private suspend fun runFull(bitmap: Bitmap, settings: com.gameocr.app.data.Settings): List<TextBlock> {
        val startedAt = SystemClock.elapsedRealtime()
        // 1) 复用 paddle DBNet 检测 → quads；大图额外走重叠分块，提升整屏小字召回。
        val mangaUnclipRatio = settings.dbnetUnclipRatioFor(OcrEngineKind.MANGA_OCR_JA)
        val detectStartedAt = SystemClock.elapsedRealtime()
        val quads = detectQuadsForManga(
            bitmap = bitmap,
            settings = settings,
            mangaUnclipRatio = mangaUnclipRatio
        )
        val detectMs = InferenceTiming.elapsedMs(detectStartedAt, SystemClock.elapsedRealtime())
        if (quads.isEmpty()) {
            Timber.i("MangaOcr: DBNet returned 0 quads")
            Timber.tag(PERF_TAG).i(
                "run totalMs=%d detectMs=%d clusterMs=0 bubblesMs=0 bitmap=%dx%d quads=0 bubbles=0 blocks=0",
                InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
                detectMs,
                bitmap.width,
                bitmap.height,
            )
            return emptyList()
        }

        // 2) quad → IntRect → 气泡聚类（用 BubbleClusterer.IntRect 而非 android.graphics.Rect，
        //    后者在 JVM 单测里是 Stub 不能直接构造）
        val clusterStartedAt = SystemClock.elapsedRealtime()
        val rects = quads.map { quad ->
            val b = quad.axisAlignedBounds()
            BubbleClusterer.IntRect(
                left = b[0].coerceAtLeast(0),
                top = b[1].coerceAtLeast(0),
                right = b[2].coerceAtMost(bitmap.width),
                bottom = b[3].coerceAtMost(bitmap.height)
            )
        }
        val bubbles = BubbleClusterer.cluster(
            rects = rects,
            imgW = bitmap.width,
            imgH = bitmap.height,
            pad = settings.mangaOcrCropPaddingPx.coerceIn(0, 64),
            gap = settings.bubbleClusterGap.coerceIn(0, 60)
        )
        val clusterMs = InferenceTiming.elapsedMs(clusterStartedAt, SystemClock.elapsedRealtime())
        Timber.i(
            "MangaOcr: %d quads -> %d bubbles (profile=%s maxSide=%d tiling=%s gap=%d cropPad=%d dbnet=%.2f/%.2f×%.2f)",
            quads.size,
            bubbles.size,
            settings.paddleDetectionProfile.name,
            settings.paddleDetectionProfile.maxSideLen,
            settings.paddleDetectionProfile.enableMangaTiling,
            settings.bubbleClusterGap,
            settings.mangaOcrCropPaddingPx,
            settings.dbnetProbThresh, settings.dbnetBoxScoreThresh, mangaUnclipRatio
        )

        // 3) 每气泡裁切 + 推理
        val bubblesStartedAt = SystemClock.elapsedRealtime()
        val results = mutableListOf<TextBlock>()
        for ((i, bubble) in bubbles.withIndex()) {
            coroutineContext.ensureActive()
            val crop = cropBubble(bitmap, bubble.rect) ?: continue
            val bubbleStartedAt = SystemClock.elapsedRealtime()
            val cropWidth = crop.width
            val cropHeight = crop.height
            val recognition = try {
                recognizeBubble(crop)
            } finally {
                crop.recycle()
            }
            val text = recognition.text.trim()
            val memberRects = bubble.memberIndices.mapNotNull(rects::getOrNull)
            Timber.tag(PERF_TAG).i(
                "bubble index=%d totalMs=%d encoderMs=%d decoderMs=%d decoderSteps=%d crop=%dx%d chars=%d",
                i,
                InferenceTiming.elapsedMs(bubbleStartedAt, SystemClock.elapsedRealtime()),
                recognition.encoderMs,
                recognition.decoderMs,
                recognition.decoderSteps,
                cropWidth,
                cropHeight,
                text.length,
            )
            Timber.i(
                "MangaOcr bub[%d] content=%s crop=%s cropPad=%d members=%d memberRects=%s -> '%s' (%d chars)",
                i,
                bubble.contentRect,
                bubble.rect,
                settings.mangaOcrCropPaddingPx,
                bubble.memberIndices.size,
                memberRects,
                text,
                text.length,
            )
            if (text.isEmpty()) continue
            if (shouldDropMangaOcrEdgeNoise(text, bubble.rect, bitmap.width, bitmap.height)) {
                Timber.i("MangaOcr drop edge noise bub[%d] %s -> '%s'", i, bubble.rect, text)
                continue
            }
            val sourceBoxes = memberRects.map { rect ->
                Rect(rect.left, rect.top, rect.right, rect.bottom)
            }
            val bubbleBounds = Rect(
                bubble.contentRect.left,
                bubble.contentRect.top,
                bubble.contentRect.right,
                bubble.contentRect.bottom,
            )
            results += TextBlock(
                text = text,
                boundingBox = bubbleBounds,
                confidence = 1f,
                recognizedLanguage = "ja",
                layoutOrientation = inferSourceLayoutOrientation(
                    sourceBoxes = memberRects,
                    blockBounds = bubble.contentRect,
                ),
                sourceBoxes = sourceBoxes,
            )
        }
        val bubblesMs = InferenceTiming.elapsedMs(bubblesStartedAt, SystemClock.elapsedRealtime())
        Timber.tag(PERF_TAG).i(
            "run profile=%s maxSide=%d tiling=%s cropPad=%d totalMs=%d detectMs=%d clusterMs=%d bubblesMs=%d bitmap=%dx%d quads=%d bubbles=%d blocks=%d",
            settings.paddleDetectionProfile.name,
            settings.paddleDetectionProfile.maxSideLen,
            settings.paddleDetectionProfile.enableMangaTiling,
            settings.mangaOcrCropPaddingPx,
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            detectMs,
            clusterMs,
            bubblesMs,
            bitmap.width,
            bitmap.height,
            quads.size,
            bubbles.size,
            results.size,
        )
        return results
    }

    private suspend fun detectQuadsForManga(
        bitmap: Bitmap,
        settings: com.gameocr.app.data.Settings,
        mangaUnclipRatio: Float
    ): List<DBPostprocessor.Quad> {
        val base = paddle.detectQuads(
            bitmap,
            binThresh = settings.dbnetProbThresh,
            scoreThresh = settings.dbnetBoxScoreThresh,
            unclipRatio = mangaUnclipRatio,
            profile = settings.paddleDetectionProfile,
            passLabel = "manga-full",
        )
        if (!MangaOcrTiling.shouldUseTiles(
                bitmap.width,
                bitmap.height,
                settings.paddleDetectionProfile,
            )
        ) {
            Timber.i(
                "MangaOcr tiling skipped profile=%s enabled=%s bitmap=%dx%d tileSide=%d",
                settings.paddleDetectionProfile.name,
                settings.paddleDetectionProfile.enableMangaTiling,
                bitmap.width,
                bitmap.height,
                MangaOcrTiling.DEFAULT_TILE_SIDE,
            )
            return base
        }

        val tiles = MangaOcrTiling.tilesFor(bitmap.width, bitmap.height)
        val tiled = mutableListOf<DBPostprocessor.Quad>()
        for (tile in tiles) {
            coroutineContext.ensureActive()
            val crop = Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width, tile.height)
            try {
                val tileQuads = paddle.detectQuads(
                    crop,
                    binThresh = settings.dbnetProbThresh,
                    scoreThresh = settings.dbnetBoxScoreThresh,
                    unclipRatio = mangaUnclipRatio,
                    profile = settings.paddleDetectionProfile,
                    passLabel = "manga-tile[$tile]",
                )
                tiled += tileQuads.map { it.offsetBy(tile.left.toFloat(), tile.top.toFloat()) }
            } finally {
                crop.recycle()
            }
        }

        // Tiles run at full resolution. Put them first so duplicate suppression keeps the finer box.
        val merged = dedupePaddleQuads(tiled + base)
        Timber.i(
            "MangaOcr tiled DBNet: base=%d tiled=%d merged=%d tiles=%d bitmap=%dx%d",
            base.size, tiled.size, merged.size, tiles.size, bitmap.width, bitmap.height
        )
        return merged
    }

    private fun cropBubble(src: Bitmap, rect: BubbleClusterer.IntRect): Bitmap? {
        val w = rect.width
        val h = rect.height
        if (w <= 0 || h <= 0) return null
        // 不做 rotate90 预处理。错误假设过：以为 manga-ocr 的 ViT 训练时是横排矩形需要把竖排旋转过去。
        // 实际 manga-ocr 训练数据集是 Manga109 即日漫整页，**ViT 见过的就是竖排长条 squash 到 224×224**，
        // 旋转反而把输入打成 out-of-distribution，整张图识别质量崩盘（实测 12 bubbles 9 个乱码）。
        // 保留 cropBubble 简单形式；OOD 改进留给"上采样长边到 2400"以及未来的更换检测器方向。
        return runCatching {
            Bitmap.createBitmap(src, rect.left, rect.top, w, h)
        }.getOrNull()
    }

    private data class BubbleRecognition(
        val text: String,
        val encoderMs: Long,
        val decoderMs: Long,
        val decoderSteps: Int,
    )

    private suspend fun recognizeBubble(crop: Bitmap): BubbleRecognition {
        val e = env ?: return BubbleRecognition("", 0L, 0L, 0)
        val enc = encSession ?: return BubbleRecognition("", 0L, 0L, 0)
        val dec = decSession ?: return BubbleRecognition("", 0L, 0L, 0)

        // ---- encoder：一次 ----
        val encoderStartedAt = SystemClock.elapsedRealtime()
        val pix = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(preprocess224(crop)),
            longArrayOf(1, 3, IMG_SIZE.toLong(), IMG_SIZE.toLong())
        )
        val encOut: OnnxTensor = pix.use { tensor ->
            enc.run(mapOf("pixel_values" to tensor)).use { result ->
                // 复制一份 OnnxTensor 出来避免 result.use 释放后失效
                val orig = result[encOutputName].get()
                copyHiddenState(orig as OnnxTensor)
            }
        }
        val encoderMs = InferenceTiming.elapsedMs(encoderStartedAt, SystemClock.elapsedRealtime())

        // ---- decoder：greedy 自回归 ----
        val decoderStartedAt = SystemClock.elapsedRealtime()
        var decoderSteps = 0
        val text = encOut.use { hidden ->
            val ids = IntArray(MAX_TOKENS + 1)
            ids[0] = CLS_ID
            var len = 1
            for (step in 0 until MAX_TOKENS) {
                coroutineContext.ensureActive()
                decoderSteps++
                val idsCopy = LongArray(len) { ids[it].toLong() }
                val idsTensor = OnnxTensor.createTensor(
                    e,
                    LongBuffer.wrap(idsCopy),
                    longArrayOf(1, len.toLong())
                )
                val nextId = idsTensor.use { it2 ->
                    dec.run(
                        mapOf(
                            decInputIdsName to it2,
                            decHiddenName to hidden
                        )
                    ).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = result[0].value as Array<Array<FloatArray>>
                        argmaxLastStep(logits[0])
                    }
                }
                if (nextId == SEP_ID) break
                ids[len] = nextId
                len++
                if (len > MAX_TOKENS) break
            }
            decodeTokens(ids, fromIdx = 1, toIdx = len)
        }
        return BubbleRecognition(
            text = text,
            encoderMs = encoderMs,
            decoderMs = InferenceTiming.elapsedMs(decoderStartedAt, SystemClock.elapsedRealtime()),
            decoderSteps = decoderSteps,
        )
    }

    /** ONNX result 出 use 块后底层 buffer 会释放，必须 deep copy。 */
    private fun copyHiddenState(src: OnnxTensor): OnnxTensor {
        val info = src.info
        val shape = info.shape
        // shape: [batch=1, seq=197, hidden=192]
        @Suppress("UNCHECKED_CAST")
        val data = src.value as Array<Array<FloatArray>>
        val flat = FloatArray(shape[0].toInt() * shape[1].toInt() * shape[2].toInt())
        var idx = 0
        for (a in data) for (b in a) for (f in b) { flat[idx++] = f }
        return OnnxTensor.createTensor(
            env ?: OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(flat),
            shape
        )
    }

    /** logits shape [seq, vocab]，取最后一步 argmax */
    private fun argmaxLastStep(logits: Array<FloatArray>): Int {
        val last = logits[logits.size - 1]
        var best = 0
        var bestVal = last[0]
        for (i in 1 until last.size) {
            if (last[i] > bestVal) { bestVal = last[i]; best = i }
        }
        return best
    }

    /** 拼接 ids[fromIdx until toIdx] 对应的字符串。跳过 special tokens (id ≤ MASK_ID)；strip `##` WordPiece 前缀。 */
    private fun decodeTokens(ids: IntArray, fromIdx: Int, toIdx: Int): String {
        val sb = StringBuilder()
        for (i in fromIdx until toIdx) {
            val tid = ids[i]
            if (tid <= MASK_ID) continue
            val tok = id2tok.getOrNull(tid) ?: continue
            sb.append(if (tok.startsWith("##")) tok.substring(2) else tok)
        }
        return sb.toString()
    }

    /**
     * 224×224 squash + L→RGB + (x/255 - 0.5)/0.5 + NCHW float[]。
     * 与 PoC `preprocess_224` 1:1 等价（manga-ocr 官方 ViTImageProcessor 行为）。
     */
    private fun preprocess224(crop: Bitmap): FloatArray {
        // L→RGB：强制把任何输入转灰度再扩到 3 通道，与 kha-white 训练流程一致
        val squashed = if (crop.width == IMG_SIZE && crop.height == IMG_SIZE) crop
        else Bitmap.createScaledBitmap(crop, IMG_SIZE, IMG_SIZE, true)
        val grayRgb = toGrayThenRgb(squashed)
        if (squashed !== crop) squashed.recycle()

        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        grayRgb.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
        grayRgb.recycle()

        // NCHW: channel-major
        val arr = FloatArray(3 * IMG_SIZE * IMG_SIZE)
        val plane = IMG_SIZE * IMG_SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            // 灰度后 R=G=B；按 (x/255-0.5)/0.5 = (x-127.5)/127.5
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            arr[i] = (r - 0.5f) / 0.5f
            arr[plane + i] = (g - 0.5f) / 0.5f
            arr[2 * plane + i] = (b - 0.5f) / 0.5f
        }
        return arr
    }

    /**
     * `PIL.Image.convert('L').convert('RGB')` 的 Kotlin 等价：先按 luminance 灰度化（ITU-R BT.601），
     * 再三通道复制。manga-ocr 训练时 PIL 这一步必须复刻，否则识别质量明显下降。
     */
    private fun toGrayThenRgb(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = true
            // BT.601 灰度变换矩阵：所有通道用 0.299 R + 0.587 G + 0.114 B
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    override fun close() {
        runCatching { encSession?.close() }
        runCatching { decSession?.close() }
        encSession = null
        decSession = null
        // env 是 ORT 全局单例，不在这里关；与 PaddleOcrEngine.close() 行为一致
    }

    companion object {
        private const val PERF_TAG = "MangaOcrPerf"
        const val PAD_ID = 0
        const val UNK_ID = 1
        const val CLS_ID = 2
        const val SEP_ID = 3
        const val MASK_ID = 4

        /** ViT 输入边长（manga-ocr 训练固定 224×224 squash） */
        const val IMG_SIZE = 224

        /**
         * decoder greedy 上限。比 PoC 用的 64 紧——端侧坏 case 兜底。日漫单气泡实测多数 < 30 token，
         * 极少超过 40。打满说明气泡 crop 过大或模型走偏，加 hit_limit 警告比硬等更安全。
         */
        const val MAX_TOKENS = 48

        const val CLUSTER_GAP_PX = 18
    }
}
