package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * OCR 路由：按 [OcrEngineKind] 选 ML Kit / 百度 / 腾讯云 / PaddleOCR PP-OCRv5 mobile。
 *
 * 在底层 engine 之上还做 [mergeAdjacentBlocks]：把同一行内左右邻接的小 box 合并成一个，
 * 避免漫画 / 字幕场景一句话被拆成多段后译文层互相重叠。
 */
@Singleton
class RoutingOcrEngine @Inject constructor(
    private val mlKit: MlKitOcrEngine,
    private val baidu: BaiduOcrEngine,
    private val tencent: TencentOcrEngine,
    private val paddle: PaddleOcrEngine,
    private val youdao: YoudaoOcrEngine,
    private val umi: UmiOcrEngine,
    private val luna: LunaOcrEngine,
    private val manga: MangaOcrEngine,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val raw = when (kind) {
            OcrEngineKind.BAIDU -> baidu.recognize(bitmap, kind)
            OcrEngineKind.TENCENT -> tencent.recognize(bitmap, kind)
            OcrEngineKind.YOUDAO -> youdao.recognize(bitmap, kind)
            OcrEngineKind.UMI_OCR -> umi.recognize(bitmap, kind)
            OcrEngineKind.LUNA_OCR -> luna.recognize(bitmap, kind)
            OcrEngineKind.PADDLE_ONNX -> paddle.recognize(bitmap, kind)
            OcrEngineKind.MANGA_OCR_JA -> manga.recognize(bitmap, kind)
            else -> mlKit.recognize(bitmap, kind)
        }
        val settings = settingsRepository.get()
        Timber.tag("OcrMerge").i(
            "engine=%s raw=%d merge=%s strength=%s",
            kind, raw.size, settings.mergeAdjacentBlocks, settings.mergeStrength
        )
        if (!settings.mergeAdjacentBlocks) {
            logBoxes("raw-no-merge", raw)
            return raw
        }
        // 详细日志：打 box 坐标，用于诊断"为什么这两段没合"。仅 Timber（logcat），不写 LogRepository
        // 避免污染用户可见日志。tag = OcrMerge，过滤用。
        logBoxes("before", raw)
        val merged = mergeAdjacentBlocks(raw, MergeParams.forStrength(settings.mergeStrength))
        Timber.tag("OcrMerge").i(
            "strength=%s, %d -> %d blocks (final)",
            settings.mergeStrength, raw.size, merged.size
        )
        logBoxes("after", merged)
        return merged
    }

    private fun logBoxes(label: String, blocks: List<TextBlock>) {
        blocks.forEachIndexed { i, b ->
            val r = b.boundingBox
            Timber.tag("OcrMerge").i(
                "%s #%d (%d,%d,%d,%d) h=%d w=%d: %s",
                label, i + 1, r.left, r.top, r.right, r.bottom,
                r.height(), r.width(),
                previewForLog(b.text)
            )
        }
    }

    private fun previewForLog(text: String, limit: Int = 80): String =
        text.replace("\r", "\\r").replace("\n", "\\n").take(limit)

    override fun close() {
        mlKit.close()
        baidu.close()
        tencent.close()
        paddle.close()
        youdao.close()
        umi.close()
        luna.close()
        manga.close()
    }

    /**
     * 两阶段段落聚类：
     *
     *  阶段 1：**同行/同列邻接合并** —— 把"主轴方向相邻"的小 box 合成一段。
     *           横排：同一行内左右紧邻 → 空格拼接（"WHEN SHE" + "SOBERS UP." → "WHEN SHE SOBERS UP."）
     *           竖排：同一列内上下接续 → 换行拼接
     *
     *  阶段 2：**跨主轴段落合并** —— 把"主轴对齐 + 次轴邻接"的两段合成一个段落。
     *           横排：水平区间相交 + 上下邻接 → 换行拼接（漫画气泡 3 行小字 → 单条 3 行）
     *           竖排：垂直区间相交 + 左右邻接 → 换行拼接，且**列拼接顺序按 right-to-left**（日文竖排）
     *
     * 方向通过 [detectMergeOrientation] 自动探测（按强竖排列 / portrait 占比）。这样
     * OCR 行级输出 → 视觉段落级输出，下游叠加层只看到几个大 box，不会因"一段话被拆成 5
     * 个相邻框"导致译文层互相重叠。
     */
    private fun mergeAdjacentBlocks(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.isEmpty()) return blocks
        val preDirectionLimits = preDirectionNoiseLimits(blocks)
        val directionBlocks = removePreDirectionOcrNoise(blocks, preDirectionLimits)
        if (directionBlocks.size <= 1) return directionBlocks
        val orientationDecision = detectMergeOrientation(
            directionBlocks.map { it.boundingBox.toMergeDebugRect() }
        )
        Timber.tag("OcrMerge").i(
            "[detect] orientation=%s reason=%s portrait=%d landscape=%d strongVertical=%d total=%d portraitRatio=%.2f",
            orientationDecision.orientation,
            orientationDecision.reason,
            orientationDecision.portraitCount,
            orientationDecision.landscapeCount,
            orientationDecision.strongVerticalCount,
            orientationDecision.total,
            orientationDecision.portraitRatio
        )
        val orientation = orientationDecision.orientation
        return when (orientation) {
            Orientation.HORIZONTAL -> {
                val lineMerged = mergeSameLine(directionBlocks, params)
                val msg1 = "[H] stage1 sameLine: ${directionBlocks.size} -> ${lineMerged.size}"
                Timber.tag("OcrMerge").i(msg1)
                val paraMerged = mergeParagraph(lineMerged, params)
                val msg2 = "[H] stage2 paragraph: ${lineMerged.size} -> ${paraMerged.size}"
                Timber.tag("OcrMerge").i(msg2)
                paraMerged.withLayoutOrientation(orientation)
            }
            Orientation.VERTICAL -> {
                // 竖排日文专属：先丢振假名（ふりがな汉字注音小列），避免译文里出现
                // "しっぱい/失敗"读音+汉字重复，也让 overlay 不再两列叠在一起。
                val deFurigana = removeFurigana(directionBlocks)
                if (deFurigana.size != directionBlocks.size) {
                    val msg = "[V] removeFurigana: ${directionBlocks.size} -> ${deFurigana.size}"
                    Timber.tag("OcrMerge").i(msg)
                }
                val noiseLimits = verticalColumnMergeLimits(
                    deFurigana.map { it.boundingBox.toMergeDebugRect() },
                    verticalGapRatio = params.verticalGapRatio
                )
                val deNoised = removeVerticalOcrNoise(deFurigana, noiseLimits.baseColumnWidth)
                if (deNoised.size != deFurigana.size) {
                    Timber.tag("OcrMerge").i(
                        "[V] removeNoise: %d -> %d baseColW=%d",
                        deFurigana.size,
                        deNoised.size,
                        noiseLimits.baseColumnWidth
                    )
                }
                val columnMerged = mergeSameColumn(deNoised, params)
                val msg1 = "[V] stage1 sameColumn: ${deNoised.size} -> ${columnMerged.size}"
                Timber.tag("OcrMerge").i(msg1)
                val paraMerged = mergeColumnsToParagraph(columnMerged, params)
                val msg2 = "[V] stage2 columnsToPara (R→L): ${columnMerged.size} -> ${paraMerged.size}"
                Timber.tag("OcrMerge").i(msg2)
                paraMerged.withLayoutOrientation(orientation)
            }
        }
    }

    private data class PreDirectionNoiseLimits(val baseShortSide: Int)

    private fun preDirectionNoiseLimits(blocks: List<TextBlock>): PreDirectionNoiseLimits {
        val shortSides = blocks.map {
            minOf(it.boundingBox.width().coerceAtLeast(1), it.boundingBox.height().coerceAtLeast(1))
        }
        return PreDirectionNoiseLimits(baseShortSide = medianInt(shortSides).coerceAtLeast(1))
    }

    private fun removePreDirectionOcrNoise(
        blocks: List<TextBlock>,
        limits: PreDirectionNoiseLimits
    ): List<TextBlock> {
        val kept = blocks.filterNot { block ->
            val drop = shouldDropPreDirectionOcrNoise(
                text = block.text,
                confidence = block.confidence,
                rect = block.boundingBox.toMergeDebugRect(),
                baseShortSide = limits.baseShortSide
            )
            if (drop) {
                Timber.tag("OcrMerge").i(
                    "[noise] drop preDirection box=%s conf=%.3f baseShort=%d text=%s",
                    block.boundingBox.toMergeDebugRect().toLogString(),
                    block.confidence,
                    limits.baseShortSide,
                    previewForLog(block.text)
                )
            }
            drop
        }
        if (kept.size != blocks.size) {
            Timber.tag("OcrMerge").i(
                "[noise] preDirection: %d -> %d baseShort=%d",
                blocks.size,
                kept.size,
                limits.baseShortSide
            )
        }
        return kept
    }

    private fun removeVerticalOcrNoise(blocks: List<TextBlock>, baseColumnWidth: Int): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val kept = blocks.filterNot { block ->
            val drop = shouldDropVerticalOcrNoise(
                text = block.text,
                confidence = block.confidence,
                rect = block.boundingBox.toMergeDebugRect(),
                baseColumnWidth = baseColumnWidth
            )
            if (drop) {
                Timber.tag("OcrMerge").i(
                    "[V] drop noise box=%s conf=%.3f baseColW=%d text=%s",
                    block.boundingBox.toMergeDebugRect().toLogString(),
                    block.confidence,
                    baseColumnWidth,
                    previewForLog(block.text)
                )
            }
            drop
        }
        return kept.takeIf { it.isNotEmpty() } ?: blocks
    }

    private fun List<TextBlock>.withLayoutOrientation(orientation: Orientation): List<TextBlock> {
        val textOrientation = when (orientation) {
            Orientation.HORIZONTAL -> TextOrientation.HORIZONTAL_LTR
            Orientation.VERTICAL -> TextOrientation.VERTICAL_RTL
        }
        return map { it.copy(layoutOrientation = textOrientation) }
    }

    /**
     * 探测当前帧排版方向。
     *
     * 判据：把 box 按 h/w 比分成 portrait（h > w * [MERGE_PORTRAIT_RATIO]）
     * 与其它，portrait 占比 ≥ 50% → 竖排。这里不复用
     * [HeuristicOrientationClassifier.PORTRAIT_RATIO]：方向路由用 2.0，宁可 UNKNOWN 也别误切引擎；
     * 段落聚类用 1.3，保留漫画 / 字幕场景调优过的 3-4 字短列合并行为。
     *
     * 同时打日志，让 logcat 能溯源到为什么走了某条路径——竖排日漫一旦被误判为横排，stage2
     * 就用错了"水平相交"判据，导致即使激进档也合不上。
     */
    private fun mergeSameLine(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val result = mutableListOf<TextBlock>()
        for (b in sorted) {
            val last = result.lastOrNull()
            if (last != null && sameLineAdjacent(last, b, params)) {
                result[result.size - 1] = unionMerge(last, b, separator = " ")
            } else {
                result.add(b)
            }
        }
        return result
    }

    /**
     * 阶段 2：把"上下邻接 + 水平区间相交"的两段合成一个段落。
     * 多轮合并直到稳定（一个气泡 4 行可能要 3 轮）。
     */
    private fun mergeParagraph(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        var current = blocks
        repeat(10) {
            val (next, merged) = mergeParagraphOnce(current, params)
            if (!merged) return current
            current = next
        }
        return current
    }

    private fun mergeParagraphOnce(blocks: List<TextBlock>, params: MergeParams): Pair<List<TextBlock>, Boolean> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val used = BooleanArray(sorted.size)
        val result = mutableListOf<TextBlock>()
        var anyMerged = false
        for (i in sorted.indices) {
            if (used[i]) continue
            var acc = sorted[i]
            used[i] = true
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val adjacent = verticallyAdjacent(acc, sorted[j], params)
                logVerticalParagraphCandidate(acc, sorted[j], params, adjacent)
                if (adjacent) {
                    acc = unionMerge(acc, sorted[j], separator = "\n")
                    used[j] = true
                    anyMerged = true
                }
            }
            result.add(acc)
        }
        return result to anyMerged
    }

    private fun unionMerge(a: TextBlock, b: TextBlock, separator: String): TextBlock {
        val la = a.boundingBox; val rb = b.boundingBox
        val unionBox = Rect(
            minOf(la.left, rb.left),
            minOf(la.top, rb.top),
            maxOf(la.right, rb.right),
            maxOf(la.bottom, rb.bottom)
        )
        return a.copy(
            text = when {
                a.text.isBlank() -> b.text
                b.text.isBlank() -> a.text
                else -> a.text + separator + b.text
            },
            boundingBox = unionBox
        )
    }

    private fun sameLineAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        // 左右 normalize：让 ra 总是更左的，rb 总是更右的。否则 unionMerge 后 box 的 right 扩张，
        // 后续比较时 gap = b.left - last.right 会出现严重的负值（-100+），所有竖排日漫这种
        // "右列 top 反而更小、排序后被先处理"的场景全部漏合。
        val (ra, rb) = if (a.boundingBox.left <= b.boundingBox.left)
            a.boundingBox to b.boundingBox
        else
            b.boundingBox to a.boundingBox
        val ha = ra.height().coerceAtLeast(1)
        val hb = rb.height().coerceAtLeast(1)
        val avgH = (ha + hb) / 2
        val gap = rb.left - ra.right
        val topDelta = kotlin.math.abs(ra.top - rb.top)
        val heightRatio = maxOf(ha, hb).toFloat() / minOf(ha, hb)
        val maxTopDelta = avgH * params.sameLineTopTolerance
        val maxGap = avgH * params.adjacentGapRatio
        val reason = when {
            heightRatio > params.heightRatioLimit -> "heightRatio"
            topDelta >= maxTopDelta -> "topDelta"
            gap < -5 -> "backtrack"
            gap > maxGap -> "gap"
            else -> "merge"
        }
        val allowed = reason == "merge"
        Timber.tag("OcrMerge").i(
            "[H] stage1 sameLine allow=%s reason=%s gap=%d maxGap=%.1f topDelta=%d maxTopDelta=%.1f " +
                "heightRatio=%.2f maxHeightRatio=%.2f left=%s right=%s text=%s + %s",
            allowed,
            reason,
            gap,
            maxGap,
            topDelta,
            maxTopDelta,
            heightRatio,
            params.heightRatioLimit,
            ra.toMergeDebugRect().toLogString(),
            rb.toMergeDebugRect().toLogString(),
            previewForLog(a.text),
            previewForLog(b.text)
        )
        return allowed
    }

    /**
     * 竖排日文振假名（ふりがな汉字注音）过滤。
     *
     * 判据（同时满足才算振假名，丢掉）：
     *  - 紧贴某更宽 box（水平 gap ≤ 自身宽度）
     *  - 宽度比小（self.w / big.w < 0.6）
     *  - 高度比够大（self.h / big.h > 0.25，注音覆盖足够汉字范围；过滤孤立小段如「ため」）
     *  - 垂直区间完全被大 box 包住（注音只标自己范围内的字，不会越界）
     *
     * 调过的样本：百度高精度+位置版输出的「しっぱい」「けんこうてき」「にんげん」「はんにん」
     * 都能命中；同帧的孤立小段「ため」（h/big.h=0.12）「おる」（h/big.h=0.16）保留。
     */
    private fun removeFurigana(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size < 2) return blocks
        return blocks.filter { small ->
            val sb = small.boundingBox
            val isFurigana = blocks.any { other ->
                if (other === small) return@any false
                val bb = other.boundingBox
                if (bb.width() <= sb.width()) return@any false
                if (sb.width().toFloat() / bb.width() >= 0.6f) return@any false
                if (sb.height().toFloat() / bb.height() <= 0.25f) return@any false
                val hGap = if (sb.left >= bb.left) sb.left - bb.right else bb.left - sb.right
                if (hGap > sb.width()) return@any false
                if (sb.top < bb.top - 10 || sb.bottom > bb.bottom + 10) return@any false
                true
            }
            !isFurigana
        }
    }

    /**
     * 竖排阶段 1：把"同一列内上下接续"的 box 用 \n 串成单段。
     *
     * 镜像 [mergeSameLine]：主轴从"水平 + 高度参考"换到"垂直 + 宽度参考"。判据用宽度
     * 而非高度——竖排里 box 宽度 ≈ 单字大小，与横排里 box 高度同义。
     */
    private fun mergeSameColumn(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        val sorted = blocks.sortedWith(compareBy({ it.boundingBox.left }, { it.boundingBox.top }))
        val result = mutableListOf<TextBlock>()
        for (b in sorted) {
            val last = result.lastOrNull()
            if (last != null && sameColumnAdjacent(last, b, params)) {
                result[result.size - 1] = unionMerge(last, b, separator = "\n")
            } else {
                result.add(b)
            }
        }
        return result
    }

    /**
     * 竖排阶段 2：把"垂直区间相交 + 左右邻接"的两列合成一段。
     *
     * 关键：列拼接顺序 **right-to-left** —— 日文竖排从最右一列起读。sortedByDescending(left)
     * 让右列先成为 acc，后续的左列 union 进去时 acc 在前 sep 在后 → 文本顺序正确。
     */
    private fun mergeColumnsToParagraph(blocks: List<TextBlock>, params: MergeParams): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val limits = verticalColumnMergeLimits(
            blocks.map { it.boundingBox.toMergeDebugRect() },
            verticalGapRatio = params.verticalGapRatio
        )
        Timber.tag("OcrMerge").i(
            "[V] stage2 limits baseColW=%d maxHGap=%.1f minOverlapH=%d gapSamples=%s",
            limits.baseColumnWidth,
            limits.maxHorizontalGap,
            limits.minOverlapHeight,
            limits.gapSamples.joinToString(prefix = "[", postfix = "]")
        )
        var current = blocks
        repeat(10) {
            val (next, merged) = mergeColumnsOnce(current, params, limits)
            if (!merged) return current
            current = next
        }
        return current
    }

    private fun mergeColumnsOnce(
        blocks: List<TextBlock>,
        params: MergeParams,
        limits: VerticalColumnMergeLimits
    ): Pair<List<TextBlock>, Boolean> {
        val sorted = blocks.sortedWith(
            compareByDescending<TextBlock> { it.boundingBox.left }.thenBy { it.boundingBox.top }
        )
        val used = BooleanArray(sorted.size)
        val result = mutableListOf<TextBlock>()
        var anyMerged = false
        for (i in sorted.indices) {
            if (used[i]) continue
            var acc = sorted[i]
            used[i] = true
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (columnsHorizontallyAdjacent(acc, sorted[j], params, limits)) {
                    // acc 是右列（sort desc by left 保证），sorted[j] 是左列 → 文本顺序 acc 在前
                    acc = unionMerge(acc, sorted[j], separator = "\n")
                    used[j] = true
                    anyMerged = true
                }
            }
            result.add(acc)
        }
        return result to anyMerged
    }

    private fun sameColumnAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        // 上下 normalize：让 ra 总是更上的，rb 总是更下的；与 sameLineAdjacent 镜像。
        val (ra, rb) = if (a.boundingBox.top <= b.boundingBox.top)
            a.boundingBox to b.boundingBox
        else
            b.boundingBox to a.boundingBox
        val wa = ra.width().coerceAtLeast(1)
        val wb = rb.width().coerceAtLeast(1)
        val avgW = (wa + wb) / 2
        val gap = rb.top - ra.bottom
        val leftDelta = kotlin.math.abs(ra.left - rb.left)
        val widthRatio = maxOf(wa, wb).toFloat() / minOf(wa, wb)
        val maxLeftDelta = avgW * params.sameLineTopTolerance
        val maxGap = avgW * params.adjacentGapRatio
        val reason = when {
            widthRatio > params.heightRatioLimit -> "widthRatio"
            leftDelta >= maxLeftDelta -> "leftDelta"
            gap < -5 -> "backtrack"
            gap > maxGap -> "gap"
            else -> "merge"
        }
        val allowed = reason == "merge"
        Timber.tag("OcrMerge").i(
            "[V] stage1 sameColumn allow=%s reason=%s gap=%d maxGap=%.1f leftDelta=%d maxLeftDelta=%.1f " +
                "widthRatio=%.2f maxWidthRatio=%.2f upper=%s lower=%s text=%s + %s",
            allowed,
            reason,
            gap,
            maxGap,
            leftDelta,
            maxLeftDelta,
            widthRatio,
            params.heightRatioLimit,
            ra.toMergeDebugRect().toLogString(),
            rb.toMergeDebugRect().toLogString(),
            previewForLog(a.text),
            previewForLog(b.text)
        )
        return allowed
    }

    /**
     * 竖排"左右邻接 + 垂直区间相交"判据。与 [verticallyAdjacent] 严格镜像：把"高度"
     * 全部换成"宽度"，"水平相交"换成"垂直相交"。
     */
    private fun columnsHorizontallyAdjacent(
        a: TextBlock,
        b: TextBlock,
        params: MergeParams,
        limits: VerticalColumnMergeLimits
    ): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val debug = verticalColumnAdjacencyDebug(ra.toMergeDebugRect(), rb.toMergeDebugRect())
        val rejectReason = verticalColumnMergeRejectReason(debug, limits, params.horizontalOverlapRatio)
        val wideParagraphGapAllowed = rejectReason == "gap" &&
            wideVerticalParagraphGapAllowed(debug, limits, a.text, b.text)
        val allowed = rejectReason == null || wideParagraphGapAllowed
        Timber.tag("OcrMerge").i(
            "[V] stage2 columnsToPara allow=%s reason=%s hGap=%d maxGap=%.1f overlapH=%d minOverlapH=%d " +
                "overlapRatio=%.2f minOverlapRatio=%.2f backtrackLimit=%.1f right=%s left=%s text=%s + %s",
            allowed,
            if (wideParagraphGapAllowed) "wideParagraphGap" else rejectReason ?: "merge",
            debug.horizontalGap,
            limits.maxHorizontalGap,
            debug.overlapHeight,
            limits.minOverlapHeight,
            debug.overlapRatio,
            params.horizontalOverlapRatio,
            debug.columnWidth * 0.3f,
            debug.rightColumn.toLogString(),
            debug.leftColumn.toLogString(),
            previewForLog(a.text),
            previewForLog(b.text)
        )
        // 左右先 normalize：rR 是右列、rL 是左列
        // 镜像 verticallyAdjacent 的 "rb.top < ra.bottom - lineH * 0.3"：允许小重叠
        return allowed
        // 同 verticallyAdjacent：stage2 不再做 size ratio limit
    }

    private fun logVerticalParagraphCandidate(
        a: TextBlock,
        b: TextBlock,
        params: MergeParams,
        allowed: Boolean
    ) {
        val ra = a.boundingBox; val rb = b.boundingBox
        val lineH = minOf(ra.height().coerceAtLeast(1), rb.height().coerceAtLeast(1))
        val vGap = rb.top - ra.bottom
        val maxGap = lineH * params.verticalGapRatio
        val backtrackLimit = lineH * 0.3f
        val overlapLeft = maxOf(ra.left, rb.left)
        val overlapRight = minOf(ra.right, rb.right)
        val overlapW = overlapRight - overlapLeft
        val minW = minOf(ra.width(), rb.width()).coerceAtLeast(1)
        val overlapRatio = overlapW.toFloat() / minW
        val reason = when {
            allowed -> "merge"
            rb.top < ra.bottom - backtrackLimit -> "backtrack"
            vGap > maxGap -> "gap"
            overlapW <= 0 -> "noOverlap"
            overlapRatio < params.horizontalOverlapRatio -> "overlapRatio"
            else -> "blocked"
        }
        Timber.tag("OcrMerge").i(
            "[H] stage2 paragraph allow=%s reason=%s vGap=%d maxGap=%.1f overlapW=%d minOverlapW=%d " +
                "overlapRatio=%.2f minOverlapRatio=%.2f backtrackLimit=%.1f upper=%s lower=%s text=%s + %s",
            allowed,
            reason,
            vGap,
            maxGap,
            overlapW,
            1,
            overlapRatio,
            params.horizontalOverlapRatio,
            backtrackLimit,
            ra.toMergeDebugRect().toLogString(),
            rb.toMergeDebugRect().toLogString(),
            previewForLog(a.text),
            previewForLog(b.text)
        )
    }

    private fun Rect.toMergeDebugRect(): MergeDebugRect =
        MergeDebugRect(left = left, top = top, right = right, bottom = bottom)

    private fun verticallyAdjacent(a: TextBlock, b: TextBlock, params: MergeParams): Boolean {
        val ra = a.boundingBox; val rb = b.boundingBox
        val ha = ra.height().coerceAtLeast(1)
        val hb = rb.height().coerceAtLeast(1)
        val lineH = minOf(ha, hb)
        if (rb.top < ra.bottom - lineH * 0.3f) return false
        val vGap = rb.top - ra.bottom
        if (vGap > lineH * params.verticalGapRatio) return false
        val overlapLeft = maxOf(ra.left, rb.left)
        val overlapRight = minOf(ra.right, rb.right)
        val overlapW = overlapRight - overlapLeft
        if (overlapW <= 0) return false
        val minW = minOf(ra.width(), rb.width())
        if (overlapW.toFloat() / minW < params.horizontalOverlapRatio) return false
        // 注意：不再做 heightRatioLimit 检查——stage2 是跨行/跨列合并，acc 累积后段
        // 高/段宽天然就远大于单行/单列，再用 ratio 反而会让剩余孤立小列接不进段。
        return true
    }

    /**
     * 合并算法的 5 个阈值。封装成 data class 方便按 [MergeStrength] 切换三套预设，
     * 也方便日志 / 单元测试。各字段含义见各 [sameLineAdjacent] / [verticallyAdjacent] 用法。
     */
    private data class MergeParams(
        val sameLineTopTolerance: Float,
        val adjacentGapRatio: Float,
        val verticalGapRatio: Float,
        val horizontalOverlapRatio: Float,
        val heightRatioLimit: Float
    ) {
        companion object {
            fun forStrength(s: MergeStrength): MergeParams = when (s) {
                MergeStrength.CONSERVATIVE -> MergeParams(
                    sameLineTopTolerance = 0.35f,
                    adjacentGapRatio = 0.8f,
                    verticalGapRatio = 0.5f,
                    horizontalOverlapRatio = 0.5f,
                    heightRatioLimit = 1.4f
                )
                MergeStrength.STANDARD -> MergeParams(
                    sameLineTopTolerance = 0.5f,
                    adjacentGapRatio = 1.2f,
                    verticalGapRatio = 0.8f,
                    horizontalOverlapRatio = 0.3f,
                    heightRatioLimit = 1.6f
                )
                MergeStrength.AGGRESSIVE -> MergeParams(
                    sameLineTopTolerance = 0.7f,
                    adjacentGapRatio = 1.8f,
                    verticalGapRatio = 1.3f,
                    horizontalOverlapRatio = 0.15f,
                    // 竖排日漫一列字数差异大（4 字 vs 6 字 → 高度比 2.1），原 2.0 太严
                    heightRatioLimit = 2.5f
                )
            }
        }
    }
}

internal enum class Orientation { HORIZONTAL, VERTICAL }

internal data class MergeOrientationDecision(
    val orientation: Orientation,
    val reason: String,
    val portraitCount: Int,
    val landscapeCount: Int,
    val strongVerticalCount: Int,
    val total: Int,
    val portraitRatio: Float
)

internal fun detectMergeOrientation(rects: List<MergeDebugRect>): MergeOrientationDecision {
    if (rects.isEmpty()) {
        return MergeOrientationDecision(
            orientation = Orientation.HORIZONTAL,
            reason = "empty",
            portraitCount = 0,
            landscapeCount = 0,
            strongVerticalCount = 0,
            total = 0,
            portraitRatio = 0f
        )
    }
    val portrait = rects.count { it.height > it.width * MERGE_PORTRAIT_RATIO }
    val landscape = rects.count { it.width > it.height * MERGE_PORTRAIT_RATIO }
    val strongVertical = rects.count { it.height > it.width * MERGE_STRONG_VERTICAL_RATIO }
    val portraitRatio = portrait.toFloat() / rects.size
    val orientation = if (
        strongVertical >= MERGE_STRONG_VERTICAL_MIN_COUNT ||
        (portrait > landscape && portraitRatio >= 0.5f)
    ) {
        Orientation.VERTICAL
    } else {
        Orientation.HORIZONTAL
    }
    val reason = when {
        strongVertical >= MERGE_STRONG_VERTICAL_MIN_COUNT -> "strongVertical"
        orientation == Orientation.VERTICAL -> "portraitMajority"
        else -> "fallbackHorizontal"
    }
    return MergeOrientationDecision(
        orientation = orientation,
        reason = reason,
        portraitCount = portrait,
        landscapeCount = landscape,
        strongVerticalCount = strongVertical,
        total = rects.size,
        portraitRatio = portraitRatio
    )
}

private const val MERGE_PORTRAIT_RATIO: Float = 1.3f
private const val MERGE_STRONG_VERTICAL_RATIO: Float = 5.0f
private const val MERGE_STRONG_VERTICAL_MIN_COUNT: Int = 3

internal data class MergeDebugRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = (right - left).coerceAtLeast(1)

    val height: Int
        get() = (bottom - top).coerceAtLeast(1)

    fun toLogString(): String = "($left,$top,$right,$bottom)"
}

internal fun shouldDropVerticalOcrNoise(
    text: String,
    confidence: Float,
    rect: MergeDebugRect,
    baseColumnWidth: Int
): Boolean {
    val compact = text.filterNot { it.isWhitespace() }
    if (compact.isBlank()) return true
    if (compact.length > 4) return false
    if (compact.any { it.isCjkLikeForOcrMerge() }) return false
    if (confidence >= 0.45f) return false

    val base = baseColumnWidth.coerceAtLeast(1)
    val tinyWidth = rect.width <= base * 0.75f
    val tinyHeight = rect.height <= base * 1.25f
    val tinyArea = rect.width * rect.height <= base * base * 0.8f
    return tinyWidth && (tinyHeight || tinyArea)
}

internal fun shouldDropPreDirectionOcrNoise(
    text: String,
    confidence: Float,
    rect: MergeDebugRect,
    baseShortSide: Int
): Boolean {
    val compact = text.filterNot { it.isWhitespace() }
    if (compact.isBlank()) return true
    if (compact.length > 4) return false
    if (compact.any { it.isCjkLikeForOcrMerge() }) return false
    if (confidence >= 0.45f) return false
    if (!compact.all { it.isAsciiNoiseCandidateForOcrMerge() }) return false

    val base = baseShortSide.coerceAtLeast(1)
    val shortSide = minOf(rect.width, rect.height)
    val area = rect.width * rect.height
    return shortSide <= base * 0.75f && area <= base * base * 1.2f
}

private fun Char.isCjkLikeForOcrMerge(): Boolean =
    this in '\u3400'..'\u9FFF' ||
        this in '\uF900'..'\uFAFF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

private fun Char.isAsciiNoiseCandidateForOcrMerge(): Boolean = this in '!'..'~'

internal data class VerticalColumnAdjacencyDebug(
    val rightColumn: MergeDebugRect,
    val leftColumn: MergeDebugRect,
    val columnWidth: Int,
    val horizontalGap: Int,
    val overlapHeight: Int,
    val overlapRatio: Float
)

internal data class VerticalColumnMergeLimits(
    val baseColumnWidth: Int,
    val maxHorizontalGap: Float,
    val minOverlapHeight: Int,
    val gapSamples: List<Int>
)

internal fun verticalColumnMergeLimits(
    rects: List<MergeDebugRect>,
    verticalGapRatio: Float
): VerticalColumnMergeLimits {
    val verticalRects = rects.filter { it.height >= it.width * 2 && it.height >= 48 }
    val basis = if (verticalRects.size >= 2) verticalRects else rects
    val baseColumnWidth = medianInt(basis.map { it.width }).coerceAtLeast(1)
    val minOverlapHeight = maxOf(16, (baseColumnWidth * 0.75f).toInt())
    val gapSamples = basis
        .sortedByDescending { it.left }
        .zipWithNext()
        .mapNotNull { (right, left) ->
            val debug = verticalColumnAdjacencyDebug(right, left)
            debug.horizontalGap.takeIf { it >= 0 && debug.overlapHeight >= minOverlapHeight }
        }
    val widthBasedLimit = baseColumnWidth * verticalGapRatio
    val observedLimit = if (gapSamples.size >= 3) {
        medianInt(gapSamples) * 3f + baseColumnWidth * 0.25f
    } else {
        widthBasedLimit
    }
    return VerticalColumnMergeLimits(
        baseColumnWidth = baseColumnWidth,
        maxHorizontalGap = maxOf(12f, minOf(widthBasedLimit, observedLimit)),
        minOverlapHeight = minOverlapHeight,
        gapSamples = gapSamples
    )
}

internal fun verticalColumnMergeAllowed(
    debug: VerticalColumnAdjacencyDebug,
    limits: VerticalColumnMergeLimits,
    horizontalOverlapRatio: Float
): Boolean {
    return verticalColumnMergeRejectReason(debug, limits, horizontalOverlapRatio) == null
}

internal fun verticalColumnMergeRejectReason(
    debug: VerticalColumnAdjacencyDebug,
    limits: VerticalColumnMergeLimits,
    horizontalOverlapRatio: Float
): String? {
    if (debug.horizontalGap > limits.maxHorizontalGap) return "gap"
    if (debug.overlapHeight < limits.minOverlapHeight) return "overlapHeight"
    if (debug.overlapRatio < horizontalOverlapRatio) return "overlapRatio"
    return null
}

internal fun wideVerticalParagraphGapAllowed(
    debug: VerticalColumnAdjacencyDebug,
    limits: VerticalColumnMergeLimits,
    rightText: String,
    leftText: String
): Boolean {
    val rightLines = rightText.visualLineCountForOcrMerge()
    val leftLines = leftText.visualLineCountForOcrMerge()
    val maxWideGap = limits.baseColumnWidth * 1.8f
    val minTallOverlap = limits.baseColumnWidth * 4
    return rightLines >= 4 &&
        leftLines >= 4 &&
        debug.horizontalGap > limits.maxHorizontalGap &&
        debug.horizontalGap <= maxWideGap &&
        debug.overlapHeight >= maxOf(limits.minOverlapHeight, minTallOverlap) &&
        debug.overlapRatio >= 0.85f
}

private fun String.visualLineCountForOcrMerge(): Int =
    split('\n').count { it.isNotBlank() }.coerceAtLeast(1)

internal fun verticalColumnAdjacencyDebug(
    first: MergeDebugRect,
    second: MergeDebugRect
): VerticalColumnAdjacencyDebug {
    val (rightColumn, leftColumn) = if (first.left >= second.left) {
        first to second
    } else {
        second to first
    }
    val columnWidth = minOf(rightColumn.width, leftColumn.width)
    val horizontalGap = rightColumn.left - leftColumn.right
    val overlapTop = maxOf(first.top, second.top)
    val overlapBottom = minOf(first.bottom, second.bottom)
    val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0)
    val minHeight = minOf(first.height, second.height).coerceAtLeast(1)
    val overlapRatio = overlapHeight.toFloat() / minHeight
    return VerticalColumnAdjacencyDebug(
        rightColumn = rightColumn,
        leftColumn = leftColumn,
        columnWidth = columnWidth,
        horizontalGap = horizontalGap,
        overlapHeight = overlapHeight,
        overlapRatio = overlapRatio
    )
}

private fun medianInt(values: List<Int>): Int {
    if (values.isEmpty()) return 1
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}
