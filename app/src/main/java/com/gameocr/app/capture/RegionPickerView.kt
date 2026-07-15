package com.gameocr.app.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil

/**
 * 双阶段截屏区域选择：
 *
 * **阶段 1 [Mode.DRAWING]** —— 屏幕一片暗，提示用户拖动新建框。手指按下 → 移动 → 抬起得到初始框，自动切到阶段 2。
 *
 * **阶段 2 [Mode.ADJUSTING]** —— 围着已有的框显示 4 角 + 4 边共 8 个 handle；用户可：
 *   - 拖任一 handle 微调单边 / 单角；
 *   - 在框内拖动整体移动；
 *   - 在框外双击 / 通过外部"重画"按钮回到 [Mode.DRAWING]。
 *
 * 浮层 ✓ / ✗ / ↩ 按钮由 [RegionPickerActivity] 在 FrameLayout 上方添加，本 View 只负责画框与
 * touch state machine。回调：
 *   - [onConfirmRequested]: 当前框可用，请保存
 *   - [onCancel]: 用户放弃
 *
 * 进入时若调用方传了 [initial]，直接以阶段 2 起步并把 initial 当作当前框；否则进阶段 1。
 */
class RegionPickerView(
    context: Context,
    private val initial: Rect?,
    private val onCancel: () -> Unit,
    /** "双击 = 选择整屏" 触发时调用。语义跟主屏「清除选框」一致：清掉 captureRegion 让下次截屏走整屏，
     *  而不是画一个铺满屏幕的 rect 让用户再按 ✓。null 时退化为旧行为（设 rect=全屏 + 等用户确认）。 */
    private val onClearAllRequested: (() -> Unit)? = null
) : View(context) {

    /** rect 变化时通知调用方（浮层按钮用它做跟随定位）。null 表示当前没有有效框。 */
    var onRectChanged: ((Rect?) -> Unit)? = null

    enum class Mode { DRAWING, ADJUSTING }

    private enum class DragKind {
        NONE,
        MOVE,        // 整框拖动
        N, S, W, E,  // 4 边
        NW, NE, SW, SE // 4 角
    }

    private val mask = Paint().apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val rectStroke = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        isAntiAlias = true
    }
    private val clearInside = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val handleFill = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleBorder = Paint().apply {
        color = 0xFF1976D2.toInt() // Material primary blue, 跟暗色遮罩对比清晰
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        isAntiAlias = true
    }
    private val tipPaint = Paint().apply {
        color = Color.WHITE
        textSize = sp(14f)
        isAntiAlias = true
    }
    private val sizePaint = Paint().apply {
        color = Color.WHITE
        textSize = sp(12f)
        isAntiAlias = true
    }
    private val sizeBg = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }

    private val handleRadius = dp(8f)
    private val handleHitRadius = dp(28f) // 触摸命中半径，比绘制半径大确保好按
    private val minSide = dp(40f).toInt()

    private var mode: Mode = if (initial != null) Mode.ADJUSTING else Mode.DRAWING
    private var rect: Rect? = initial?.let { Rect(it) }

    // DRAWING 阶段临时坐标
    private var dragStartX = 0f
    private var dragStartY = 0f

    // ADJUSTING 阶段拖动状态
    private var dragKind: DragKind = DragKind.NONE
    private var dragAnchor: Rect? = null  // 拖动开始时的框，用于计算 delta
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var lastTapTime = 0L

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // PorterDuff.CLEAR 需要 software layer
        isClickable = true
        isFocusable = true
    }

    /** 当前框（可能为 null，比如阶段 1 还没拉过）。 */
    fun currentRect(): Rect? = rect?.let { Rect(it) }

    fun currentMode(): Mode = mode

    /** 由浮层按钮调用：清空当前框，回到阶段 1。 */
    fun resetToDrawing() {
        rect = null
        mode = Mode.DRAWING
        dragKind = DragKind.NONE
        dragAnchor = null
        invalidate()
        onRectChanged?.invoke(rect?.let { Rect(it) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 视图尺寸已知后，把外部传来的 initial 夹回边界；首次 layout 才会跑到这里
        rect?.let { r ->
            r.left = r.left.coerceIn(0, w - minSide)
            r.top = r.top.coerceIn(0, h - minSide)
            r.right = r.right.coerceIn(r.left + minSide, w)
            r.bottom = r.bottom.coerceIn(r.top + minSide, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)

        val r = rect
        if (r != null && r.width() > 0 && r.height() > 0) {
            canvas.drawRect(r, clearInside)
            canvas.drawRect(r, rectStroke)

            if (mode == Mode.ADJUSTING) {
                drawHandles(canvas, r)
                drawSizeLabel(canvas, r)
            }
        } else {
            // 阶段 1 提示
            val tip = context.getString(com.gameocr.app.R.string.region_picker_hint_drawing)
            canvas.drawText(tip, dp(20f), dp(64f), tipPaint)
        }
    }

    private fun drawHandles(canvas: Canvas, r: Rect) {
        for ((cx, cy) in handleCenters(r)) {
            canvas.drawCircle(cx, cy, handleRadius, handleFill)
            canvas.drawCircle(cx, cy, handleRadius, handleBorder)
        }
    }

    private fun drawSizeLabel(canvas: Canvas, r: Rect) {
        val label = "${r.width()} × ${r.height()}"
        val padding = dp(6f).toInt()
        val metrics = sizePaint.fontMetrics
        val textHeight = ceil(metrics.descent - metrics.ascent).toInt()
        val labelWidth = ceil(sizePaint.measureText(label)).toInt() + padding * 2
        val labelHeight = textHeight + padding * 2
        val placement = RegionSizeLabelPlacementPolicy.place(
            selection = RegionSizeLabelRect(r.left, r.top, r.right, r.bottom),
            viewportWidth = width,
            viewportHeight = height,
            labelWidth = labelWidth,
            labelHeight = labelHeight,
            gap = ceil(handleRadius + dp(3f)).toInt(),
            margin = dp(4f).toInt(),
        ) ?: return
        canvas.drawRect(
            placement.left.toFloat(),
            placement.top.toFloat(),
            placement.right.toFloat(),
            placement.bottom.toFloat(),
            sizeBg,
        )
        val baseline = placement.top + padding - metrics.ascent
        canvas.drawText(label, (placement.left + padding).toFloat(), baseline, sizePaint)
    }

    /** 8 个 handle 中心坐标，顺序：4 角（NW NE SW SE）+ 4 边中点（N S W E）。 */
    private fun handleCenters(r: Rect): List<Pair<Float, Float>> {
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        return listOf(
            r.left.toFloat() to r.top.toFloat(),     // NW
            r.right.toFloat() to r.top.toFloat(),    // NE
            r.left.toFloat() to r.bottom.toFloat(),  // SW
            r.right.toFloat() to r.bottom.toFloat(), // SE
            cx to r.top.toFloat(),                   // N
            cx to r.bottom.toFloat(),                // S
            r.left.toFloat() to cy,                  // W
            r.right.toFloat() to cy                  // E
        )
    }

    /**
     * hit-test：先 4 角 + 4 边，再框内 (MOVE)，再框外 (NONE)。
     * 角优先级高于边，因为角 handle 视觉上也在边 handle 之上。
     */
    private fun hitTest(x: Float, y: Float): DragKind {
        val r = rect ?: return DragKind.NONE
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        fun near(hx: Float, hy: Float) =
            kotlin.math.abs(x - hx) <= handleHitRadius && kotlin.math.abs(y - hy) <= handleHitRadius

        return when {
            near(r.left.toFloat(), r.top.toFloat()) -> DragKind.NW
            near(r.right.toFloat(), r.top.toFloat()) -> DragKind.NE
            near(r.left.toFloat(), r.bottom.toFloat()) -> DragKind.SW
            near(r.right.toFloat(), r.bottom.toFloat()) -> DragKind.SE
            near(cx, r.top.toFloat()) -> DragKind.N
            near(cx, r.bottom.toFloat()) -> DragKind.S
            near(r.left.toFloat(), cy) -> DragKind.W
            near(r.right.toFloat(), cy) -> DragKind.E
            r.contains(x.toInt(), y.toInt()) -> DragKind.MOVE
            else -> DragKind.NONE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (mode) {
            Mode.DRAWING -> onTouchDrawing(event)
            Mode.ADJUSTING -> onTouchAdjusting(event)
        }
    }

    private fun onTouchDrawing(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                rect = Rect(event.x.toInt(), event.y.toInt(), event.x.toInt(), event.y.toInt())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                rect = makeRect(dragStartX, dragStartY, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val r = rect
                if (r == null || r.width() < 20 || r.height() < 20) {
                    // 点击：双击 = 选择整屏。语义跟主屏「清除选框」一致——清掉 captureRegion 走整屏，
                    // 不画一个铺满屏幕的 rect 等用户确认。无回调时退化为旧行为。
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        lastTapTime = 0
                        val handler = onClearAllRequested
                        if (handler != null) {
                            handler.invoke()
                        } else {
                            rect = Rect(0, 0, width, height)
                            mode = Mode.ADJUSTING
                            invalidate()
                        }
                    } else {
                        lastTapTime = now
                        rect = null
                        invalidate()
                    }
                } else {
                    mode = Mode.ADJUSTING
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                rect = null
                invalidate()
            }
        }
        return true
    }

    private fun onTouchAdjusting(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragKind = hitTest(event.x, event.y)
                dragDownX = event.x
                dragDownY = event.y
                dragAnchor = rect?.let { Rect(it) }
                if (dragKind == DragKind.NONE) {
                    // 框外按下：双击重画
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        resetToDrawing()
                        lastTapTime = 0
                    } else {
                        lastTapTime = now
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                applyDrag(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragKind = DragKind.NONE
                dragAnchor = null
            }
        }
        return true
    }

    private fun applyDrag(x: Float, y: Float) {
        val anchor = dragAnchor ?: return
        val r = rect ?: return
        val dx = (x - dragDownX).toInt()
        val dy = (y - dragDownY).toInt()

        // 基于 dragAnchor + delta 重新计算，避免连续 move 累积误差
        var nl = anchor.left
        var nt = anchor.top
        var nr = anchor.right
        var nb = anchor.bottom

        when (dragKind) {
            DragKind.MOVE -> {
                val w = anchor.width(); val h = anchor.height()
                nl = (anchor.left + dx).coerceIn(0, width - w)
                nt = (anchor.top + dy).coerceIn(0, height - h)
                nr = nl + w
                nb = nt + h
            }
            DragKind.NW -> { nl = (anchor.left + dx).coerceIn(0, anchor.right - minSide); nt = (anchor.top + dy).coerceIn(0, anchor.bottom - minSide) }
            DragKind.NE -> { nr = (anchor.right + dx).coerceIn(anchor.left + minSide, width); nt = (anchor.top + dy).coerceIn(0, anchor.bottom - minSide) }
            DragKind.SW -> { nl = (anchor.left + dx).coerceIn(0, anchor.right - minSide); nb = (anchor.bottom + dy).coerceIn(anchor.top + minSide, height) }
            DragKind.SE -> { nr = (anchor.right + dx).coerceIn(anchor.left + minSide, width); nb = (anchor.bottom + dy).coerceIn(anchor.top + minSide, height) }
            DragKind.N -> { nt = (anchor.top + dy).coerceIn(0, anchor.bottom - minSide) }
            DragKind.S -> { nb = (anchor.bottom + dy).coerceIn(anchor.top + minSide, height) }
            DragKind.W -> { nl = (anchor.left + dx).coerceIn(0, anchor.right - minSide) }
            DragKind.E -> { nr = (anchor.right + dx).coerceIn(anchor.left + minSide, width) }
            DragKind.NONE -> return
        }

        r.set(nl, nt, nr, nb)
        invalidate()
        onRectChanged?.invoke(rect?.let { Rect(it) })
    }

    private fun makeRect(sx: Float, sy: Float, ex: Float, ey: Float): Rect {
        val l = minOf(sx, ex).toInt().coerceAtLeast(0)
        val t = minOf(sy, ey).toInt().coerceAtLeast(0)
        val r = maxOf(sx, ex).toInt().coerceAtMost(width)
        val b = maxOf(sy, ey).toInt().coerceAtMost(height)
        return Rect(l, t, r, b)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** 让 Activity 浮层"取消"按钮触达底层 onCancel 回调。 */
    fun requestCancel() = onCancel()
}
