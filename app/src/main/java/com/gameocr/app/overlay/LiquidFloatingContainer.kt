package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.FrameLayout
import kotlin.math.sqrt

/**
 * 悬浮球容器：自己在 [dispatchDraw] 前画"球本体 + 液态吸附尾巴"的 path 作为背景，子 view
 * （图标 ImageView + [LoopProgressView]）始终居中、不变形。
 *
 * 三态：
 * - [DockSide.NONE] → 画完整圆 + 描边（默认浮动态）。
 * - [DockSide.LEFT] → 球 + 两条凹贝塞尔连接到容器左边（屏幕物理左边）。
 * - [DockSide.RIGHT] → 球 + 镜像连接到容器右边。
 *
 * 视觉等价 iOS Dynamic Island 的"液体吸附"形变。屏幕边那段直线不描边，只在球弧 +
 * 两条贝塞尔上画 stroke，保证水滴尾巴"融入"屏幕边没有违和直线。
 *
 * 球的视觉中心 = container 中心 (width/2, height/2)，球直径 = 2 * [ballRadius]。
 * container 通常给 1.5×ball 的宽度、1.4×ball 的高度作 padding（供 path 上下/侧向展开）。
 */
internal class LiquidFloatingContainer(context: Context) : FrameLayout(context) {

    enum class DockSide { NONE, LEFT, RIGHT }

    var side: DockSide = DockSide.NONE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var ballRadius: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var fillColor: Int = 0xCC1E88E5.toInt()
        set(value) {
            if (field != value) {
                field = value
                fillPaint.color = value
                invalidate()
            }
        }

    var strokeColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            if (field != value) {
                field = value
                strokePaint.color = value
                invalidate()
            }
        }

    var strokeWidthPx: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                strokePaint.strokeWidth = value
                invalidate()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
    }
    private val fillPath = Path()
    private val strokePath = Path()
    private val ballRect = RectF()

    init {
        // FrameLayout 默认 willNotDraw=true，不会调 onDraw / dispatchDraw 内的自定义绘制；打开它
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (ballRadius > 0f) {
            val cx = width / 2f
            val cy = height / 2f
            when (side) {
                DockSide.NONE -> drawCircle(canvas, cx, cy)
                DockSide.LEFT -> drawLiquid(canvas, cx, cy, edgeX = 0f, leftSide = true)
                DockSide.RIGHT -> drawLiquid(canvas, cx, cy, edgeX = width.toFloat(), leftSide = false)
            }
        }
        super.dispatchDraw(canvas)
    }

    private fun drawCircle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, ballRadius, fillPaint)
        if (strokeWidthPx > 0f) {
            canvas.drawCircle(cx, cy, ballRadius - strokeWidthPx / 2f, strokePaint)
        }
    }

    /**
     * 画"球 + 凹底座"（依照用户第二张图的几何）：
     *
     * 1. **底座**：两条 quadratic 弧从球切点 Q1/Q2 出发，**接触屏幕边**于 P1/P2 两点
     *    （P1 P2 之间只留 0.2r 的极短屏幕边段，避免出现长的"垂直边"）。
     *    控制点放在 Q1-P1 中点朝球心方向偏移 depth → 曲线朝"球与屏幕边形成的内角"凹陷。
     *    fill 用「上弧 + 短屏幕边 + 下弧 + 球近侧弧」闭合；stroke 只描两条弧。
     * 2. **球本体**：drawCircle 画完整圆 fill + 完整圆描边。
     *
     * 视觉：球紧贴屏幕边，下方一对"耳朵"状凹陷弧把球扶在墙上。
     */
    private fun drawLiquid(canvas: Canvas, cx: Float, cy: Float, edgeX: Float, leftSide: Boolean) {
        val r = ballRadius
        val sign = if (leftSide) -1f else 1f

        val cosT = 0.5f
        val sinT = 0.866f
        val qx = cx + sign * cosT * r
        val q1y = cy - sinT * r
        val q2y = cy + sinT * r

        // P1 / P2 几乎合并到 cy 附近（中间细），形成"收腰"
        val p1y = cy - 0.1f * r
        val p2y = cy + 0.1f * r

        // 控制点偏移方向严格沿 Q1-P1 直线的**法向**（垂直方向），不是朝球心方向。
        // 之前用"朝球心方向"几乎与 Q1-P1 直线本身平行，弧线鼓不出来，看起来像漏斗的直线
        // 边。改成法向后，弧线才能真正在中段"鼓"出 depth/2 的高度。
        val depth = 0.5f * r

        // 上半弧：Q1 → P1 法向控制点
        val midUpX = (qx + edgeX) * 0.5f
        val midUpY = (q1y + p1y) * 0.5f
        val dxUp = edgeX - qx
        val dyUp = p1y - q1y
        val dLenUp = sqrt(dxUp * dxUp + dyUp * dyUp)
        val nUpX = -dyUp / dLenUp     // 顺时针 90° 法向
        val nUpY = dxUp / dLenUp
        // 选择朝球心那一侧的法向（让弧线鼓向球心 = ")"开口朝屏幕边）
        val toCxUp = cx - midUpX
        val toCyUp = cy - midUpY
        val signNUp = if (nUpX * toCxUp + nUpY * toCyUp > 0f) 1f else -1f
        val ctrlUpX = midUpX + signNUp * nUpX * depth
        val ctrlUpY = midUpY + signNUp * nUpY * depth

        // 下半弧（镜像）
        val midDnX = (qx + edgeX) * 0.5f
        val midDnY = (q2y + p2y) * 0.5f
        val dxDn = edgeX - qx
        val dyDn = p2y - q2y
        val dLenDn = sqrt(dxDn * dxDn + dyDn * dyDn)
        val nDnX = -dyDn / dLenDn
        val nDnY = dxDn / dLenDn
        val toCxDn = cx - midDnX
        val toCyDn = cy - midDnY
        val signNDn = if (nDnX * toCxDn + nDnY * toCyDn > 0f) 1f else -1f
        val ctrlDnX = midDnX + signNDn * nDnX * depth
        val ctrlDnY = midDnY + signNDn * nDnY * depth

        // 球近侧弧（底座 fill 包到球的近侧半圆，与球同色无缝）
        // 贴左：Q1=240° Q2=120° 近侧=左半圆 sweep +120°
        // 贴右：Q1=300° Q2=60°  近侧=右半圆 sweep -120°
        val nearStart: Float
        val nearSweep: Float
        if (leftSide) { nearStart = 120f; nearSweep = 120f }
        else { nearStart = 60f; nearSweep = -120f }
        ballRect.set(cx - r, cy - r, cx + r, cy + r)

        // 底座 fill
        fillPath.reset()
        fillPath.moveTo(qx, q1y)
        fillPath.quadTo(ctrlUpX, ctrlUpY, edgeX, p1y)
        fillPath.lineTo(edgeX, p2y)   // 极短 (0.2r) 屏幕边段
        fillPath.quadTo(ctrlDnX, ctrlDnY, qx, q2y)
        fillPath.arcTo(ballRect, nearStart, nearSweep, false)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 球本体
        canvas.drawCircle(cx, cy, r, fillPaint)
        if (strokeWidthPx > 0f) {
            canvas.drawCircle(cx, cy, r - strokeWidthPx / 2f, strokePaint)

            // 底座描边：只描两条 quadratic 弧
            strokePath.reset()
            strokePath.moveTo(qx, q1y)
            strokePath.quadTo(ctrlUpX, ctrlUpY, edgeX, p1y)
            strokePath.moveTo(edgeX, p2y)
            strokePath.quadTo(ctrlDnX, ctrlDnY, qx, q2y)
            canvas.drawPath(strokePath, strokePaint)
        }
    }
}
