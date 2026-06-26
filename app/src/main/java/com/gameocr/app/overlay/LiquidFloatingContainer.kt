package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
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
                updateChildTranslation()
                invalidate()
            }
        }

    var ballRadius: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                updateChildTranslation()
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

    /** P（屏幕边接触点）相对 Q（球切点）沿屏幕边方向再延伸的距离 / r，决定挂钩"长度"。 */
    private val tailReach = 0.3f

    /** quadratic 控制点法向偏移 / r，决定挂钩"凹陷深度"（朝球心方向）。 */
    private val tailDepth = 0.4f

    init {
        // FrameLayout 默认 willNotDraw=true，不会调 onDraw / dispatchDraw 内的自定义绘制；打开它
        setWillNotDraw(false)
    }

    /**
     * dock 状态下球必须紧贴屏幕边（球面最远点 = edgeX），否则球与边之间会出现一段
     * fill 颜色的"水柱"。container 中心 = width/2 但 dock 时 cx = r 或 width - r，
     * 子 view 需同步 translation 才能让图标停留在球的视觉中心。
     */
    private fun visualCx(): Float {
        val r = ballRadius
        return when (side) {
            DockSide.LEFT -> r
            DockSide.RIGHT -> width - r
            DockSide.NONE -> width / 2f
        }
    }

    private fun updateChildTranslation() {
        if (width == 0 || ballRadius <= 0f) return
        val tx = visualCx() - width / 2f
        for (i in 0 until childCount) {
            getChildAt(i).translationX = tx
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateChildTranslation()
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        updateChildTranslation()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (ballRadius > 0f) {
            val cx = visualCx()
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
     * 画"球 + 液态挂钩"：球紧贴屏幕边（[cx] 已在 dock 时由 [visualCx] 调整为 r 或 width-r），
     * 屏幕边在球的上下方各形成一段**朝球心方向凹陷**的弧（quadratic），从屏幕边出发凹向球切点。
     *
     * - Q1/Q2：球面 ±60° 切点（贴左 = 球面 240°/120°；贴右 = 300°/60°）。
     * - P1/P2：屏幕边接触点，沿屏幕边方向再延伸 [tailReach]·r（P1 上方、P2 下方）。
     *   P 远离 cy 后，两条挂钩弧不再"漏斗中间细"，呈现独立的上下凹勾。
     * - 控制点：Q-P 中点沿 **朝球心方向** 的法向偏移 [tailDepth]·r —— 让弧线从屏幕边
     *   出发先凹向球心，再贴住球切点（与 quadratic 平滑切线一致）。
     * - fill：屏幕边 P1 → Q1 → 球近侧 120° 弧 → Q2 → 屏幕边 P2 → close。
     * - 球紧贴边 + dock 时 cx=r，球面最远点 = edgeX，路径无任何"水柱"直边。
     */
    private fun drawLiquid(canvas: Canvas, cx: Float, cy: Float, edgeX: Float, leftSide: Boolean) {
        val r = ballRadius
        val sign = if (leftSide) -1f else 1f       // 球切点相对 cx 的偏向

        // 球面 ±60° 切点
        val cosT = 0.5f
        val sinT = 0.866f
        val qx = cx + sign * cosT * r
        val q1y = cy - sinT * r
        val q2y = cy + sinT * r

        val reach = tailReach * r
        val depth = tailDepth * r
        val p1y = q1y - reach
        val p2y = q2y + reach

        // 上半弧 (Q1 → P1) quadratic 控制点：Q-P 中点朝球心方向法向偏移
        val midUpX = (qx + edgeX) * 0.5f
        val midUpY = (q1y + p1y) * 0.5f
        val dxUp = edgeX - qx
        val dyUp = p1y - q1y
        val dLenUp = sqrt(dxUp * dxUp + dyUp * dyUp)
        val nUpX = -dyUp / dLenUp
        val nUpY = dxUp / dLenUp
        val signNUp = if (nUpX * (cx - midUpX) + nUpY * (cy - midUpY) > 0f) 1f else -1f
        val ctrlUpX = midUpX + signNUp * nUpX * depth
        val ctrlUpY = midUpY + signNUp * nUpY * depth

        // 下半弧 (Q2 → P2) 镜像
        val midDnX = (qx + edgeX) * 0.5f
        val midDnY = (q2y + p2y) * 0.5f
        val dxDn = edgeX - qx
        val dyDn = p2y - q2y
        val dLenDn = sqrt(dxDn * dxDn + dyDn * dyDn)
        val nDnX = -dyDn / dLenDn
        val nDnY = dxDn / dLenDn
        val signNDn = if (nDnX * (cx - midDnX) + nDnY * (cy - midDnY) > 0f) 1f else -1f
        val ctrlDnX = midDnX + signNDn * nDnX * depth
        val ctrlDnY = midDnY + signNDn * nDnY * depth

        // 球近侧弧（Q1 → Q2 沿屏幕边那侧）
        val nearStart: Float
        val nearSweep: Float
        if (leftSide) { nearStart = 240f; nearSweep = -120f }
        else { nearStart = 300f; nearSweep = 120f }
        ballRect.set(cx - r, cy - r, cx + r, cy + r)

        // 底座 fill：屏幕边 P1 → 上挂钩 → 球近侧弧 → 下挂钩 → 屏幕边 P2 → close
        // 球紧贴边时 fill 区域的"屏幕边段"完全在 edgeX 直线上，但 P2 → P1 仅是 close()
        // 自动补上的极短回程（同在 X=edgeX 上的直线，不可见，无视觉直边问题）。
        fillPath.reset()
        fillPath.moveTo(edgeX, p1y)
        fillPath.quadTo(ctrlUpX, ctrlUpY, qx, q1y)
        fillPath.arcTo(ballRect, nearStart, nearSweep, false)
        fillPath.quadTo(ctrlDnX, ctrlDnY, edgeX, p2y)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // 球本体
        canvas.drawCircle(cx, cy, r, fillPaint)
        if (strokeWidthPx > 0f) {
            canvas.drawCircle(cx, cy, r - strokeWidthPx / 2f, strokePaint)

            strokePath.reset()
            strokePath.moveTo(edgeX, p1y)
            strokePath.quadTo(ctrlUpX, ctrlUpY, qx, q1y)
            strokePath.moveTo(qx, q2y)
            strokePath.quadTo(ctrlDnX, ctrlDnY, edgeX, p2y)
            canvas.drawPath(strokePath, strokePaint)
        }
    }
}
