package com.gameocr.app.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.max

/**
 * 竖排（tategaki）文本绘制 + 度量。Android 16 / API 36 才有官方 `Paint.VERTICAL_TEXT_FLAG`
 * 垂直文本测量和绘制支持（本项目 compileSdk=35、minSdk=26 暂时用不了）。所以这里走业界
 * 公认的退化方案：**逐 codepoint `drawText`，按 W3C Unicode `Vertical_Orientation` 属性
 * 分类处理 UPRIGHT / 90° 旋转 / 标点位置偏移**。
 *
 * **不用整段 `canvas.rotate(90°)`**：那会让汉字字形侧倒（"床"读不出"床"），且与原文 bbox
 * 对不齐——比横排错位还糟，已被屏译/漫画翻译社区普遍否决。
 *
 * 设计原则：
 *  - 纯函数 + 无状态，[classify] / [measure] / [draw] 都不持有任何字段
 *  - 多列从右往左换列（VERTICAL_RTL 默认；VERTICAL_LTR 由调用方传 [leftToRight]=true 翻转）
 *  - 标点参考 W3C CSS Writing Modes 4 §4 character orientation：
 *    https://www.w3.org/TR/css-writing-modes-4/
 *    https://www.w3.org/International/articles/vertical-text/
 *
 * 已知 Phase 1.5 限制（后续可优化）：
 *  - 不支持 furigana（振假名）
 *  - 不支持 bouten（圈点强调）
 *  - 短数字（≤2 位）目前按 ROTATE_90_CW 兜底，不做 tate-chu-yoko 横排嵌入
 *  - 不调用 OpenType `vert` 特性，字形位置位移全靠手动算（visualOffsetX/Y）
 */
object VerticalTextDrawer {

    /**
     * W3C Unicode `Vertical_Orientation` 三分类。决定单个 codepoint 在竖排里如何渲染。
     */
    enum class GlyphKind {
        /** 直接居中放在列轴上：CJK 汉字 / 假名 / 谚文 / 多数中日韩文本主体。 */
        UPRIGHT,
        /** 局部 `save/rotate(90°)/translate/restore` 后绘制：括号 / 引号 / 破折号 / 拉丁字母数字。 */
        ROTATE_90_CW,
        /** 正立但位置偏到字格右上：句号 / 顿号 / 全角逗号句点。 */
        PUNCT_TOP_RIGHT
    }

    /**
     * 按 codepoint 决定竖排渲染策略。**纯函数**，可在 src/test 直接跑。
     *
     * 分类表（基于 W3C Vertical_Orientation 属性，常用子集）：
     *  - `、` `。` `，` `．` → PUNCT_TOP_RIGHT
     *  - 中日韩括号 / 引号 / 破折号 / 省略号 / 波浪号（U+3008..U+3011, U+300A..U+300F, U+FF08..U+FF09 等）→ ROTATE_90_CW
     *  - ASCII 可打印（0x21..0x7E）→ ROTATE_90_CW（拉丁默认 sideways）
     *  - 其它（汉字 / 假名 / 谚文等 CJK 主体）→ UPRIGHT
     */
    fun classify(codePoint: Int): GlyphKind = when {
        // 全角 / 中日韩句末标点：句号 顿号 全角逗号 全角句点
        codePoint == 0x3001 || codePoint == 0x3002 -> GlyphKind.PUNCT_TOP_RIGHT
        codePoint == 0xFF0C || codePoint == 0xFF0E -> GlyphKind.PUNCT_TOP_RIGHT
        // 中日韩括号 / 书名号 / 引号（< > 《 》 「 」 『 』 等）
        codePoint in 0x3008..0x3011 -> GlyphKind.ROTATE_90_CW
        codePoint in 0x300A..0x300F -> GlyphKind.ROTATE_90_CW
        // 全角括号
        codePoint == 0xFF08 || codePoint == 0xFF09 -> GlyphKind.ROTATE_90_CW
        codePoint == 0xFF5B || codePoint == 0xFF5D -> GlyphKind.ROTATE_90_CW
        // 破折号 / 省略号 / 波浪号
        codePoint == 0x2014 || codePoint == 0x2015 -> GlyphKind.ROTATE_90_CW
        codePoint == 0x2026 || codePoint == 0xFF5E -> GlyphKind.ROTATE_90_CW
        // ASCII 可打印字符（拉丁字母 / 数字 / 半角符号）：竖排里默认 sideways
        codePoint in 0x21..0x7E -> GlyphKind.ROTATE_90_CW
        // 其它（CJK 主体 / 假名 / 谚文 / emoji 等）保持正立
        else -> GlyphKind.UPRIGHT
    }

    /**
     * 度量竖排文本占用的最小 (width, height) 像素。供 [VerticalTextView.onMeasure] 用。
     *
     *  @param text 待画文本
     *  @param paint 携带 textSize / typeface，用于读 fontMetrics
     *  @param maxHeightPx 单列高度上限（超出换列）。<= 0 = 无限高，单列到底
     *  @return Pair(width, height)
     */
    fun measure(text: String, paint: Paint, maxHeightPx: Int = 0): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val cps = text.codePoints().toArray()
        val lineH = lineHeight(paint)
        val colW = columnWidth(paint)
        val effectiveMaxH = if (maxHeightPx <= 0) Int.MAX_VALUE.toFloat() else maxHeightPx.toFloat()

        var y = 0f
        var cols = 1
        for (cp in cps) {
            if (cp == 0x000A) {
                // 显式换行：进入下一列
                y = 0f
                cols++
                continue
            }
            y += lineH
            if (y > effectiveMaxH) {
                y = lineH
                cols++
            }
        }

        // 单列剩余文本的实际高度（不到 maxHeightPx 的情况）
        val measuredH = if (cols == 1) {
            ceil(y).toInt()
        } else if (maxHeightPx > 0) {
            maxHeightPx
        } else {
            ceil(y).toInt()
        }
        val measuredW = ceil(colW * cols).toInt()
        return max(measuredW, 1) to max(measuredH, 1)
    }

    /**
     * 在 canvas 上画竖排文本。多列从**右往左**换列（VERTICAL_RTL 默认）；[leftToRight]=true 时
     * 从左往右换列（VERTICAL_LTR 蒙古文 / 部分繁中古籍）。
     *
     *  @param canvas 目标画布
     *  @param text 待画文本
     *  @param paint 字号 / 颜色 / typeface
     *  @param boundsW 画布可用宽度（决定起始列 x 坐标 + 列数）
     *  @param boundsH 画布可用高度（决定每列字数上限，超出换列）
     *  @param leftToRight true = VERTICAL_LTR（从左往右换列）；false = VERTICAL_RTL（默认）
     */
    fun draw(
        canvas: Canvas,
        text: String,
        paint: Paint,
        boundsW: Float,
        boundsH: Float,
        leftToRight: Boolean = false
    ) {
        if (text.isEmpty()) return
        val cps = text.codePoints().toArray()
        val fm = paint.fontMetrics
        val lineH = lineHeight(paint)
        val colW = columnWidth(paint)

        // 列起始 x：RTL 起最右一列中心；LTR 起最左一列中心。每列向左 / 向右步进 colW
        val firstColCenterX = if (leftToRight) colW / 2f else boundsW - colW / 2f
        val colStepX = if (leftToRight) colW else -colW

        // 每列字数上限 = floor(boundsH / lineH)；至少 1（避免 boundsH 太小整段卡住）
        val perColCap = max(1, (boundsH / lineH).toInt())

        var colIdx = 0
        var inColIdx = 0  // 当前列已画的字数（含换行触发的归零）
        for (cp in cps) {
            if (cp == 0x000A) {
                // 显式换行：进下一列
                colIdx++
                inColIdx = 0
                continue
            }
            if (inColIdx >= perColCap) {
                colIdx++
                inColIdx = 0
            }
            val colCenterX = firstColCenterX + colIdx * colStepX
            // 越界保护：列起点已超出画布范围则停止绘制（避免画到外面）
            if (colCenterX < -colW || colCenterX > boundsW + colW) break

            // baseline y = (inColIdx + 1) 个字格的下沿 + 字格内 baseline 偏移
            val cellTop = inColIdx * lineH
            val baselineY = cellTop - fm.ascent  // ascent 是负值，相对 baseline 向上

            drawSingleGlyph(canvas, cp, paint, colCenterX, baselineY, lineH, colW)
            inColIdx++
        }
    }

    /**
     * 单 codepoint 绘制。按 [classify] 三类分别处理：
     *  - UPRIGHT：横向居中到列轴
     *  - ROTATE_90_CW：以列轴中心为锚点局部 rotate 90°
     *  - PUNCT_TOP_RIGHT：右上偏移 1/4 列宽 + 上偏 1/3 行高
     */
    private fun drawSingleGlyph(
        canvas: Canvas,
        codePoint: Int,
        paint: Paint,
        colCenterX: Float,
        baselineY: Float,
        lineH: Float,
        colW: Float
    ) {
        val s = String(Character.toChars(codePoint))
        val advance = paint.measureText(s)
        when (classify(codePoint)) {
            GlyphKind.UPRIGHT -> {
                canvas.drawText(s, colCenterX - advance / 2f, baselineY, paint)
            }
            GlyphKind.PUNCT_TOP_RIGHT -> {
                // 句号 / 顿号在竖排里位置在字格右上角。偏移 visualOffsetX = +colW/4（往右），
                // visualOffsetY = -lineH/3（往上）。
                canvas.drawText(
                    s,
                    colCenterX - advance / 2f + colW / 4f,
                    baselineY - lineH / 3f,
                    paint
                )
            }
            GlyphKind.ROTATE_90_CW -> {
                // 局部旋转 90° 顺时针。pivot 选列轴中心 + 字格垂直中心，确保旋转后视觉位置正确。
                val pivotX = colCenterX
                val pivotY = baselineY - lineH / 4f  // 字格垂直中点近似（baseline 上方约 1/4 lineH）
                canvas.save()
                canvas.rotate(90f, pivotX, pivotY)
                // 旋转后坐标系：原来的"向右"变成"向下"。让字横向居中到 pivot 即可
                canvas.drawText(s, pivotX - advance / 2f, pivotY + paint.fontMetrics.descent, paint)
                canvas.restore()
            }
        }
    }

    /** 字格行高（baseline 间距）。1.05 系数保留 5% 行距，与 RoutingOcrEngine 横排聚类 1.05 一致。 */
    fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * 1.05f
    }

    /** 列宽（左右占用空间）。竖排单列宽度 ≈ 单字宽度，用 "国" 这种全角字 measureText 近似。 */
    fun columnWidth(paint: Paint): Float {
        // 用全角"国"测——所有 CJK 字符的宽度近似都是这个值
        return max(paint.measureText("国"), paint.textSize)
    }
}
