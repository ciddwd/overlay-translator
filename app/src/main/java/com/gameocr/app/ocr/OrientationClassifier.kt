package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * 文本排版方向 5+1 类。设计目标：覆盖屏译实际遇到的所有"非默认"排版，把它们映射到一个
 * OCR 引擎选择规则上（[OrientationRouting.resolveEngine]）。
 *
 * 之所以拆 5+1 类而不是简单的 horizontal/vertical 二分类：
 *  - [VERTICAL_RTL] 与 [VERTICAL_LTR] 决定列拼接顺序（日漫 RTL vs 蒙古文 LTR），引擎层不一样
 *  - [STACKED] 是 logo / 海报常见的"每行一个字母"，本质横排但 box 高瘦得像竖排，特判避免误路由
 *  - [HORIZONTAL_RTL] 给阿拉伯 / 希伯来留位，目前无引擎专门处理但留作扩展
 *  - [UNKNOWN] 让路由层"宁可不切也别错切"——所有 Heuristic 置信度低 / box 太少的场景统统兜底
 */
@Serializable
enum class TextOrientation {
    /** 拉丁 / 中日韩横排，从左到右。绝大多数 case 应当落在这一类，路由层不分流。 */
    HORIZONTAL_LTR,
    /** 阿拉伯 / 希伯来横排，从右到左。现阶段无端侧引擎专精，留位给 Phase 3 云端兜底。 */
    HORIZONTAL_RTL,
    /** 日漫 tategaki / 繁中古风游戏 UI：竖排，列从右向左读。manga-ocr / 百度含位置版的目标场景。 */
    VERTICAL_RTL,
    /** 蒙古文 / 部分繁中古籍：竖排，列从左向右读。Phase 1 不主动分流，仅识别。 */
    VERTICAL_LTR,
    /** 一字母一行的英文 logo / 海报标题。本质横排但视觉上是纵向堆叠，OCR 必须按 y 拼接。 */
    STACKED,
    /** 启发式置信度过低 / box 数量不足。路由层看到 UNKNOWN 一律不覆盖用户引擎选择。 */
    UNKNOWN
}

/**
 * 一次方向判别的输出。带 [confidence] 与 [source] 便于在 LogScreen 给用户解释"为什么自动切了引擎"。
 *
 *  @param orientation 判别结果
 *  @param confidence  0..1。Heuristic 用 max(portraitRatio, landscapeRatio)；ONNX 模型用 softmax max
 *  @param rawAngle    整图旋转角度（0/90/180/270），Phase 1 永远是 0；Phase 2 PaddleDocOriClassifier 才有值
 *  @param source      标识哪个 classifier / 路径产出的结果，用于日志归因。
 *                     已知值："heuristic-bitmap-na"（pre-OCR Heuristic 兜底）、
 *                     "heuristic-boxes"（refineWithBoxes 正常输出）、
 *                     "heuristic-too-few-boxes"（box 数量 < 2 兜底）、
 *                     "manual"（用户在 Settings 里手动锁定）
 */
data class OrientationResult(
    val orientation: TextOrientation,
    val confidence: Float,
    val rawAngle: Int = 0,
    val source: String
)

/**
 * 方向分类器两段式接口。**为什么拆两段**：方向判别天然有两个独立信息源——
 *  1. bitmap 本身的整图旋转（PaddleDocOriClassifier 这种 ONNX 模型用，Heuristic 无能为力）
 *  2. OCR 检测结果的 bbox 几何分布（高宽比、列间距，Heuristic 的主战场）
 *
 * 让接口同时承载两段，避免实现内部反复 re-run OCR det；Phase 1 只有 Heuristic 时 [classifyFromBitmap]
 * 返回 [TextOrientation.UNKNOWN]，由 [OrientationCoordinator] 触发 [refineWithBoxes] 兜底。
 */
interface OrientationClassifier {
    /** 阶段 A：仅看 bitmap 像素。Phase 2 的 ONNX 模型在这里给出 90/270° 整图旋转判别。 */
    suspend fun classifyFromBitmap(bitmap: Bitmap): OrientationResult

    /** 阶段 B：拿到 OCR 检测结果后用 bbox 几何精细化（或直接判别）。 */
    fun refineWithBoxes(
        prelim: OrientationResult,
        blocks: List<TextBlock>,
        bitmapW: Int,
        bitmapH: Int
    ): OrientationResult
}

/**
 * 启发式实现：升级版 [RoutingOcrEngine.detectOrientation]——把"事后段落聚类用"的方向判别
 * 抽出来变成"也能作 OCR 引擎路由依据"的独立组件。
 *
 * 判据顺序（参考 [Companion.classifyByGeometry] 内注释）：
 *  1. box 数量 < 2 → UNKNOWN（统计样本不足，宁可不动）
 *  2. STACKED 特征（高瘦 box 且彼此分散）→ STACKED
 *  3. portrait 占比 ≥ 0.5 → VERTICAL_RTL（默认假定 RTL；蒙古文等 LTR 由路由层按 sourceLang 翻转）
 *  4. landscape 占比 ≥ 0.5 → HORIZONTAL_LTR
 *  5. 否则 → UNKNOWN
 *
 * **不输出 [TextOrientation.HORIZONTAL_RTL]**：阿拉伯 / 希伯来的 RTL 性质 bbox 几何看不出来
 * （box 高宽比与拉丁横排无区别），需要从 sourceLang 推断，由路由层处理。
 *
 * **核心判别逻辑 [Companion.classifyByGeometry] 是纯函数**，输入 [IntRect] 不依赖 `android.graphics.Rect`，
 * 单元测试可直接在 JVM 上跑（沿用 [BubbleClusterer] 同款"测试友好"模式）。`refineWithBoxes` 只是
 * adapter：把 [TextBlock.boundingBox] 转 [IntRect] 后委托给纯函数。
 */
@Singleton
class HeuristicOrientationClassifier @Inject constructor() : OrientationClassifier {

    override suspend fun classifyFromBitmap(bitmap: Bitmap): OrientationResult =
        OrientationResult(
            orientation = TextOrientation.UNKNOWN,
            confidence = 0f,
            rawAngle = 0,
            source = "heuristic-bitmap-na"
        )

    override fun refineWithBoxes(
        prelim: OrientationResult,
        blocks: List<TextBlock>,
        bitmapW: Int,
        bitmapH: Int
    ): OrientationResult {
        val rects = blocks.map { b ->
            val r = b.boundingBox
            IntRect(r.left, r.top, r.right, r.bottom)
        }
        val core = classifyByGeometry(rects, bitmapW, bitmapH)
        // adapter 不丢 prelim 提供的 rawAngle（Phase 2 ONNX 阶段 A 给的整图旋转角度）
        return core.copy(rawAngle = prelim.rawAngle)
    }

    companion object {

        /**
         * OCR 引擎路由用高宽比阈值：高 > 宽 × [PORTRAIT_RATIO] 视为 portrait，反之 landscape。
         *
         * 这里故意比 [RoutingOcrEngine] 段落聚类的 1.3 更严格：引擎路由一旦误切会导致整帧 OCR
         * 重跑到错误引擎，所以宁可让 h/w≈1.5 的 3 字短列先走 UNKNOWN，也不要把短横排 UI 或
         * stacked logo 误路由到竖排专用引擎。段落聚类仍保留生产调优过的 1.3，避免破坏
         * mergeAdjacentBlocks=true 下的漫画短列合并。
         *
         * 不做动态阈值（按 box 数量切换 1.3/2.0）：会导致用户每帧 OCR 结果跳变，同一画面
         * 一会儿走 manga-ocr 一会儿走 ML Kit。固定阈值更可预测。
         */
        const val PORTRAIT_RATIO: Float = 2.0f

        /**
         * "显著竖排" 高宽比阈值。任何 h/w > [STRONG_VERTICAL_RATIO] 的 box 都被视为"几乎不可能
         * 是横排"——典型的整列 tategaki 文本（一列 8+ 字，box 比例 5:1 起步）。
         *
         * **为什么需要这条**：屏译场景几乎总是有状态栏 / 标题 / 系统按钮等横排 UI 干扰；这些
         * 横排 box 会稀释整体 portrait 占比，让"主判据 portrait 占比 ≥ 50%"失效。实测一张
         * 含 10 列竖排正文 + 18 个横排 UI box 的图，portrait 占比仅 38%，但仍肉眼一看就是
         * 竖排正文为主。所以补一条"绝对判据"：只要 ≥ [STRONG_VERTICAL_MIN_COUNT] 个这种极
         * 细长 box，无论整体占比直接判 VERTICAL_RTL。
         */
        const val STRONG_VERTICAL_RATIO: Float = 5.0f
        const val STRONG_VERTICAL_MIN_COUNT: Int = 3

        /**
         * 方向判别的核心纯函数。**不依赖 android.graphics.Rect**，单元测试可直接传 [IntRect] 跑。
         *
         *  @param rects 一组矩形，通常来自 OCR det/recognize 的 boundingBox。元素 < 2 → UNKNOWN
         *  @param bitmapW 原 bitmap 宽，目前仅作元数据传出（未来 STACKED 判定可能用 totalH / bitmapH）
         *  @param bitmapH 原 bitmap 高
         *  @return 含 orientation + confidence + source 的结果。rawAngle 总是 0（要 ONNX 阶段 A 才能填）
         */
        fun classifyByGeometry(
            rects: List<IntRect>,
            bitmapW: Int,
            bitmapH: Int
        ): OrientationResult {
            if (rects.size < 2) {
                return OrientationResult(
                    orientation = TextOrientation.UNKNOWN,
                    confidence = 0f,
                    source = "heuristic-too-few-boxes"
                )
            }

            // 1. STACKED 优先判：一字母一行的 logo 特征明显，先排除掉避免被 portrait 判据误判为竖排
            if (looksStacked(rects)) {
                return OrientationResult(
                    orientation = TextOrientation.STACKED,
                    confidence = 0.8f,
                    source = "heuristic-boxes"
                )
            }

            // 2. "显著竖排"判据：屏译场景下状态栏 / 系统 UI 横排 box 会稀释整体占比（实测某张
            //    10 列竖排繁中 + 18 个横排 UI 的图，portrait 占比仅 38% 触发不了主判据）。
            //    所以补一条绝对判据——只要 ≥ STRONG_VERTICAL_MIN_COUNT 个 h/w > STRONG_VERTICAL_RATIO
            //    的极细长 box（不可能是横排误判），直接判 VERTICAL_RTL。
            val strongVerticalCount = rects.count { isStrongVertical(it) }
            if (strongVerticalCount >= STRONG_VERTICAL_MIN_COUNT) {
                return OrientationResult(
                    orientation = TextOrientation.VERTICAL_RTL,
                    confidence = 0.9f,
                    source = "heuristic-strong-vertical"
                )
            }

            val portraitCount = rects.count { isPortrait(it) }
            val landscapeCount = rects.count { isLandscape(it) }
            val total = rects.size
            val portraitRatio = portraitCount.toFloat() / total
            val landscapeRatio = landscapeCount.toFloat() / total

            val orientation = when {
                portraitCount > landscapeCount && portraitRatio >= 0.5f -> TextOrientation.VERTICAL_RTL
                landscapeCount > portraitCount && landscapeRatio >= 0.5f -> TextOrientation.HORIZONTAL_LTR
                else -> TextOrientation.UNKNOWN
            }

            val confidence = if (orientation == TextOrientation.UNKNOWN) {
                maxOf(portraitRatio, landscapeRatio)
            } else {
                maxOf(portraitRatio, landscapeRatio).coerceAtLeast(0.5f)
            }

            return OrientationResult(orientation, confidence, source = "heuristic-boxes")
        }

        /** 高 > 宽 × [PORTRAIT_RATIO] 视为 portrait（典型竖排单列 / 多字短列）。 */
        private fun isPortrait(r: IntRect): Boolean =
            r.height > r.width * PORTRAIT_RATIO

        /** 宽 > 高 × [PORTRAIT_RATIO] 视为 landscape（典型横排句段）。 */
        private fun isLandscape(r: IntRect): Boolean =
            r.width > r.height * PORTRAIT_RATIO

        /** 高 > 宽 × [STRONG_VERTICAL_RATIO]：极细长 box，几乎只可能是整列 tategaki 文本。 */
        private fun isStrongVertical(r: IntRect): Boolean =
            r.height > r.width * STRONG_VERTICAL_RATIO

        /**
         * STACKED logo 特征（一字母一行的英文招牌）：
         *  - box 数量 ≥ 3（"OK"/"NO" 等单字别误判）
         *  - 所有 box 都接近正方形（aspect ratio H/W ∈ [0.6, 1.6]）
         *  - 按 y 轴排序后，相邻 box 的 x 中心差异 < box 平均宽度的 0.5（说明叠在一列里）
         *  - 整体 y 跨度 / 单 box 高度 ≥ 3（确实"堆"起来了）
         *
         * 实测 BANK / GOOGLE / 各种竖排英文 logo 都能命中；漫画竖排日文（每个汉字 box 接近正方）
         * 因为列数通常 ≥ 2 + 列间距明显，第三条件不满足，不会误判为 STACKED。
         */
        private fun looksStacked(rects: List<IntRect>): Boolean {
            if (rects.size < 3) return false
            val allSquare = rects.all {
                val w = it.width.coerceAtLeast(1)
                val h = it.height.coerceAtLeast(1)
                val ratio = w.toFloat() / h
                ratio in 0.6f..1.6f
            }
            if (!allSquare) return false

            val sorted = rects.sortedBy { it.top }
            val avgW = rects.map { it.width }.average().toFloat()
            val xCenters = sorted.map { (it.left + it.right) / 2f }
            val maxCenterDiff = (xCenters.maxOrNull() ?: 0f) - (xCenters.minOrNull() ?: 0f)
            if (maxCenterDiff > avgW * 0.5f) return false

            val totalH = (sorted.last().bottom - sorted.first().top).coerceAtLeast(1)
            val avgH = rects.map { it.height }.average().toFloat()
            return totalH / avgH >= 3f
        }
    }
}

/**
 * 方向判别协调器。Phase 1 仅持有 [HeuristicOrientationClassifier]；Phase 2 加入
 * `Provider<PaddleDocOriClassifier>` 后，[classifyPreOcr] 优先调用 ONNX 模型，失败 fallback Heuristic。
 *
 * **暴露两套入口的原因**：
 *  - [classifyPreOcr] — OCR **之前**调用，只能看 bitmap 像素。Phase 1 Heuristic 这步返回 UNKNOWN
 *    （像素看不出方向），所以 Phase 1 这条路径不会触发路由覆盖。Phase 2 接 ONNX 模型后才有实际效果。
 *  - [classifyPostOcr] — OCR **之后**调用，能用 bbox 几何精细化。Phase 1 的核心入口：CaptureService 用
 *    用户选定引擎跑完 OCR，把 bbox 喂给这里；如果判别结果与当前引擎能力不匹配（如发现是日文竖排但
 *    用了 ML Kit Latin），路由层会让 CaptureService 用更合适的引擎再跑一次。
 *
 * **Phase 1 不引入 Provider<PaddleDocOriClassifier>** 的原因：Hilt 在编译期会校验 binding，
 * 若类不存在会报 MissingBinding 编译失败。Phase 2 加入 PaddleDocOriClassifier 类时同步加 Provider 字段。
 */
@Singleton
class OrientationCoordinator @Inject constructor(
    private val heuristic: HeuristicOrientationClassifier,
    private val paddleDocOriProvider: Provider<PaddleDocOriClassifier>,
    private val paddleTextLineOriProvider: Provider<PaddleTextLineOrientationClassifier>
) {
    /**
     * OCR 之前的方向预判。Phase 1：Heuristic 无 bitmap 阶段能力 → 永远返回 UNKNOWN。
     * Phase 2 PaddleDocOriClassifier 加入后会在这里出实际结果。
     */
    suspend fun classifyPreOcr(bitmap: Bitmap): OrientationResult {
        val modelResult = runCatching {
            paddleDocOriProvider.get().classifyFromBitmap(bitmap)
        }.onFailure { t ->
            when (t) {
                is ModelNotReadyException -> Timber.d("Paddle doc orientation model not ready; fallback heuristic")
                else -> Timber.w(t, "Paddle doc orientation failed; fallback heuristic")
            }
        }.getOrNull()
        return modelResult ?: heuristic.classifyFromBitmap(bitmap)
    }

    /**
     * OCR 之后的方向精细化判别。Phase 1 的实际生效入口。
     *
     *  @param bitmap OCR 用的 bitmap（已预处理）。用于读宽高；Heuristic 不实际访问像素
     *  @param blocks OCR 输出的所有 TextBlock。必须 ≥ 2 个 box，否则返回 UNKNOWN
     */
    suspend fun classifyPostOcr(
        bitmap: Bitmap,
        blocks: List<TextBlock>,
        prelim: OrientationResult = OrientationResult(TextOrientation.UNKNOWN, 0f, 0, "post-ocr")
    ): OrientationResult {
        val geometry = heuristic.refineWithBoxes(prelim, blocks, bitmap.width, bitmap.height)
        if (geometry.orientation == TextOrientation.VERTICAL_RTL ||
            geometry.orientation == TextOrientation.VERTICAL_LTR
        ) {
            return geometry
        }
        val textLineResult = runCatching {
            paddleTextLineOriProvider.get().classifyFromBlocks(bitmap, blocks)
        }.onFailure { t ->
            when (t) {
                is ModelNotReadyException -> Timber.d("Paddle text-line orientation model not ready; keep geometry")
                else -> Timber.w(t, "Paddle text-line orientation failed; keep geometry")
            }
        }.getOrNull()
        return if (textLineResult?.rawAngle == 180) {
            textLineResult
        } else {
            geometry
        }
    }
}
