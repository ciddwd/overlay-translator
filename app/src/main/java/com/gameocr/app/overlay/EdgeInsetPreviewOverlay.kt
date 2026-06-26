package com.gameocr.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * "贴边距离"滑块的实时预览：在屏幕左右各画一条半透粉色条带，宽度 = 当前 inset。
 * 用户拖动滑块时直观看到悬浮球将会让出的边距宽度（参考 MIUI 悬浮窗边距 UX）。
 *
 * 通过 WindowManager + TYPE_APPLICATION_OVERLAY 直接覆盖在设置页之上，不依赖 CaptureService。
 * 需要 SYSTEM_ALERT_WINDOW 权限（项目悬浮球功能本来就要求过）；没授权时 addView 静默失败。
 *
 * 使用方式：SettingsScreen 在 `floatingDockInset` 变化时调 [update]，slider section dispose 时调 [hide]。
 */
internal class EdgeInsetPreviewOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var leftView: View? = null
    private var rightView: View? = null
    private var currentInsetPx: Int = -1

    /** inset ≤ 0 时自动 hide，否则贴 left/right 各一条 [insetPx] 宽度的粉色条。 */
    fun update(insetPx: Int) {
        if (insetPx <= 0) { hide(); return }
        if (insetPx == currentInsetPx && leftView != null) return
        currentInsetPx = insetPx
        if (leftView == null) {
            leftView = makeBar().also {
                runCatching { wm.addView(it, paramsForSide(insetPx, leftSide = true)) }
            }
            rightView = makeBar().also {
                runCatching { wm.addView(it, paramsForSide(insetPx, leftSide = false)) }
            }
        } else {
            runCatching { wm.updateViewLayout(leftView, paramsForSide(insetPx, leftSide = true)) }
            runCatching { wm.updateViewLayout(rightView, paramsForSide(insetPx, leftSide = false)) }
        }
    }

    fun hide() {
        leftView?.let { runCatching { wm.removeView(it) } }
        rightView?.let { runCatching { wm.removeView(it) } }
        leftView = null
        rightView = null
        currentInsetPx = -1
    }

    private fun makeBar(): View = View(context).apply {
        // 半透粉红：~33% alpha 让设置页内容仍透过来可读
        setBackgroundColor(BAR_COLOR)
    }

    private fun paramsForSide(insetPx: Int, leftSide: Boolean): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            insetPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (leftSide) Gravity.START else Gravity.END)
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    companion object {
        private const val BAR_COLOR: Int = 0x55FF4081.toInt()
    }
}
