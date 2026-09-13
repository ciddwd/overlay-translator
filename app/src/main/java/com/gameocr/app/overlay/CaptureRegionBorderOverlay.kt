package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.capture.CaptureRegionBorderStyle
import com.gameocr.app.capture.CaptureRegionResizeCorner
import com.gameocr.app.capture.captureRegionBorderRect
import com.gameocr.app.capture.movedCaptureRegion
import com.gameocr.app.capture.normalizedCaptureRegionBorderWidthDp
import com.gameocr.app.capture.resizedCaptureRegion
import com.gameocr.app.capture.shouldShowCaptureRegionBorder
import com.gameocr.app.capture.shouldHideCaptureRegionBorder
import com.gameocr.app.data.Settings
import kotlin.math.roundToInt

private enum class CaptureRegionDragKind {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    // Added last so the central handle remains reachable when a very small region overlaps handles.
    MOVE,
}

/**
 * Outlines the active screenshot region. The border remains fully touch-through. When adjustment is
 * enabled, five small overlay windows provide one move handle and four resize handles without
 * turning the entire screen or capture region into a touch-blocking window.
 */
internal class CaptureRegionBorderOverlay(
    private val context: Context,
    private val onRegionAdjusted: (CaptureRegion, Int, Int) -> Unit = { _, _, _ -> },
) {

    private val density = context.resources.displayMetrics.density
    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm: WindowManager by lazy {
        val defaultWm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@lazy defaultWm
        runCatching {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching defaultWm
            context.createWindowContext(display, overlayType, null)
                .getSystemService(WindowManager::class.java) ?: defaultWm
        }.getOrElse { defaultWm }
    }

    private var borderView: CaptureRegionBorderView? = null
    private val adjustmentViews = mutableMapOf<CaptureRegionDragKind, CaptureRegionHandleView>()
    private var hiddenForCapture: Boolean = false
    private var autoHideOnCapture: Boolean = true
    private var hiddenForEditor: Boolean = false
    private var hiddenForWordSelect: Boolean = false
    private var adjustmentRegion: CaptureRegion? = null
    private var adjustmentScreenWidth: Int = 0
    private var adjustmentScreenHeight: Int = 0
    private var dragAnchor: CaptureRegion? = null
    private var dragDownRawX: Float = 0f
    private var dragDownRawY: Float = 0f

    fun applySettings(settings: Settings) {
        autoHideOnCapture = settings.captureRegionHideOnCapture
        val region = settings.captureRegion
        if (!shouldShowCaptureRegionBorder(settings.captureRegionBorderEnabled, region)) {
            hide()
            return
        }

        val currentRegion = checkNotNull(region)
        val (screenWidth, screenHeight) = physicalScreenSize()
        val view = borderView ?: CaptureRegionBorderView(context)
        view.configure(
            region = currentRegion,
            color = settings.captureRegionBorderColor,
            widthDp = settings.captureRegionBorderWidthDp,
            style = settings.captureRegionBorderStyle,
            adjustmentEnabled = settings.captureRegionAdjustmentEnabled,
        )
        val params = borderLayoutParams(screenWidth, screenHeight)
        if (borderView == null) {
            if (runCatching { wm.addView(view, params) }.isSuccess) {
                borderView = view
            }
        } else {
            runCatching { wm.updateViewLayout(view, params) }
        }

        syncAdjustmentHandles(
            enabled = settings.captureRegionAdjustmentEnabled,
            region = currentRegion,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        syncVisibility()
    }

    /** Update existing geometry immediately after a commit; the settings flow supplies style. */
    fun applyCommittedRegion(region: CaptureRegion?) {
        if (region == null) {
            hide()
        } else {
            updateAdjustmentPreview(region)
            syncHandlePositions(region)
        }
    }

    fun setHiddenForCapture(hidden: Boolean): Boolean {
        if (hiddenForCapture == hidden) return false
        val wasVisible = borderView?.visibility == View.VISIBLE
        hiddenForCapture = hidden
        syncVisibility()
        return wasVisible && borderView?.visibility != View.VISIBLE
    }

    fun setHiddenForEditor(hidden: Boolean) {
        if (hiddenForEditor == hidden) return
        hiddenForEditor = hidden
        syncVisibility()
    }

    fun setHiddenForWordSelect(hidden: Boolean) {
        if (hiddenForWordSelect == hidden) return
        hiddenForWordSelect = hidden
        syncVisibility()
    }

    fun hide() {
        borderView?.let { runCatching { wm.removeView(it) } }
        borderView = null
        removeAdjustmentHandles()
        adjustmentRegion = null
        dragAnchor = null
    }

    private fun syncVisibility() {
        val visibility = if (
            shouldHideCaptureRegionBorder(
                hiddenForCapture = hiddenForCapture,
                hiddenForEditor = hiddenForEditor,
                hiddenForWordSelect = hiddenForWordSelect,
                autoHideOnCapture = autoHideOnCapture,
            )
        ) View.INVISIBLE else View.VISIBLE
        borderView?.visibility = visibility
        adjustmentViews.values.forEach { it.visibility = visibility }
    }

    private fun syncAdjustmentHandles(
        enabled: Boolean,
        region: CaptureRegion,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        adjustmentRegion = region
        adjustmentScreenWidth = screenWidth
        adjustmentScreenHeight = screenHeight
        if (!enabled) {
            removeAdjustmentHandles()
            return
        }

        CaptureRegionDragKind.entries.forEach { kind ->
            val existing = adjustmentViews[kind]
            if (existing == null) {
                val handle = CaptureRegionHandleView(context, kind).apply {
                    setOnTouchListener { _, event -> onHandleTouch(kind, event) }
                }
                val added = runCatching {
                    wm.addView(
                        handle,
                        handleLayoutParams(kind, region, screenWidth, screenHeight),
                    )
                }.isSuccess
                if (added) adjustmentViews[kind] = handle
            } else {
                runCatching {
                    wm.updateViewLayout(
                        existing,
                        handleLayoutParams(kind, region, screenWidth, screenHeight),
                    )
                }
            }
        }
    }

    private fun onHandleTouch(kind: CaptureRegionDragKind, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragAnchor = adjustmentRegion ?: return false
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> updateDraggedRegion(kind, event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> {
                updateDraggedRegion(kind, event.rawX, event.rawY)
                val anchor = dragAnchor
                val adjusted = adjustmentRegion
                dragAnchor = null
                if (anchor != null && adjusted != null && adjusted != anchor) {
                    syncHandlePositions(adjusted)
                    onRegionAdjusted(adjusted, adjustmentScreenWidth, adjustmentScreenHeight)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragAnchor?.let {
                    updateAdjustmentPreview(it)
                    syncHandlePositions(it)
                }
                dragAnchor = null
            }
        }
        return true
    }

    private fun updateDraggedRegion(kind: CaptureRegionDragKind, rawX: Float, rawY: Float) {
        val anchor = dragAnchor ?: return
        val dx = (rawX - dragDownRawX).roundToInt()
        val dy = (rawY - dragDownRawY).roundToInt()
        val next = when (kind) {
            CaptureRegionDragKind.MOVE -> movedCaptureRegion(
                region = anchor,
                deltaX = dx,
                deltaY = dy,
                screenWidth = adjustmentScreenWidth,
                screenHeight = adjustmentScreenHeight,
            )
            CaptureRegionDragKind.TOP_LEFT -> resize(anchor, CaptureRegionResizeCorner.TOP_LEFT, dx, dy)
            CaptureRegionDragKind.TOP_RIGHT -> resize(anchor, CaptureRegionResizeCorner.TOP_RIGHT, dx, dy)
            CaptureRegionDragKind.BOTTOM_LEFT -> resize(anchor, CaptureRegionResizeCorner.BOTTOM_LEFT, dx, dy)
            CaptureRegionDragKind.BOTTOM_RIGHT -> resize(anchor, CaptureRegionResizeCorner.BOTTOM_RIGHT, dx, dy)
        }
        if (next != null && next != adjustmentRegion) updateAdjustmentPreview(next)
    }

    private fun resize(
        anchor: CaptureRegion,
        corner: CaptureRegionResizeCorner,
        dx: Int,
        dy: Int,
    ): CaptureRegion? = resizedCaptureRegion(
        region = anchor,
        corner = corner,
        deltaX = dx,
        deltaY = dy,
        screenWidth = adjustmentScreenWidth,
        screenHeight = adjustmentScreenHeight,
        minSidePx = (40f * density).roundToInt(),
    )

    private fun updateAdjustmentPreview(region: CaptureRegion) {
        adjustmentRegion = region
        borderView?.updateRegion(region)
    }

    /** Window relayouts are intentionally deferred until the gesture ends. */
    private fun syncHandlePositions(region: CaptureRegion) {
        adjustmentViews.forEach { (kind, view) ->
            runCatching {
                wm.updateViewLayout(
                    view,
                    handleLayoutParams(
                        kind,
                        region,
                        adjustmentScreenWidth,
                        adjustmentScreenHeight,
                    ),
                )
            }
        }
    }

    private fun removeAdjustmentHandles() {
        adjustmentViews.values.forEach { runCatching { wm.removeView(it) } }
        adjustmentViews.clear()
    }

    private fun borderLayoutParams(width: Int, height: Int): WindowManager.LayoutParams =
        baseLayoutParams(
            width = width,
            height = height,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            x = 0
            y = 0
            alpha = MAX_TOUCH_THROUGH_ALPHA
        }

    private fun handleLayoutParams(
        kind: CaptureRegionDragKind,
        region: CaptureRegion,
        screenWidth: Int,
        screenHeight: Int,
    ): WindowManager.LayoutParams {
        val size = ((if (kind == CaptureRegionDragKind.MOVE) 52f else 40f) * density)
            .roundToInt()
            .coerceAtLeast(1)
        val (centerX, centerY) = handleCenter(kind, region)
        return baseLayoutParams(
            width = size,
            height = size,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            x = (centerX - size / 2).coerceIn(0, (screenWidth - size).coerceAtLeast(0))
            y = (centerY - size / 2).coerceIn(0, (screenHeight - size).coerceAtLeast(0))
        }
    }

    private fun handleCenter(
        kind: CaptureRegionDragKind,
        region: CaptureRegion,
    ): Pair<Int, Int> = when (kind) {
        CaptureRegionDragKind.MOVE ->
            ((region.left + region.right) / 2) to ((region.top + region.bottom) / 2)
        CaptureRegionDragKind.TOP_LEFT -> region.left to region.top
        CaptureRegionDragKind.TOP_RIGHT -> region.right to region.top
        CaptureRegionDragKind.BOTTOM_LEFT -> region.left to region.bottom
        CaptureRegionDragKind.BOTTOM_RIGHT -> region.right to region.bottom
    }

    private fun baseLayoutParams(
        width: Int,
        height: Int,
        flags: Int,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        overlayType,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            fitInsetsSides = 0
        }
    }

    private fun physicalScreenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private companion object {
        const val MAX_TOUCH_THROUGH_ALPHA: Float = 0.8f
    }
}

private class CaptureRegionBorderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val moveHandleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val moveHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val moveHandlePath = Path()
    private var region: CaptureRegion? = null
    private var adjustmentEnabled: Boolean = false

    fun configure(
        region: CaptureRegion,
        color: Int,
        widthDp: Int,
        style: CaptureRegionBorderStyle,
        adjustmentEnabled: Boolean,
    ) {
        this.region = region
        this.adjustmentEnabled = adjustmentEnabled
        paint.color = color
        handleStrokePaint.color = color
        moveHandlePaint.color = color
        paint.strokeWidth = normalizedCaptureRegionBorderWidthDp(widthDp) * density
        paint.strokeCap = if (style == CaptureRegionBorderStyle.DOTTED) {
            Paint.Cap.ROUND
        } else {
            Paint.Cap.BUTT
        }
        paint.pathEffect = when (style) {
            CaptureRegionBorderStyle.SOLID -> null
            CaptureRegionBorderStyle.DASHED -> DashPathEffect(
                floatArrayOf(10f * density, 6f * density),
                0f,
            )
            CaptureRegionBorderStyle.DOTTED -> DashPathEffect(
                floatArrayOf(0.1f, paint.strokeWidth * 2f),
                0f,
            )
        }
        invalidate()
    }

    fun updateRegion(region: CaptureRegion) {
        this.region = region
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentRegion = region ?: return
        val rect = captureRegionBorderRect(
            region = currentRegion,
            viewportWidth = width,
            viewportHeight = height,
            strokeWidthPx = paint.strokeWidth,
        ) ?: return
        canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
        if (adjustmentEnabled) drawAdjustmentHandles(canvas, currentRegion)
    }

    private fun drawAdjustmentHandles(canvas: Canvas, currentRegion: CaptureRegion) {
        val corners = listOf(
            currentRegion.left.toFloat() to currentRegion.top.toFloat(),
            currentRegion.right.toFloat() to currentRegion.top.toFloat(),
            currentRegion.left.toFloat() to currentRegion.bottom.toFloat(),
            currentRegion.right.toFloat() to currentRegion.bottom.toFloat(),
        )
        val cornerRadius = 7f * density
        corners.forEach { (x, y) ->
            canvas.drawCircle(x, y, cornerRadius, handleFillPaint)
            canvas.drawCircle(x, y, cornerRadius, handleStrokePaint)
        }

        val cx = (currentRegion.left + currentRegion.right) / 2f
        val cy = (currentRegion.top + currentRegion.bottom) / 2f
        drawFourDirectionArrow(canvas, cx, cy)
    }

    private fun drawFourDirectionArrow(canvas: Canvas, cx: Float, cy: Float) {
        val arm = 15f * density
        val arrow = 5f * density
        moveHandlePath.reset()
        moveHandlePath.moveTo(cx - arm, cy)
        moveHandlePath.lineTo(cx + arm, cy)
        moveHandlePath.moveTo(cx - arm, cy)
        moveHandlePath.lineTo(cx - arm + arrow, cy - arrow)
        moveHandlePath.moveTo(cx - arm, cy)
        moveHandlePath.lineTo(cx - arm + arrow, cy + arrow)
        moveHandlePath.moveTo(cx + arm, cy)
        moveHandlePath.lineTo(cx + arm - arrow, cy - arrow)
        moveHandlePath.moveTo(cx + arm, cy)
        moveHandlePath.lineTo(cx + arm - arrow, cy + arrow)
        moveHandlePath.moveTo(cx, cy - arm)
        moveHandlePath.lineTo(cx, cy + arm)
        moveHandlePath.moveTo(cx, cy - arm)
        moveHandlePath.lineTo(cx - arrow, cy - arm + arrow)
        moveHandlePath.moveTo(cx, cy - arm)
        moveHandlePath.lineTo(cx + arrow, cy - arm + arrow)
        moveHandlePath.moveTo(cx, cy + arm)
        moveHandlePath.lineTo(cx - arrow, cy + arm - arrow)
        moveHandlePath.moveTo(cx, cy + arm)
        moveHandlePath.lineTo(cx + arrow, cy + arm - arrow)
        canvas.drawPath(moveHandlePath, moveHandleHaloPaint)
        canvas.drawPath(moveHandlePath, moveHandlePaint)
    }
}

private class CaptureRegionHandleView(
    context: Context,
    private val kind: CaptureRegionDragKind,
) : View(context) {
    init {
        isClickable = true
        contentDescription = context.getString(
            if (kind == CaptureRegionDragKind.MOVE) {
                R.string.capture_region_move_handle
            } else {
                R.string.capture_region_resize_handle
            }
        )
    }
}
