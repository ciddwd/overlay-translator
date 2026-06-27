package com.gameocr.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 可拖拽 + 可缩放的悬浮窗口外壳。**不拦截窗外触摸**——窗口尺寸 = 内容尺寸（非 MATCH_PARENT）
 * + `FLAG_NOT_TOUCH_MODAL`，下层 app 的 touch 天然透传（Android 12+ untrusted touch 限制只针对
 * `FLAG_NOT_TOUCHABLE`，本类不踩）。
 *
 * 用法：
 * ```
 * window.show(myContentView, onDismiss = { ... })
 * window.setContent(newContent)   // 替换内容不重建 window
 * window.hide()
 * ```
 *
 * UI 结构（VERTICAL LinearLayout 作为外壳）：
 * - header 32dp：左侧拖拽手柄（灰色短条）+ 右侧关闭按钮 ✕
 * - 中间 ScrollView 包内容
 * - footer 18dp：右下角 resize 把手（三角形）
 *
 * 拖动 / 缩放结束（UP 事件）后通过 [ioScope] 写回 [SettingsRepository] 持久化。
 */
class DraggableOverlayWindow(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val ioScope: CoroutineScope
) {
    /** 锁定后禁用拖拽 / resize（按钮关闭仍可用）。 */
    @Volatile var locked: Boolean = false

    @Volatile var theme: OverlayTheme = OverlayTheme.CLASSIC_DARK
    @Volatile var alpha: Float = 0.85f
    @Volatile var customBg: Int = 0xE6000000.toInt()
    @Volatile var customFg: Int = 0xFFFFFFFF.toInt()
    @Volatile var customBorder: Int = 0
    @Volatile var customBorderWidthDp: Int = 0
    @Volatile var borderStyle: BorderStyle = BorderStyle.SOLID

    /** -1 表示首次未保存 → 居中。 */
    @Volatile var initialX: Int = -1
    @Volatile var initialY: Int = -1
    @Volatile var widthDp: Int = 320
    @Volatile var heightDp: Int = 180

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm: WindowManager by lazy { createDisplayBoundWm() }

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var contentSlot: FrameLayout? = null
    /** show / 旋转后缓存的屏幕尺寸——给 onConfigurationChanged 做 ratio 重算的基准。
     *  Google 推荐做法：存 x/screenW 的 ratio 而不是绝对像素。 */
    private var lastKnownScreenW: Int = 0
    private var lastKnownScreenH: Int = 0
    private var headerView: View? = null
    private var footerView: View? = null
    private var lockButtonView: ImageView? = null
    private var onDismiss: (() -> Unit)? = null

    fun isShown(): Boolean = rootView != null

    /** 当前窗口在屏幕坐标系的矩形（x,y 是 gravity=TOP|START 下的 layoutParams.x/y）。未显示时返回 null。 */
    fun currentBounds(): android.graphics.Rect? {
        val p = layoutParams ?: return null
        return android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    /** 显示窗口；[onDismiss] 在用户点击 ✕ 时调用。重复 show 会先 hide。 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(content: View, onDismiss: (() -> Unit)? = null) {
        if (isShown()) hide()
        this.onDismiss = onDismiss

        val density = context.resources.displayMetrics.density
        val w = (widthDp.coerceAtLeast(MIN_WIDTH_DP) * density).roundToInt()
        val h = (heightDp.coerceAtLeast(MIN_HEIGHT_DP) * density).roundToInt()
        val (screenW, screenH) = currentScreenSize()

        val root = buildShell(content)

        // 兜底策略：
        // 1) 完全屏外 → 居中（跨设备 / 跨分辨率切换时上次位置废了）
        // 2) y < 状态栏高度 → 推下来到状态栏下方（避免 header 被状态栏遮挡 + 系统下拉手势抢走）
        // 3) 否则保留用户拖动位置
        // 旋转屏幕会让原 y 落到状态栏后面是核心场景。
        val centerX = ((screenW - w) / 2).coerceAtLeast(0)
        val centerY = ((screenH - h) / 2).coerceAtLeast(0)
        val statusBarH = statusBarHeightPx()
        val finalX = when {
            initialX + w <= 0 -> centerX
            initialX >= screenW -> centerX
            else -> initialX.coerceIn(0, (screenW - w).coerceAtLeast(0))
        }
        val finalY = when {
            initialY + h <= 0 -> centerY
            initialY >= screenH -> centerY
            initialY < statusBarH -> statusBarH.coerceAtMost((screenH - h).coerceAtLeast(0))
            else -> initialY.coerceIn(0, (screenH - h).coerceAtLeast(0))
        }
        val params = newLayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            x = finalX
            y = finalY
        }

        runCatching { wm.addView(root, params) }
        rootView = root
        layoutParams = params
        lastKnownScreenW = screenW
        lastKnownScreenH = screenH
        applyLocked()
    }

    /**
     * Service.onConfigurationChanged 调用——屏幕旋转 / 折叠状态切换 / 跨屏拖动时按比例
     * 重算悬浮窗位置，避免竖屏 y=200 旋转到横屏后落到状态栏后。Google 官方推荐做法。
     */
    fun onConfigurationChanged() {
        val v = rootView ?: return
        val params = layoutParams ?: return
        val (newScreenW, newScreenH) = currentScreenSize()
        if (newScreenW <= 0 || newScreenH <= 0) return
        if (newScreenW == lastKnownScreenW && newScreenH == lastKnownScreenH) return
        val oldW = lastKnownScreenW.takeIf { it > 0 } ?: newScreenW
        val oldH = lastKnownScreenH.takeIf { it > 0 } ?: newScreenH
        val ratioX = params.x.toFloat() / oldW
        val ratioY = params.y.toFloat() / oldH
        val w = params.width
        val h = params.height
        val statusBarH = statusBarHeightPx()
        val newX = (ratioX * newScreenW).toInt().coerceIn(0, (newScreenW - w).coerceAtLeast(0))
        val newY = ((ratioY * newScreenH).toInt())
            .coerceIn(statusBarH.coerceAtMost((newScreenH - h).coerceAtLeast(0)),
                (newScreenH - h).coerceAtLeast(0))
        params.x = newX
        params.y = newY
        // resize 兜底：上次大小可能超过新屏（横竖切换）
        if (w > newScreenW) params.width = newScreenW
        if (h > newScreenH) params.height = newScreenH
        runCatching { wm.updateViewLayout(v, params) }
        lastKnownScreenW = newScreenW
        lastKnownScreenH = newScreenH
        initialX = newX
        initialY = newY
        widthDp = (params.width / context.resources.displayMetrics.density).toInt()
        heightDp = (params.height / context.resources.displayMetrics.density).toInt()
        persistGeometry()
    }

    /** 替换内容区 View。不重建 window，避免每次切换内容都触发闪烁。 */
    fun setContent(content: View) {
        val slot = contentSlot ?: return
        slot.removeAllViews()
        slot.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun hide() {
        val v = rootView ?: return
        runCatching { wm.removeView(v) }
        rootView = null
        layoutParams = null
        contentSlot = null
        headerView = null
        footerView = null
        lockButtonView = null
        lastKnownScreenW = 0
        lastKnownScreenH = 0
        onDismiss = null
    }

    /** 重置位置 + 大小到默认（居中 + 默认尺寸）。 */
    fun resetToDefault() {
        initialX = -1
        initialY = -1
        widthDp = DEFAULT_WIDTH_DP
        heightDp = DEFAULT_HEIGHT_DP
        val v = rootView ?: return
        val params = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).roundToInt()
        val h = (heightDp * density).roundToInt()
        val (screenW, screenH) = currentScreenSize()
        params.width = w
        params.height = h
        params.x = ((screenW - w) / 2).coerceAtLeast(0)
        params.y = ((screenH - h) / 2).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, params) }
        persistGeometry()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildShell(content: View): View {
        val density = context.resources.displayMetrics.density
        val headerH = (HEADER_HEIGHT_DP * density).roundToInt()
        val lockBtnSize = (LOCK_BTN_DP * density).roundToInt()

        // root = FrameLayout：里面叠两层 —— 内 wrap（VERTICAL 装 header/content/footer）+
        // 独立的锁按钮（右上角始终可见）。锁定时只隐藏内 wrap 的 header/footer，锁按钮一直在，
        // 让用户随时能解锁。
        val root = FrameLayout(context).apply {
            background = shellBackground()
            this.alpha = this@DraggableOverlayWindow.alpha
            clipToOutline = true
        }

        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // —— header：居中拖拽手柄 + 右侧 ✕ 关闭按钮。锁按钮独立到左上角（见下文）。
        // 锁定时整条 header GONE，✕ 自动跟着隐藏，避免误触关闭。
        val header = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(0x40, 0, 0, 0))
        }
        val dragHandleBar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(themeFgMutedColor())
            }
        }
        header.addView(
            dragHandleBar,
            FrameLayout.LayoutParams(
                (32 * density).roundToInt(), (4 * density).roundToInt(), Gravity.CENTER
            )
        )

        val closeBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(themeFgColor())
            val pad = (6 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onDismiss?.invoke() ?: hide() }
        }
        header.addView(
            closeBtn,
            FrameLayout.LayoutParams(
                (28 * density).roundToInt(),
                (28 * density).roundToInt(),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = (4 * density).roundToInt() }
        )
        attachDragListener(header)
        wrap.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH))

        // —— content（中间，weight=1 撑满；用 ScrollView 包裹保证溢出可滚） ——
        val slot = FrameLayout(context)
        slot.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                slot,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        contentSlot = slot
        wrap.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // —— footer：整条接管 resize touch（视觉上只画右下角一条灰条作为提示）——
        // 不再把 listener 挂在 handle 子 View 上：32×4dp 的有效触摸面积太小，用户手指
        // 几乎按不准。整条 footer 都响应 resize，触摸面积 = window 宽 × 24dp，~60 倍。
        val footer = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(0x30, 0, 0, 0))
        }
        val resizeHandle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(themeFgMutedColor())
            }
        }
        footer.addView(
            resizeHandle,
            FrameLayout.LayoutParams((40 * density).roundToInt(), (6 * density).roundToInt(),
                Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { rightMargin = (8 * density).roundToInt() }
        )
        attachResizeListener(footer)
        wrap.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (FOOTER_HEIGHT_DP * density).roundToInt()
        ))

        // wrap 加到 root，撑满整个 window
        root.addView(
            wrap,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // —— 独立的锁按钮（始终居左上角；锁定状态下 header/footer GONE 仍可见，便于解锁）——
        val lockBtn = ImageView(context).apply {
            setImageResource(lockIconRes(locked))
            imageTintList = android.content.res.ColorStateList.valueOf(themeFgColor())
            // 不要椭圆灰底——纯图标更干净；padding 仍保证整个 LOCK_BTN_DP 都是触摸响应区
            val pad = (5 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleLocked() }
        }
        root.addView(
            lockBtn,
            FrameLayout.LayoutParams(lockBtnSize, lockBtnSize, Gravity.START or Gravity.TOP).apply {
                topMargin = (4 * density).roundToInt()
                leftMargin = (4 * density).roundToInt()
            }
        )

        headerView = header
        footerView = footer
        lockButtonView = lockBtn
        return root
    }

    private fun lockIconRes(locked: Boolean): Int =
        if (locked) com.gameocr.app.R.drawable.ic_menu_lock
        else com.gameocr.app.R.drawable.ic_menu_lock_open

    /** 切换锁定状态：更新视图 + 立即写回 [SettingsRepository]，UI 端的开关跟着 settings flow 同步。 */
    private fun toggleLocked() {
        locked = !locked
        applyLocked()
        val newLocked = locked
        ioScope.launch {
            settingsRepository.update { it.copy(floatingWindowLocked = newLocked) }
        }
    }

    /** 应用 locked 字段到视图：锁定时 header/footer GONE（✕ 自动随 header 隐藏），
     *  锁按钮换实心锁；解锁时全部恢复 + 锁按钮换开锁图标。 */
    private fun applyLocked() {
        val vis = if (locked) View.GONE else View.VISIBLE
        headerView?.visibility = vis
        footerView?.visibility = vis
        lockButtonView?.setImageResource(lockIconRes(locked))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(handle: View) {
        var startTouchX = 0f
        var startTouchY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, ev ->
            if (locked) return@setOnTouchListener false
            val params = layoutParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = ev.rawX
                    startTouchY = ev.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startTouchX).toInt()
                    val dy = (ev.rawY - startTouchY).toInt()
                    val (screenW, screenH) = currentScreenSize()
                    params.x = (startX + dx).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    params.y = (startY + dy).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                    val v = rootView ?: return@setOnTouchListener false
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    initialX = params.x
                    initialY = params.y
                    persistGeometry()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachResizeListener(handle: View) {
        var startTouchX = 0f
        var startTouchY = 0f
        var startW = 0
        var startH = 0
        val density = context.resources.displayMetrics.density
        handle.setOnTouchListener { _, ev ->
            if (locked) return@setOnTouchListener false
            val params = layoutParams ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = ev.rawX
                    startTouchY = ev.rawY
                    startW = params.width
                    startH = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startTouchX).toInt()
                    val dy = (ev.rawY - startTouchY).toInt()
                    val (screenW, screenH) = currentScreenSize()
                    val minW = (MIN_WIDTH_DP * density).roundToInt()
                    val minH = (MIN_HEIGHT_DP * density).roundToInt()
                    val maxW = (screenW - params.x).coerceAtLeast(minW)
                    val maxH = (screenH - params.y).coerceAtLeast(minH)
                    params.width = (startW + dx).coerceIn(minW, maxW)
                    params.height = (startH + dy).coerceIn(minH, maxH)
                    val v = rootView ?: return@setOnTouchListener false
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    widthDp = (params.width / density).roundToInt()
                    heightDp = (params.height / density).roundToInt()
                    persistGeometry()
                    true
                }
                else -> false
            }
        }
    }

    private fun persistGeometry() {
        val x = initialX
        val y = initialY
        val w = widthDp
        val h = heightDp
        ioScope.launch {
            settingsRepository.update {
                it.copy(
                    floatingWindowX = x,
                    floatingWindowY = y,
                    floatingWindowWidthDp = w,
                    floatingWindowHeightDp = h
                )
            }
        }
    }

    /** 状态栏 + cutout 顶部高度（动态获取，跟随旋转）。横屏时通常为 0 或很小；竖屏在 24-32dp 之间。
     *  旋转屏幕后用户原 y 坐标可能落到新方向的状态栏后面，用这个值推下来避开。 */
    private fun statusBarHeightPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.displayCutout()
                ).top
            }.getOrDefault(0)
        }
        // 老 API fallback：读 android internal dimen，拿不到就当 24dp
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId)
            else (24 * context.resources.displayMetrics.density).toInt()
    }

    /**
     * 屏幕尺寸（API 30+ 跟随旋转的 currentWindowMetrics；老 API 退回 defaultDisplay）。
     * 仿 [FloatingButtonManager]。
     */
    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            metrics.bounds.width() to metrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun createDisplayBoundWm(): WindowManager {
        val defaultWm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return defaultWm
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?: return@runCatching defaultWm
            val windowContext = context.createWindowContext(display, overlayType, null)
            windowContext.getSystemService(WindowManager::class.java) ?: defaultWm
        }.getOrElse { defaultWm }
    }

    /**
     * 构造 overlay 通用 LayoutParams。关键：
     * - 宽高 = 内容尺寸（**非 MATCH_PARENT**）→ 窗外触摸天然透传
     * - `FLAG_NOT_FOCUSABLE` 不抢焦点
     * - `FLAG_NOT_TOUCH_MODAL` 窗外 touch 走到下层
     * - 不带 `FLAG_NOT_TOUCHABLE`，避免 Android 12+ untrusted-touch 限制
     */
    private fun newLayoutParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // 历史教训：曾经加过 FLAG_SECURE 想让 MediaProjection 自动跳过自己的悬浮窗——
            // 但 MIUI / HyperOS 的反盗版逻辑会"屏幕上一旦有 FLAG_SECURE 层就整张 MediaProjection
            // 输出黑屏 / 让 Shizuku screencap exit=1"。代价远大于自循环防护。已撤回。
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }

    /**
     * 悬浮窗外壳背景。按 [borderStyle] 切不同 Drawable：
     * - SOLID/DASHED/DOTTED：单 GradientDrawable，setStroke 4-arg 自带 dash 支持
     * - DOUBLE：LayerDrawable 外层 stroke + 内层 inset stroke（间隙模拟双边）
     * - GROOVE：LayerDrawable，外层暗色 + 内层亮色 stroke，模拟凹槽
     * 主题本身无 stroke（如 CLASSIC_DARK）时 borderStyle 不生效。
     */
    private fun shellBackground(): android.graphics.drawable.Drawable {
        val bgColor = themeBgColor()
        val (strokeWidthDp, strokeColor) = themeStroke()
        val density = context.resources.displayMetrics.density

        if (strokeWidthDp <= 0) {
            return GradientDrawable().apply {
                cornerRadius = 16f
                setColor(bgColor)
            }
        }
        val px = (strokeWidthDp * density).toInt().coerceAtLeast(1)

        // borderStyle 仅在 CUSTOM 主题下生效——预设主题（AMBER/PAPER/FROST）的边框是设计师调好的实线，
        // 用户调 borderStyle 不应改主题预设的视觉。
        if (theme != OverlayTheme.CUSTOM) {
            return GradientDrawable().apply {
                cornerRadius = 16f
                setColor(bgColor)
                setStroke(px, strokeColor)
            }
        }

        return when (borderStyle) {
            BorderStyle.SOLID -> GradientDrawable().apply {
                cornerRadius = 16f
                setColor(bgColor)
                setStroke(px, strokeColor)
            }
            BorderStyle.DASHED -> GradientDrawable().apply {
                cornerRadius = 16f
                setColor(bgColor)
                setStroke(px, strokeColor, 8f * density, 5f * density)
            }
            BorderStyle.DOTTED -> GradientDrawable().apply {
                cornerRadius = 16f
                setColor(bgColor)
                setStroke(px, strokeColor, 2f * density, 3f * density)
            }
            BorderStyle.DOUBLE -> {
                // 外圈画背景 + stroke；内圈透明 + 同色 stroke，往内 inset 让两条线之间留间隙
                val gap = px + (3 * density).toInt()
                val outer = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(bgColor)
                    setStroke(px, strokeColor)
                }
                val inner = GradientDrawable().apply {
                    cornerRadius = (16f - gap / density).coerceAtLeast(8f)
                    setColor(Color.TRANSPARENT)
                    setStroke(px, strokeColor)
                }
                android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply {
                    setLayerInset(1, gap, gap, gap, gap)
                }
            }
            BorderStyle.GROOVE -> {
                // 外层暗色 + 内层亮色，凹槽视觉
                val outer = GradientDrawable().apply {
                    cornerRadius = 16f
                    setColor(bgColor)
                    setStroke(px, shadeColor(strokeColor, -0.4f))
                }
                val inner = GradientDrawable().apply {
                    cornerRadius = (16f - px / density).coerceAtLeast(8f)
                    setColor(Color.TRANSPARENT)
                    setStroke(px, shadeColor(strokeColor, 0.4f))
                }
                android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply {
                    setLayerInset(1, px, px, px, px)
                }
            }
        }
    }

    private fun themeBgColor(): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xE6000000.toInt()
        OverlayTheme.AMBER_GOLD -> 0xF0241608.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xF0F5EFE0.toInt()
        OverlayTheme.FROST_GLASS -> 0xCC1E293B.toInt()
        OverlayTheme.CUSTOM -> customBg
    }

    /** 主题边框：(widthDp, color)。widthDp = 0 表示无边。 */
    private fun themeStroke(): Pair<Int, Int> = when (theme) {
        OverlayTheme.AMBER_GOLD -> 2 to 0xFFB8860B.toInt()
        OverlayTheme.PAPER_LIGHT -> 1 to 0xFFB68850.toInt()
        OverlayTheme.FROST_GLASS -> 1 to 0xFF60A5FA.toInt()
        OverlayTheme.CUSTOM -> if (customBorderWidthDp > 0) customBorderWidthDp to customBorder else 0 to 0
        else -> 0 to 0
    }

    /** factor > 0 变亮，< 0 变暗。保持 alpha 不变。 */
    private fun shadeColor(color: Int, factor: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val nr = if (factor >= 0) r + ((255 - r) * factor).toInt() else (r * (1 + factor)).toInt()
        val ng = if (factor >= 0) g + ((255 - g) * factor).toInt() else (g * (1 + factor)).toInt()
        val nb = if (factor >= 0) b + ((255 - b) * factor).toInt() else (b * (1 + factor)).toInt()
        return (a shl 24) or
            (nr.coerceIn(0, 255) shl 16) or
            (ng.coerceIn(0, 255) shl 8) or
            nb.coerceIn(0, 255)
    }

    private fun themeFgColor(): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFFFFFFFF.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFFFD27F.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFF3E2A1F.toInt()
        OverlayTheme.FROST_GLASS -> 0xFFE0F2FE.toInt()
        OverlayTheme.CUSTOM -> customFg
    }

    private fun themeFgMutedColor(): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0x80B0BEC5.toInt()
        OverlayTheme.AMBER_GOLD -> 0x80B68850.toInt()
        OverlayTheme.PAPER_LIGHT -> 0x808B6F47.toInt()
        OverlayTheme.FROST_GLASS -> 0x8094A3B8.toInt()
        OverlayTheme.CUSTOM -> (customFg and 0xFFFFFF) or 0x80000000.toInt()
    }

    companion object {
        const val DEFAULT_WIDTH_DP = 320
        const val DEFAULT_HEIGHT_DP = 180
        const val MIN_WIDTH_DP = 160
        const val MIN_HEIGHT_DP = 100
        const val HEADER_HEIGHT_DP = 32
        const val FOOTER_HEIGHT_DP = 24
        const val LOCK_BTN_DP = 30
    }

    /** 在 [Settings] 内核心字段变化时一次性同步全部参数。
     *  如果窗口已显示，背景 / alpha 立即生效（用户在 Settings 改完配色保存后无需等下一次翻译）。
     *  注意：内容里的 TextView 文字颜色由 [OverlayManager] 控制，需要 OverlayManager 配合 rebuild content。
     *
     *  **线程约束：必须在主线程调用**。末尾会改 rootView.background / rootView.alpha，
     *  View 系统只接受主线程操作。settings flow 默认在 Dispatchers.Default，调用方必须 withContext(Main)
     *  或 mainScope.launch 包一层。 */
    fun applyFromSettings(s: Settings) {
        theme = s.overlayTheme
        alpha = s.overlayAlpha
        customBg = s.customBgColor
        customFg = s.customFgColor
        customBorder = s.customBorderColor
        customBorderWidthDp = s.customBorderWidth
        borderStyle = s.customBorderStyle
        initialX = s.floatingWindowX
        initialY = s.floatingWindowY
        widthDp = s.floatingWindowWidthDp.coerceAtLeast(MIN_WIDTH_DP)
        heightDp = s.floatingWindowHeightDp.coerceAtLeast(MIN_HEIGHT_DP)
        if (locked != s.floatingWindowLocked) {
            locked = s.floatingWindowLocked
            if (isShown()) applyLocked()
        }
        // 已显示时刷新 root 背景 + alpha（主题色 / 自定义色 / 边框立即生效）
        rootView?.let {
            it.background = shellBackground()
            it.alpha = this.alpha
        }
    }
}
