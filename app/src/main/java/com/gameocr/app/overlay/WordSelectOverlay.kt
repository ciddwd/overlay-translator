package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.gameocr.app.R
import com.gameocr.app.capture.RegionPickerView

/**
 * 划词翻译用的悬浮窗框选层。复用 [RegionPickerView] 的双阶段拖框 / handle 微调能力，但与
 * [RegionPickerOverlay] 有两点不同：
 *  - 语义是「一次性翻译」，**不**把 rect 写回 Settings.captureRegion；
 *  - toolbar 只有「翻译 / 取消 / 重画」三个键，翻译键文案区别于「确认」。
 *
 * 与 RegionPickerOverlay 共享：display-bound WindowManager、cutout layout 一致性，确保
 * 选区坐标对齐物理屏幕原点。
 */
class WordSelectOverlay(private val context: Context) {

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
            val dm = context.getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching defaultWm
            val windowContext = context.createWindowContext(display, overlayType, null)
            windowContext.getSystemService(WindowManager::class.java) ?: defaultWm
        }.getOrElse { defaultWm }
    }

    private var container: FrameLayout? = null

    fun isShown(): Boolean = container != null

    fun show(
        onTranslate: (Rect) -> Unit,
        onCancel: () -> Unit,
        initial: Rect? = null,
        skipAdjustment: Boolean = false,
    ) {
        if (container != null) return

        val picker = RegionPickerView(
            context = context,
            initial = initial,
            onCancel = onCancel,
            // 划词模式下不支持「双击=清除」语义，双击直接 cancel。
            onClearAllRequested = { dismiss(); onCancel() },
            skipAdjustment = skipAdjustment,
        )

        lateinit var doCancel: () -> Unit
        lateinit var doTranslate: () -> Unit
        val toolbar = buildToolbar(
            onRedo = { picker.resetToDrawing() },
            onCancel = { doCancel() },
            onTranslate = { doTranslate() }
        )

        // 自定义 FrameLayout：拦截返回键 = 取消划词。
        // window 去掉了 FLAG_NOT_FOCUSABLE 才能拿到按键事件，否则返回键漏到底层 app（游戏）。
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        doCancel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                picker,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                toolbar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = dp(16)
                    bottomMargin = dp(32)
                }
            )
        }
        container = root

        doCancel = {
            dismiss()
            onCancel()
        }
        doTranslate = {
            val r = picker.currentRect()
            if (r != null && r.width() >= 20 && r.height() >= 20) {
                dismiss()
                onTranslate(r)
            } else {
                dismiss()
                onCancel()
            }
        }

        picker.onDrawingComplete = { r ->
            dismiss()
            onTranslate(r)
        }

        val updateToolbarPos: (Rect?) -> Unit = { rect ->
            placeToolbar(root, toolbar, rect)
        }
        picker.onRectChanged = updateToolbarPos
        root.post { updateToolbarPos(picker.currentRect()) }

        val (physW, physH) = physicalScreenSize()
        // 不加 FLAG_NOT_FOCUSABLE：需要拿到按键事件来拦截返回键（避免漏给底层 app 导致游戏退出）。
        // 副作用同 RegionPickerOverlay，补 FLAG_ALT_FOCUSABLE_IM 防 IME 干扰。
        val params = WindowManager.LayoutParams(
            physW, physH,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        runCatching { wm.addView(root, params) }
    }

    private fun physicalScreenSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    fun dismiss() {
        container?.let { runCatching { wm.removeView(it) } }
        container = null
    }

    private fun buildToolbar(
        onRedo: () -> Unit,
        onCancel: () -> Unit,
        onTranslate: () -> Unit
    ): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xCC222222.toInt())
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(buildButton(context.getString(R.string.word_select_btn_redraw), onRedo))
            addView(buildButton(context.getString(R.string.word_select_btn_cancel), onCancel))
            addView(buildButton(context.getString(R.string.word_select_btn_translate), onTranslate, primary = true))
        }
    }

    private fun buildButton(text: String, onClick: () -> Unit, primary: Boolean = false): Button {
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else 0xFFE0E0E0.toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (primary) 0xFF1976D2.toInt() else 0xFF424242.toInt())
            }
            background = bg
            minWidth = dp(72)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun placeToolbar(container: FrameLayout, toolbar: ViewGroup, rect: Rect?) {
        val parentW = container.width
        val parentH = container.height
        val tbW = toolbar.width
        val tbH = toolbar.height
        if (tbW == 0 || tbH == 0 || parentW == 0 || parentH == 0) {
            container.post { placeToolbar(container, toolbar, rect) }
            return
        }
        val gap = dp(12)
        val safe = dp(16)
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams

        if (rect != null && rect.width() >= 20 && rect.height() >= 20) {
            var top = rect.bottom + gap
            if (top + tbH > parentH - safe) {
                top = rect.top - gap - tbH
            }
            if (top < safe) {
                top = parentH - safe - tbH
            }
            val left = (rect.centerX() - tbW / 2).coerceIn(safe, parentW - tbW - safe)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = left
            lp.topMargin = top
            lp.rightMargin = 0
            lp.bottomMargin = 0
        } else {
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.rightMargin = safe
            lp.bottomMargin = dp(32)
        }
        toolbar.layoutParams = lp
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()
}
