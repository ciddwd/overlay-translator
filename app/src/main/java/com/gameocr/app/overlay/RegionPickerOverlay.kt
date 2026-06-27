package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.gameocr.app.R
import com.gameocr.app.capture.RegionPickerView

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

    /**
     * 拿跟随屏幕旋转的 WindowManager（仿 [com.gameocr.app.overlay.FloatingButtonManager]）。
     * Service / Application context 的默认 WindowManager 不跟随旋转——横屏 cutout 让出 inset 后
     * window 原点被推到物理 cutout 之后，左边露出物理屏黑边。API 31+ 用
     * `createWindowContext(display, type, options)` 拿挂在指定 display 上的 context，其 WM
     * 会跟随该 display 当前方向。API < 31 退回默认 wm（已知 limitation）。
     */
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
            context = context,
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

        val root = FrameLayout(context).apply {
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
        root.post { updateToolbarPos(picker.currentRect()) }

        // 拿屏幕物理真实分辨率（不受 inset / system bar / cutout 让位影响）。
        // MATCH_PARENT 在 HyperOS 横屏会被让出 cutout inset → window 左边露物理屏黑边。
        // 显式给具体像素值绕过 framework 解释。
        val (physW, physH) = physicalScreenSize()
        val params = WindowManager.LayoutParams(
            physW, physH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    /** 屏幕物理真实分辨率。直接用 [android.view.Display.getRealMetrics]——这是 framework 原生接口，
     *  返回的是物理屏的真实分辨率，跟 cutout / system bar / inset 无关。
     *  比 [WindowManager.getCurrentWindowMetrics] 可靠：后者在挂 window context 的 wm 上可能返回
     *  工作区而非物理屏（HyperOS 横屏实测）。 */
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
        onConfirm: () -> Unit
    ): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xCC222222.toInt())
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(buildButton(context.getString(R.string.region_picker_btn_redo), onRedo))
            addView(buildButton(context.getString(R.string.region_picker_btn_cancel), onCancel))
            addView(buildButton(context.getString(R.string.region_picker_btn_confirm), onConfirm, primary = true))
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

    /** rect 变化时把 toolbar 重新摆位（贴 rect 下方/上方/底部，避开选区）。 */
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
