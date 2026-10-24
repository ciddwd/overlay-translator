package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.gameocr.app.R
import com.gameocr.app.capture.RegionPickerView
import com.gameocr.app.data.AppLocalePrefs
import com.gameocr.app.util.physicalDisplaySize

/**
 * 悬浮窗版区域选择。复用 [RegionPickerView] + 同款 toolbar，但用 [WindowManager.addView]
 * 覆盖在当前 app 之上，不切走前台 Activity——用户在游戏 / 漫画里操作不出戏，也不会
 * 重复入栈（[RegionPickerActivity] 那条 [android.content.Intent.FLAG_ACTIVITY_NEW_TASK]
 * 老路径只剩 MainScreen 仍在用）。
 *
 * 全屏 view + 不加 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] → 拦截所有触摸，
 * 拉框期间后台应用收不到误操作。
 */
class RegionPickerOverlay(private val context: Context) {

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    // The views and their WindowManager must share the same visual configuration. A Service
    // context can retain portrait maxBounds even while the overlay's display is landscape.
    private val windowContext: Context by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@lazy context
        runCatching {
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching context
            context.createWindowContext(display, overlayType, null)
        }.getOrElse {
            Log.w("RegionPicker", "Could not create a display-bound window context", it)
            context
        }
    }
    private val viewContext: Context by lazy { AppLocalePrefs.live(windowContext) }
    private val wm: WindowManager by lazy {
        windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observingDisplay = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onConfigurationChanged()
        }
    }

    private var container: FrameLayout? = null

    fun isShown(): Boolean = container != null

    fun show(
        initial: Rect?,
        onConfirm: (Rect) -> Unit,
        onCancel: () -> Unit,
        /** 双击选择整屏触发。语义跟主屏「清除选框」一致：清掉 captureRegion 让下次走整屏。
         *  默认走 onCancel（不清；保留兼容） */
        onClearAll: () -> Unit = onCancel
    ) {
        if (container != null) return

        lateinit var doClearAll: () -> Unit
        val picker = RegionPickerView(
            context = viewContext,
            initial = initial,
            onCancel = onCancel,
            onClearAllRequested = { doClearAll() }
        )

        lateinit var doCancel: () -> Unit
        lateinit var doConfirm: () -> Unit
        val toolbar = buildToolbar(
            onRedo = { picker.resetToDrawing() },
            onCancel = { doCancel() },
            onConfirm = { doConfirm() }
        )

        // 自定义 FrameLayout：拦截返回键 = 取消框选。
        // window 去掉了 FLAG_NOT_FOCUSABLE 才能拿到按键事件，否则返回键漏到底层 app（游戏）。
        val root = object : FrameLayout(viewContext) {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                super.onConfigurationChanged(newConfig)
                Log.i("RegionPicker", "configuration orientation=${newConfig.orientation}")
                this@RegionPickerOverlay.onConfigurationChanged()
            }

            override fun onDetachedFromWindow() {
                if (container === this) stopObservingDisplay()
                super.onDetachedFromWindow()
            }

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
        doConfirm = {
            val r = picker.currentRect()
            if (r != null && r.width() >= 20 && r.height() >= 20) {
                dismiss()
                onConfirm(r)
            } else {
                dismiss()
                onCancel()
            }
        }
        doClearAll = {
            dismiss()
            onClearAll()
        }

        val updateToolbarPos: (Rect?) -> Unit = { rect ->
            placeToolbar(root, toolbar, rect)
        }
        picker.onRectChanged = updateToolbarPos
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                Log.i("RegionPicker", "laidOut=${right - left}x${bottom - top}")
                root.post { updateToolbarPos(picker.currentRect()) }
            }
        }
        root.post { updateToolbarPos(picker.currentRect()) }

        // 拿屏幕物理真实分辨率（不受 inset / system bar / cutout 让位影响）。
        // MATCH_PARENT 在 HyperOS 横屏会被让出 cutout inset → window 左边露物理屏黑边。
        // 显式给具体像素值绕过 framework 解释。
        val (physW, physH) = physicalScreenSize()
        // 不加 FLAG_NOT_FOCUSABLE：需要拿到按键事件来拦截返回键（避免漏给底层 app 导致游戏退出）。
        // 副作用排查：
        //  - 失去 FLAG_NOT_TOUCH_MODAL：本来就要拦截所有触摸（全屏 MATCH_PARENT，无 window 外区域），无影响。
        //  - 抢底层 app focus：模态全屏选择期间用户只跟 overlay 交互，期望行为。
        //  - IME 交互：补 FLAG_ALT_FOCUSABLE_IM = "不与 IME 交互，且可覆盖 IME 区"，
        //    防御部分 ROM 在 focusable window 弹出时唤起输入法。
        val params = WindowManager.LayoutParams(
            physW, physH,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            title = "RegionPicker"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        runCatching {
            wm.addView(root, params)
            startObservingDisplay()
            onConfigurationChanged()
            Log.i("RegionPicker", "shown requested=${physW}x${physH}")
        }.onFailure {
            Log.w("RegionPicker", "Could not show the editor window", it)
            dismiss()
            onCancel()
        }
    }

    /** The editor uses explicit physical bounds, so an attached window must be resized too. */
    fun onConfigurationChanged() {
        val root = container ?: return
        refreshDisplayBounds(root)
        // The display event and the window's configuration can arrive in either order.
        root.post { if (container === root) refreshDisplayBounds(root) }
    }

    private fun startObservingDisplay() {
        if (observingDisplay) return
        val manager = displayManager ?: return
        manager.registerDisplayListener(displayListener, mainHandler)
        observingDisplay = true
    }

    private fun stopObservingDisplay() {
        if (!observingDisplay) return
        displayManager?.unregisterDisplayListener(displayListener)
        observingDisplay = false
    }

    private fun refreshDisplayBounds(root: FrameLayout) {
        if (container !== root) return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        val (width, height) = physicalScreenSize()
        if (params.width == width && params.height == height) return
        val previousWidth = params.width
        val previousHeight = params.height
        params.width = width
        params.height = height
        params.x = 0
        params.y = 0
        runCatching {
            wm.updateViewLayout(root, params)
            Log.i("RegionPicker", "resized=${previousWidth}x${previousHeight}->${width}x${height} " +
                "view=${root.width}x${root.height}")
        }.onFailure {
            params.width = previousWidth
            params.height = previousHeight
            Log.w("RegionPicker", "Could not resize the editor window", it)
        }
    }

    // The service receives current display bounds even when a ROM leaves an existing
    // WindowContext at its creation-time bounds. Read them again for every update;
    // the same source is used when opening the editor and saving the region's coordinate basis.
    private fun physicalScreenSize(): Pair<Int, Int> {
        val size = physicalDisplaySize(context)
        return size.width to size.height
    }

    fun dismiss() {
        stopObservingDisplay()
        val root = container
        container = null
        root?.let { runCatching { wm.removeView(it) } }
    }

    private fun buildToolbar(
        onRedo: () -> Unit,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xCC222222.toInt())
        }
        return LinearLayout(viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(buildButton(viewContext.getString(R.string.region_picker_btn_redo), onRedo))
            addView(buildButton(viewContext.getString(R.string.region_picker_btn_cancel), onCancel))
            addView(buildButton(viewContext.getString(R.string.region_picker_btn_confirm), onConfirm, primary = true))
        }
    }

    private fun buildButton(text: String, onClick: () -> Unit, primary: Boolean = false): Button {
        return Button(viewContext).apply {
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

    /** rect 变化时把 toolbar 重新摆位（贴 rect 下方/上方/底部，避开选区）。 */
    private fun placeToolbar(container: FrameLayout, toolbar: ViewGroup, rect: Rect?) {
        if (this.container !== container) return
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
            if (top < safe) top = parentH - safe - tbH
            val minLeft = safe.coerceAtMost((parentW - tbW).coerceAtLeast(0))
            val maxLeft = (parentW - tbW - safe).coerceAtLeast(minLeft)
            val minTop = safe.coerceAtMost((parentH - tbH).coerceAtLeast(0))
            val maxTop = (parentH - tbH - safe).coerceAtLeast(minTop)
            val left = (rect.centerX() - tbW / 2).coerceIn(minLeft, maxLeft)
            top = top.coerceIn(minTop, maxTop)
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
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), viewContext.resources.displayMetrics
        ).toInt()
}
