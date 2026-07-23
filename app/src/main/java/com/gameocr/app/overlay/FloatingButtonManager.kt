package com.gameocr.app.overlay

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.gameocr.app.R
import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.MenuItemId
import com.gameocr.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

private typealias DockSide = LiquidFloatingContainer.DockSide

internal object ArcMenuGeometry {
    fun spreadFor(itemCount: Int): Double = when {
        itemCount <= 1 -> 0.0
        itemCount == 2 -> Math.PI / 6
        itemCount == 3 -> Math.PI / 4
        itemCount == 4 -> Math.toRadians(54.0)
        itemCount == 5 -> Math.toRadians(72.0)
        else -> Math.PI / 2
    }
}

/**
 * 悬浮触发按钮。
 * - 单击 → [onSingleTap] 按当前主球模式执行单次、划词或循环启停。
 * - 长按 → 弹出可分页半圆弧菜单；模式项切换主球图标与后续单击行为。
 *
 * 拖动跟系统拨号一样：手指按住后跟随移动，松开后用 SpringAnimation 弹性吸附到最近的左/右边，
 * 位置写回 [SettingsRepository] 持久化，下次启动 Service 还原。
 */
class FloatingButtonManager(
    private val context: Context,
    private val onSingleTap: () -> Unit,
    private val onSwitchToLoop: () -> Unit,
    private val settingsRepository: SettingsRepository,
    private val ioScope: CoroutineScope
) {
    @Volatile var sizeDp: Int = 56
    /** 初始位置：构造后由 CaptureService 注入；若 ≥ 0 则 show() 时优先使用。 */
    @Volatile var initialX: Int = -1
    @Volatile var initialY: Int = -1
    /** 菜单第二项「截图区域调整」回调，由 CaptureService 赋值。 */
    @Volatile var onMenuPickRegion: () -> Unit = {}
    @Volatile var onMenuLanguagePair: () -> Unit = {}
    /** 菜单第三项「返回主应用」回调，由 CaptureService 赋值。 */
    @Volatile var onMenuOpenMainActivity: () -> Unit = {}
    @Volatile var onMenuOpenSettings: () -> Unit = {}
    @Volatile var onMenuPresetSwitch: () -> Unit = {}
    /** 吸附边缘开关（用户在 Settings 里可关）。关时松手保持原位 + 不藏半边。 */
    @Volatile var snapToEdgeEnabled: Boolean = true
    /** 3s 无操作自动吸附。需 [snapToEdgeEnabled] 同时为 true 才生效（由 [scheduleAutoDock] 守门）。 */
    @Volatile var autoDockEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) cancelAutoDock()
        }
    /**
     * 吸附距实际屏幕边的内偏移（px）。0 = 紧贴系统边。由 Settings 的 dp 值转换后注入。
     *
     * 球当前 dock 着时改 inset 会立刻触发一次 snapToEdge 让它平滑滑到新位置——否则用户在
     * 设置里改完保存后还得手动拖一下才能看到新边距。
     */
    @Volatile var dockEdgeInsetPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            val v = view ?: return
            if (dockSide != DockSide.NONE) v.post { snapToEdge() }
        }
    /** 当前是否处于循环模式（影响菜单第一项的视觉指示）。由 CaptureService 通过 setLoopActive 同步。 */
    @Volatile private var isLooping: Boolean = false

    /**
     * 主球当前技能。决定 [onSingleTap] 触发的行为 + 球本体显示的图标。
     * 由 CaptureService 在 settings collect 同步；菜单点「技能切换」按钮时也走它。
     */
    @Volatile var skill: FloatingSkill = FloatingSkill.FULL_SCREEN

    /** 弧菜单按钮顺序（来自 Settings.floatingMenuItemOrder）。CaptureService 在 settings collect 时同步。 */
    @Volatile var menuItemOrder: List<MenuItemId> = FloatingMenu.DEFAULT_ORDER
    @Volatile var arcMenuPageSize: Int = FloatingMenu.DEFAULT_PAGE_SIZE
    @Volatile var firstUseTourPending: Boolean = false
    @Volatile var onFirstUseTourCompleted: () -> Unit = {}

    /**
     * 主球技能切换回调：菜单里点了「划词翻译 / 全屏翻译」时调用。
     * 由 CaptureService 注入，负责持久化 + 弹 info 提示 + 同步图标。
     */
    @Volatile var onSwitchSkill: (FloatingSkill) -> Unit = {}

    /** 主球图标 ImageView 引用，applySkillIcon 时改 src。 */
    private var mainIcon: ImageView? = null

    /**
     * 长按腾位：弹菜单前若球距屏幕边不够展开扇形，把球 spring 到安全位置；菜单关闭时再 spring 回原位。
     * 非 null 表示当前菜单是「腾位后弹的」，dismissArcMenu 末尾负责回滚。
     */
    private var positionBeforeMenu: Pair<Int, Int>? = null


    /**
     * Service / Application context 的默认 WindowManager 不跟随屏幕旋转（旋转后 currentWindowMetrics
     * 仍返回构造时方向）。Android 11+ 用 `createWindowContext(display, type, ...)` 拿一个
     * **挂在指定 display 上的 context**，其 WindowManager 会跟随该 display 当前方向。
     * API < 30 没这个 API，退回默认 wm（横竖屏切换时有 known limitation）。
     */
    private val wm: WindowManager by lazy { createDisplayBoundWm() }

    private fun createDisplayBoundWm(): WindowManager {
        val defaultWm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // createWindowContext(display, type, options) 是 API 31 (S)，2-arg 版本 (API 30) 不绑定指定 display
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return defaultWm
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return@runCatching defaultWm
            val windowContext = context.createWindowContext(display, overlayType, null)
            windowContext.getSystemService(WindowManager::class.java) ?: defaultWm
        }.getOrElse {
            android.util.Log.e("FBM", "createWindowContext failed", it)
            defaultWm
        }
    }
    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var progressView: LoopProgressView? = null
    private var longPressGuideView: LongPressGuideView? = null

    private var snapAnimX: SpringAnimation? = null
    private var snapAnimY: SpringAnimation? = null
    private var arcMenuView: View? = null
    private var tourStage: TourStage = TourStage.NONE
    private var tourPlan: List<FloatingMenuTourPage> = emptyList()
    private var tourPageIndex: Int = 0
    private var tourTargetIndex: Int = 0
    private var tourMenuItems: List<MenuItem> = emptyList()
    private var tourMenuButtons: List<ImageView> = emptyList()
    private var tourPulseAnimator: ValueAnimator? = null
    private var tourPulseTarget: View? = null
    private val tourOverlay by lazy {
        FloatingMenuTourOverlay(context, wm, overlayType)
    }
    private val longPressCueOverlay by lazy {
        FloatingLongPressCueOverlay(context, wm, overlayType)
    }

    private val autoDockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoDockRunnable = Runnable {
        // 触发时再校验一次：吸附总开关开、autoDock 开、球已 show、当前未在 dock 态。
        val v = view ?: return@Runnable
        if (!snapToEdgeEnabled || !autoDockEnabled) return@Runnable
        if (dockSide != DockSide.NONE) return@Runnable
        // 复用 snapToEdge 的弹性吸边动画
        snapToEdge()
        v.alpha = 1.0f
    }

    /** 启动 3s 倒计时。重复调用会取消上次。守门条件不满足时 noop。 */
    private fun scheduleAutoDock() {
        if (tourStage != TourStage.NONE) return
        if (!snapToEdgeEnabled || !autoDockEnabled) return
        if (dockSide != DockSide.NONE) return
        autoDockHandler.removeCallbacks(autoDockRunnable)
        autoDockHandler.postDelayed(autoDockRunnable, AUTO_DOCK_DELAY_MS)
    }

    private fun cancelAutoDock() {
        autoDockHandler.removeCallbacks(autoDockRunnable)
    }

    /** 容器是 [LiquidFloatingContainer]（FrameLayout 子类），dock 时它自己画液态尾巴 path。 */
    private val liquidView: LiquidFloatingContainer? get() = view as? LiquidFloatingContainer
    private val dockSide: LiquidFloatingContainer.DockSide
        get() = liquidView?.side ?: LiquidFloatingContainer.DockSide.NONE

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    fun isShown(): Boolean = view != null

    fun requestFirstUseTour() {
        if (tourStage != TourStage.NONE) {
            cancelFirstUseTour(markCompleted = false)
        } else if (arcMenuView != null) {
            dismissArcMenu()
        }
        firstUseTourPending = true
        val currentView = view ?: return
        currentView.postDelayed(
            {
                if (
                    view === currentView &&
                    firstUseTourPending &&
                    tourStage == TourStage.NONE
                ) {
                    startFirstUseTour()
                }
            },
            TOUR_START_DELAY_MS,
        )
    }

    /** Physical screen bounds occupied by the ball window, masked from loop-frame comparison. */
    fun captureExclusionRect(): Rect? {
        val params = layoutParams ?: return null
        val currentView = view ?: return null
        val width = currentView.width.takeIf { it > 0 } ?: params.width
        val height = currentView.height.takeIf { it > 0 } ?: params.height
        if (width <= 0 || height <= 0) return null
        return Rect(params.x, params.y, params.x + width, params.y + height)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return

        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 128) * density).toInt()  // 球直径
        // 容器横纵 1.3x：球本体距屏幕边仅 0.3R，紧贴边；弧线短小（≈0.3R 横向）
        val containerW = (size * 1.3f).toInt()
        val containerH = (size * 1.4f).toInt()

        val iv = ImageView(context).apply {
            setImageResource(skillIconRes())
            // 球本体（圆形 / 液态）由 LiquidFloatingContainer 在 dispatchDraw 里画，
            // ImageView 只显示图标，不再设 bg_floating_button（避免与 path 错位）
            val pad = (size * 0.18f).toInt()
            setPadding(pad, pad, pad, pad)
        }
        mainIcon = iv
        // 循环模式进度环：叠加在 iv 上层 size×size 居中，stop 状态不绘任何东西
        val progress = LoopProgressView(context)
        val longPressGuide = LongPressGuideView(context)
        val container = LiquidFloatingContainer(context).apply {
            fillColor = androidx.core.content.ContextCompat.getColor(
                this@FloatingButtonManager.context, R.color.floating_button
            )
            strokeColor = 0xFFFFFFFF.toInt()
            strokeWidthPx = 2f * density
            ballRadius = size / 2f
            addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(progress, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(longPressGuide, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        progressView = progress
        longPressGuideView = longPressGuide

        val (screenW, screenH) = currentScreenSize()

        val params = WindowManager.LayoutParams(
            containerW, containerH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (initialX >= 0 && initialY >= 0) {
                x = initialX.coerceIn(0, (screenW - containerW).coerceAtLeast(0))
                y = initialY.coerceIn(0, (screenH - containerH).coerceAtLeast(0))
            } else {
                x = (16 * density).toInt()
                y = (screenH / 4).coerceIn(containerH, screenH - containerH * 2)
            }
            // ALWAYS：让 floating window 延伸到 cutout / status bar 区，window 原点 = 物理 (0, 0)。
            // 不设的话 system 让开 cutout+status bar（横屏 K60 至尊让出 147+147），params.x=0 实际
            // 物理 X=147，球永远贴不到屏幕物理边。同时**arcMenu menuParams 也必须设 ALWAYS**，
            // 否则两个 window 原点不一致 → 按钮 root 本地坐标对应物理位置偏 cutout 宽，弧菜单歪斜。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        attachTouchListener(container, params)
        container.isClickable = true
        container.isFocusable = true
        container.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        container.setOnClickListener { onSingleTap() }
        container.setOnLongClickListener {
            showArcMenu()
            true
        }
        wm.addView(container, params)
        view = container
        layoutParams = params
        updateAccessibilityDescription()

        // 启动时如果开了吸附但上次保存的位置不在边（用户上次拖到中间没吸边），自动吸一下。
        // post 到下一帧确保 view layout 完成再 snap，否则 SpringAnimation 用错的 start value 跳变。
        if (snapToEdgeEnabled) {
            container.post { snapToEdge() }
        }
        if (firstUseTourPending) {
            container.postDelayed(
                {
                    if (view === container && firstUseTourPending) {
                        startFirstUseTour()
                    }
                },
                TOUR_START_DELAY_MS,
            )
        }
    }

    /** 改完 [sizeDp] 后调用以即时生效，无需重启服务。 */
    fun applyResize() {
        val params = layoutParams ?: return
        val container = liquidView ?: return
        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 128) * density).toInt()
        val containerW = (size * 1.3f).toInt()
        val containerH = (size * 1.4f).toInt()
        params.width = containerW
        params.height = containerH
        container.ballRadius = size / 2f
        // 子 view (ImageView + LoopProgressView) 重新调 size×size 居中
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val lp = child.layoutParams as FrameLayout.LayoutParams
            lp.width = size
            lp.height = size
            child.layoutParams = lp
        }
        runCatching { wm.updateViewLayout(container, params) }
    }

    /**
     * 屏幕方向变了：clamp 进新方向的可视区，dock 状态下重新 snap。
     *
     * A 路径（API 30+）：wm 是 display-bound 的，旋转自动反映在 `currentWindowMetrics`，本函数
     * 只负责把 X/Y 拉回合理范围 + re-dock。
     *
     * C 路径（API < 30）：服务持有的默认 wm 不一定感知旋转。理论上这里可以 removeView + addView
     * 强制 wm 重绑定，但默认 wm 的 internal state 也未必随之更新——老 API 是 known limitation，
     * 还是同样的 clamp + re-dock，best effort。
     */
    fun onConfigurationChanged() {
        val params = layoutParams ?: run {
            android.util.Log.w("FBM", "onConfigurationChanged: layoutParams null, skip")
            return
        }
        val v = view ?: run {
            android.util.Log.w("FBM", "onConfigurationChanged: view null, skip")
            return
        }
        val (screenW, screenH) = currentScreenSize()
        params.x = params.x.coerceIn(0, (screenW - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, screenH - params.height)
        runCatching { wm.updateViewLayout(v, params) }
        // post 到下一帧再 snap：避开 onConfigurationChanged 到达时 wm 尺寸尚未切换的窗口期
        v.post {
            if (dockSide != DockSide.NONE) snapToEdge()
            if (tourStage != TourStage.NONE) {
                restartFirstUseTourAfterConfigurationChange()
            }
        }
    }

    /**
     * 拿当前**实际显示方向**的屏幕尺寸。API 30+ 走 `wm.currentWindowMetrics`（wm 是 display-bound
     * 的，跟随旋转）；老 API fallback 走 `view.display.getRealMetrics()`。
     */
    private fun currentScreenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cur = wm.currentWindowMetrics.bounds
            return cur.width() to cur.height()
        }
        @Suppress("DEPRECATION")
        val display = view?.display ?: wm.defaultDisplay
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * 返回系统 bar（status / nav）+ 挖孔的占位（left, top, right, bottom）px。
     * 横屏 3-button nav 会让右/左有非零值，影响"贴边距 inset" 的真实可见区。
     * Android 11+ 走 WindowInsets API；老版本只能返回零（fallback 走 statusBarSafe/navBarSafe 常量）。
     */
    private fun systemInsetsLtrb(): IntArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return intArrayOf(0, 0, 0, 0)
        val winInsets = runCatching { wm.currentWindowMetrics.windowInsets }.getOrNull()
            ?: return intArrayOf(0, 0, 0, 0)
        val types = android.view.WindowInsets.Type.systemBars() or
            android.view.WindowInsets.Type.displayCutout()
        val ins = winInsets.getInsetsIgnoringVisibility(types)
        return intArrayOf(ins.left, ins.top, ins.right, ins.bottom)
    }

    /**
     * 拿屏幕 4 个圆角中最大的半径（px）。圆角屏的圆角区域物理不可见，球贴边时必须避开。
     * 老版本 / 无圆角 API 时返回估算值（24dp），覆盖多数主流圆角屏。
     */
    private fun maxRoundedCornerRadiusPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // API 31 之前没 RoundedCorner API，给个保守保底（24dp 足以覆盖 Pixel/小米/三星等典型圆角）
            return (24 * context.resources.displayMetrics.density).toInt()
        }
        val display = view?.display ?: wm.defaultDisplay ?: return 0
        val positions = intArrayOf(
            android.view.RoundedCorner.POSITION_TOP_LEFT,
            android.view.RoundedCorner.POSITION_TOP_RIGHT,
            android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
            android.view.RoundedCorner.POSITION_BOTTOM_RIGHT
        )
        return positions.maxOf { display.getRoundedCorner(it)?.radius ?: 0 }
    }

    fun hide() {
        cancelFirstUseTour(markCompleted = false, dismissMenu = false)
        // hide 时若菜单还在 + 球已腾位 → 先把球位「回滚 到 positionBeforeMenu」再保存，不然下次
        // show() 用的 initialX/initialY 会是腾位后的临时位置，球永久跳到屏幕中部。
        positionBeforeMenu?.let { (origX, origY) ->
            layoutParams?.x = origX
            layoutParams?.y = origY
        }
        positionBeforeMenu = null
        dismissArcMenu(restorePosition = false)
        // 必须在 dismissArcMenu 之后 cancel——dismiss 末尾会 scheduleAutoDock，否则会留下指向已销毁 view 的 runnable
        cancelAutoDock()
        snapAnimX?.cancel(); snapAnimX = null
        snapAnimY?.cancel(); snapAnimY = null
        progressView?.stop()
        progressView = null
        longPressGuideView?.stop()
        longPressGuideView = null
        mainIcon = null
        // 保留 hide 前的最后位置——下次 show() 用 initialX/initialY 重建，否则会回到 service 启动
        // 时灌入的旧 settings 值（用户在弧菜单选区域后，调整完区域回来球就跳回原点的根因）。
        layoutParams?.let {
            initialX = it.x
            initialY = it.y
        }
        view?.let {
            runCatching { wm.removeView(it) }
            view = null
            layoutParams = null
        }
    }

    private fun setDockSide(side: LiquidFloatingContainer.DockSide) {
        liquidView?.side = side
    }

    /**
     * 设置项变化时主动响应——不再等用户拖一下：
     * - 开 → 立即吸附到最近边（球完整贴墙、半透待机）
     * - 关 → 若当前贴边状态，自动滑离边缘到 16dp margin 处，alpha 恢复全显
     *
     * 只在状态实际变化时启动动画，避免每次 settings flow emit 都重新跑。
     * Service 还没 show 球时静默更新字段。
     */
    fun applySnapPreference(enabled: Boolean) {
        val prev = snapToEdgeEnabled
        snapToEdgeEnabled = enabled
        if (!enabled) cancelAutoDock()  // 总开关关 → autoDock 也作废
        view ?: return  // 还没 show，只记字段
        if (prev == enabled) return
        if (enabled) snapToEdge() else leaveDockedEdge()
    }

    /** 吸附关时自动从屏幕边滑出：仅在球当前贴边 (dockSide != NONE 或 x 在边缘) 时触发。 */
    private fun leaveDockedEdge() {
        val v = view ?: return
        val params = layoutParams ?: return
        val (screenW, _) = currentScreenSize()
        val size = params.width
        val density = context.resources.displayMetrics.density
        val margin = (16 * density).toInt()
        val atEdge = params.x <= 0 || params.x + size >= screenW || dockSide != DockSide.NONE
        setDockSide(DockSide.NONE)
        v.animate().cancel()
        v.alpha = 1.0f
        if (!atEdge) {
            persistPositionDebounced()
            return
        }
        val centerX = params.x + size / 2
        val targetX = if (centerX < screenW / 2) margin
        else (screenW - size - margin).coerceAtLeast(0)

        snapAnimX?.cancel()
        snapAnimX = SpringAnimation(FloatValueHolder(params.x.toFloat())).apply {
            spring = SpringForce(targetX.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
            addUpdateListener { _, value, _ ->
                params.x = value.toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            addEndListener { _, _, _, _ -> persistPositionDebounced() }
            start()
        }
    }

    /**
     * 松手处理：吸附开启时贴到最近左/右边、半个球藏屏外（"石墩子"感）+ alpha 0.6 待机；
     * 吸附关闭时**完全不动 X**，只对 Y 做安全 clamp（防球落入状态栏 / 导航栏死区）+ alpha 恢复 1.0。
     *
     * SpringAnimation 每帧 updateViewLayout，动画结束写回 [SettingsRepository] 持久化。
     * 重复调用会取消上次动画。
     */
    private fun snapToEdge() {
        val v = view ?: return
        val params = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val (screenW, screenH) = currentScreenSize()
        // 系统 bar / 挖孔区域真实占位：横屏 3-button nav 在右侧时 inset 必须从这开始算，
        // 否则球贴右边落到 nav bar 里看起来"贴着屏幕边缘"忽略了 inset
        val sysInsets = systemInsetsLtrb()
        val safeLeft = sysInsets[0]
        val safeTop = sysInsets[1].coerceAtLeast((30 * density).toInt())
        val safeRight = sysInsets[2]
        val safeBottom = sysInsets[3].coerceAtLeast((48 * density).toInt())
        val containerW = params.width
        val containerH = params.height

        // Y 直接用 menuYRange()——既保证不入状态栏 / 导航栏死区，又留出弧菜单展开需要的
        // 垂直空间。openArcMenuPage 的腾位判定也用同一个 range，避免「snap 在边界 + 腾位
        // 又判越界」的死循环。
        val yRange = menuYRange()
        val targetY = params.y.coerceIn(yRange.first, yRange.last.coerceAtLeast(yRange.first))

        snapAnimX?.cancel(); snapAnimY?.cancel()

        if (snapToEdgeEnabled) {
            val centerX = params.x + containerW / 2
            val dockLeft = centerX < screenW / 2
            val inset = dockEdgeInsetPx.coerceAtLeast(0)
            val targetX = if (dockLeft) inset
            else (screenW - containerW - inset).coerceAtLeast(0)
            setDockSide(if (dockLeft) DockSide.LEFT else DockSide.RIGHT)

            snapAnimX = SpringAnimation(FloatValueHolder(params.x.toFloat())).apply {
                spring = SpringForce(targetX.toFloat()).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    stiffness = 400f
                }
                addUpdateListener { _, value, _ ->
                    params.x = value.toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                }
                addEndListener { _, _, _, _ ->
                    persistPositionDebounced()
                    v.animate().alpha(0.75f).setDuration(220L).start()
                }
                start()
            }
        } else {
            // 吸附关：X 不动（用户松手在哪就在哪）、恢复全显
            setDockSide(DockSide.NONE)
            v.animate().cancel()
            v.alpha = 1.0f
            persistPositionDebounced()
        }

        snapAnimY = SpringAnimation(FloatValueHolder(params.y.toFloat())).apply {
            spring = SpringForce(targetY.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                stiffness = 400f
            }
            addUpdateListener { _, value, _ ->
                params.y = value.toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            start()
        }
    }

    /**
     * 用户重新按下时唤醒：若当前处于贴边吸附态（dockSide != NONE），把球滑离边并恢复
     * 完整圆形 + alpha 1.0。用 SpringAnimation 平滑滑离，避免瞬时 jump。返回唤醒后的 X
     * 用于 touch listener 重置 initX，让拖动从正确位置开始。
     */
    private fun wakeFromSnap(): Int {
        val v = view
        val params = layoutParams ?: return 0
        // alpha + 形状立即恢复，不等动画
        v?.animate()?.cancel()
        v?.alpha = 1.0f
        val docked = dockSide != DockSide.NONE
        if (docked) setDockSide(DockSide.NONE)
        if (!docked) return params.x

        val (screenW, _) = currentScreenSize()
        val size = params.width  // = containerW
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val centerX = params.x + size / 2
        val targetX = if (centerX < screenW / 2) margin
        else (screenW - size - margin).coerceAtLeast(0)

        snapAnimX?.cancel()
        snapAnimX = SpringAnimation(FloatValueHolder(params.x.toFloat())).apply {
            spring = SpringForce(targetX.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
            addUpdateListener { _, value, _ ->
                params.x = value.toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            start()
        }
        return targetX
    }

    /** 持久化当前 params.x/y 到 Settings。X/Y 任一动画 end 都会调，写一次足够。 */
    @Volatile private var positionPersistPending = false
    private fun persistPositionDebounced() {
        if (positionPersistPending) return
        positionPersistPending = true
        val params = layoutParams ?: run { positionPersistPending = false; return }
        ioScope.launch {
            try {
                settingsRepository.update { it.copy(floatingButtonX = params.x, floatingButtonY = params.y) }
            } finally {
                positionPersistPending = false
            }
        }
    }

    /** 循环模式开关：固定间隔显示倒计时环，智能等待显示平滑旋转的短弧。 */
    fun setLoopActive(active: Boolean, intervalMs: Long, indeterminate: Boolean = false) {
        isLooping = active
        progressView?.let { progress ->
            when {
                !active -> progress.stop()
                indeterminate -> progress.startIndeterminate(intervalMs)
                else -> progress.start(intervalMs)
            }
        }
        updateAccessibilityDescription()
    }

    /** 立即把球的主图标按当前 [skill] 切换。CaptureService 在切技能时 + settings collect 同步时调。 */
    fun applySkillIcon() {
        mainIcon?.setImageResource(skillIconRes())
        updateAccessibilityDescription()
    }

    private fun skillIconRes(): Int = when (skill) {
        FloatingSkill.FULL_SCREEN -> R.drawable.ic_overlay_button
        FloatingSkill.WORD_SELECT -> R.drawable.ic_overlay_button_word
        FloatingSkill.LOOP -> R.drawable.ic_overlay_button_loop
    }

    private fun updateAccessibilityDescription() {
        val state = when (skill) {
            FloatingSkill.FULL_SCREEN -> context.getString(R.string.a11y_floating_mode_full_screen)
            FloatingSkill.WORD_SELECT -> context.getString(R.string.a11y_floating_mode_word_select)
            FloatingSkill.LOOP -> context.getString(
                if (isLooping) R.string.a11y_floating_mode_loop_running
                else R.string.a11y_floating_mode_loop_stopped
            )
        }
        val hint = when (skill) {
            FloatingSkill.FULL_SCREEN -> context.getString(R.string.a11y_floating_hint_full_screen)
            FloatingSkill.WORD_SELECT -> context.getString(R.string.a11y_floating_hint_word_select)
            FloatingSkill.LOOP -> context.getString(
                if (isLooping) R.string.a11y_floating_hint_loop_stop
                else R.string.a11y_floating_hint_loop_start
            )
        }
        view?.contentDescription = OverlayAccessibilityLabels.actionWithState(
            action = context.getString(R.string.a11y_floating_ball),
            state = state,
            hint = hint,
        )
    }

    private fun startFirstUseTour() {
        if (!firstUseTourPending || tourStage != TourStage.NONE || view == null) return
        firstUseTourPending = false
        cancelAutoDock()
        tourPlan = FloatingMenuTourPolicy.pages(menuItemOrder, arcMenuPageSize)
        tourStage = TourStage.WAITING_FOR_LONG_PRESS
        showMainBallTourStep()
    }

    private fun showMainBallTourStep() {
        startTourPulse(view)
        longPressGuideView?.start()
        longPressCueOverlay.show(currentBallBounds())
        tourOverlay.show(
            anchorCenterY = currentBallCenterY(),
            progress = context.getString(
                R.string.floating_tour_progress,
                1,
                FloatingMenuTourPolicy.totalStepCount(menuItemOrder, arcMenuPageSize),
            ),
            title = context.getString(R.string.floating_tour_main_title),
            body = context.getString(
                R.string.floating_tour_main_body,
                currentSkillDisplayName(),
            ),
            actionLabel = null,
            onAction = {},
            onSkip = { finishFirstUseTour() },
        )
    }

    private fun currentSkillDisplayName(): String = context.getString(
        when (skill) {
            FloatingSkill.FULL_SCREEN -> R.string.floating_skill_full_screen_label
            FloatingSkill.WORD_SELECT -> R.string.floating_skill_word_select_label
            FloatingSkill.LOOP -> R.string.menu_loop_translate
        }
    )

    private fun currentBallCenterY(): Int {
        val params = layoutParams ?: return currentScreenSize().second / 2
        return params.y + params.height / 2
    }

    private fun currentBallBounds(): Rect {
        val density = context.resources.displayMetrics.density
        val ballSize = (sizeDp.coerceIn(28, 128) * density).toInt()
        val ballRadius = ballSize / 2
        val params = layoutParams ?: run {
            val (screenWidth, screenHeight) = currentScreenSize()
            return Rect(
                screenWidth / 2 - ballRadius,
                screenHeight / 2 - ballRadius,
                screenWidth / 2 + ballRadius,
                screenHeight / 2 + ballRadius,
            )
        }
        val centerX = params.x + when (dockSide) {
            DockSide.LEFT -> ballRadius
            DockSide.RIGHT -> params.width - ballRadius
            DockSide.NONE -> params.width / 2
        }
        val centerY = params.y + params.height / 2
        return Rect(
            centerX - ballRadius,
            centerY - ballRadius,
            centerX + ballRadius,
            centerY + ballRadius,
        )
    }

    private fun restartFirstUseTourAfterConfigurationChange() {
        if (tourStage == TourStage.NONE) return
        val wasShowingCompletion = tourStage == TourStage.COMPLETION
        stopTourPulse()
        longPressGuideView?.stop()
        longPressCueOverlay.dismiss()
        tourOverlay.dismiss()
        if (wasShowingCompletion) {
            if (arcMenuView != null) dismissArcMenu()
            view?.postDelayed(
                { showFirstUseTourCelebration() },
                TOUR_RESTART_DELAY_MS,
            )
            return
        }
        if (arcMenuView != null) {
            tourStage = TourStage.WAITING_FOR_LONG_PRESS
            dismissArcMenu()
        } else {
            tourStage = TourStage.WAITING_FOR_LONG_PRESS
        }
        view?.postDelayed({ showMainBallTourStep() }, TOUR_RESTART_DELAY_MS)
    }

    private fun startTourPulse(target: View?) {
        stopTourPulse()
        if (target == null) return
        tourPulseTarget = target
        tourPulseAnimator = ValueAnimator.ofFloat(1f, TOUR_PULSE_SCALE).apply {
            duration = TOUR_PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val scale = it.animatedValue as Float
                target.scaleX = scale
                target.scaleY = scale
            }
            start()
        }
    }

    private fun stopTourPulse() {
        tourPulseAnimator?.cancel()
        tourPulseAnimator = null
        tourPulseTarget?.let {
            it.scaleX = 1f
            it.scaleY = 1f
        }
        tourPulseTarget = null
    }

    private fun showCurrentMenuTourStep() {
        val page = tourPlan.getOrNull(tourPageIndex) ?: run {
            finishFirstUseTour()
            return
        }
        val target = page.targets.getOrNull(tourTargetIndex) ?: run {
            finishFirstUseTour()
            return
        }
        val item = tourMenuItems.getOrNull(tourTargetIndex) ?: run {
            finishFirstUseTour()
            return
        }
        val button = tourMenuButtons.getOrNull(tourTargetIndex) ?: run {
            finishFirstUseTour()
            return
        }

        tourMenuButtons.forEachIndexed { index, menuButton ->
            menuButton.animate().cancel()
            menuButton.alpha = if (index == tourTargetIndex) 1f else TOUR_DIMMED_ALPHA
            menuButton.scaleX = 1f
            menuButton.scaleY = 1f
            menuButton.translationX = 0f
            menuButton.translationY = 0f
            menuButton.rotation = 0f
        }
        startTourPulse(button)

        val absoluteIndex = FloatingMenuTourPolicy.absoluteStepIndex(
            pages = tourPlan,
            pageIndex = tourPageIndex,
            targetIndex = tourTargetIndex,
        )
        val totalSteps = 1 + tourPlan.sumOf { it.targets.size }
        val actionLabel = when {
            target is FloatingMenuTourTarget.NextPage ->
                context.getString(R.string.floating_tour_try_next_page)
            tourPageIndex == tourPlan.lastIndex &&
                tourTargetIndex == page.targets.lastIndex ->
                context.getString(R.string.floating_tour_done)
            else -> context.getString(R.string.floating_tour_next)
        }
        val descriptionRes = when (item.labelRes) {
            R.string.menu_loop_translate -> R.string.floating_tour_loop_body
            R.string.menu_pick_region -> R.string.floating_tour_region_body
            R.string.menu_language_pair -> R.string.floating_tour_language_body
            R.string.menu_preset_switch -> R.string.floating_tour_preset_body
            R.string.menu_open_settings -> R.string.floating_tour_settings_body
            R.string.menu_open_main -> R.string.floating_tour_home_body
            R.string.menu_word_select -> R.string.floating_tour_word_select_body
            R.string.menu_full_screen_skill -> R.string.floating_tour_full_screen_body
            R.string.menu_next_page -> if (
                target is FloatingMenuTourTarget.NextPage && target.wrapsToFirstPage
            ) {
                R.string.floating_tour_next_page_wrap_body
            } else {
                R.string.floating_tour_next_page_body
            }
            else -> R.string.floating_tour_generic_item_body
        }
        tourOverlay.show(
            anchorCenterY = currentBallCenterY(),
            progress = context.getString(
                R.string.floating_tour_progress,
                absoluteIndex + 1,
                totalSteps,
            ),
            title = context.getString(item.labelRes),
            body = context.getString(descriptionRes),
            actionLabel = actionLabel,
            onAction = { advanceFirstUseTour() },
            onSkip = { finishFirstUseTour() },
        )
    }

    private fun advanceFirstUseTour() {
        if (tourStage != TourStage.MENU_ITEMS) return
        when (
            val decision = FloatingMenuTourPolicy.advance(
                pages = tourPlan,
                pageIndex = tourPageIndex,
                targetIndex = tourTargetIndex,
            )
        ) {
            FloatingMenuTourAdvance.Complete -> showFirstUseTourCelebration()
            is FloatingMenuTourAdvance.ShowTarget -> {
                tourPageIndex = decision.pageIndex
                tourTargetIndex = decision.targetIndex
                showCurrentMenuTourStep()
            }
            is FloatingMenuTourAdvance.OpenPage -> {
                stopTourPulse()
                tourOverlay.dismiss()
                tourMenuItems.getOrNull(tourTargetIndex)?.onTap?.invoke()
            }
        }
    }

    private fun showFirstUseTourCelebration() {
        if (tourStage == TourStage.NONE) return
        tourStage = TourStage.COMPLETION
        stopTourPulse()
        tourOverlay.dismiss()
        if (arcMenuView != null) dismissArcMenu()
        tourOverlay.show(
            anchorCenterY = currentBallCenterY(),
            progress = context.getString(R.string.floating_tour_complete_progress),
            title = context.getString(R.string.floating_tour_complete_title),
            body = context.getString(R.string.floating_tour_complete_body),
            actionLabel = context.getString(R.string.floating_tour_start_using),
            onAction = { finishFirstUseTour() },
            onSkip = null,
            celebration = true,
        )
    }

    private fun finishFirstUseTour() {
        cancelFirstUseTour(markCompleted = true)
    }

    private fun cancelFirstUseTour(
        markCompleted: Boolean,
        dismissMenu: Boolean = true,
    ) {
        val wasActive = tourStage != TourStage.NONE
        tourStage = TourStage.NONE
        tourPlan = emptyList()
        tourPageIndex = 0
        tourTargetIndex = 0
        stopTourPulse()
        longPressGuideView?.stop()
        longPressCueOverlay.dismiss()
        tourOverlay.dismiss()
        restoreMenuTourVisuals()
        if (dismissMenu && arcMenuView != null) dismissArcMenu()
        if (markCompleted && wasActive) onFirstUseTourCompleted()
    }

    private fun restoreMenuTourVisuals() {
        tourMenuButtons.forEach {
            it.animate().cancel()
            it.alpha = MENU_ITEM_ALPHA
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
            it.translationY = 0f
            it.rotation = 0f
        }
        tourMenuItems = emptyList()
        tourMenuButtons = emptyList()
    }

    private fun arcSpreadFor(itemCount: Int): Double = ArcMenuGeometry.spreadFor(itemCount)

    /**
     * 「球中心 cy 到屏幕可见区上 / 下边的最小安全距离」（px，**不含**系统栏 inset）。
     *
     * 按按钮**外缘**距 cy 的距离算（`radius * sin(spread) + itemRadius`），保证最远那颗按钮
     * 完全在可见区内。系统栏 / 导航栏 inset 由调用方再叠加（[menuYRange]）。
     *
     * 永远按可配置最大页大小 [FloatingMenu.MAX_PAGE_SIZE] 对应的扇形（当前 ±90°）算最坏情况，
     * 避免 snapToEdge 和 openArcMenuPage 因实际页按钮数不同导致 verticalSafe 不一致触发死循环 spring。
     */
    fun menuVerticalSafePx(): Int {
        val density = context.resources.displayMetrics.density
        val ballRadiusPx = (sizeDp.coerceIn(28, 128) / 2f * density).toInt()
        val itemSize = (sizeDp * 0.85f * density).toInt().coerceAtLeast((40 * density).toInt())
        val itemRadiusPx = itemSize / 2
        val gapPx = (28 * density).toInt()
        val radius = ballRadiusPx + itemRadiusPx + gapPx
        val maxSpread = arcSpreadFor(FloatingMenu.MAX_PAGE_SIZE)
        return (radius * Math.sin(maxSpread) + itemRadiusPx).toInt()
    }

    /**
     * 球可以放且**不会让菜单按钮被系统栏遮住**的 `params.y` 范围。
     * 等价于：球中心 cy ∈ [safeTop + menuSafe, screenH - safeBottom - menuSafe]，转回 params.y 坐标系。
     *
     * 小屏 / 大球时 lower > upper（不存在满足条件的位置）→ 退化成 [lower, lower]，至少保证球能放下、
     * 菜单最多被切一点（比挤成一团好）。
     */
    private fun menuYRange(): IntRange {
        val density = context.resources.displayMetrics.density
        val (_, screenH) = currentScreenSize()
        val sys = systemInsetsLtrb()
        val safeTop = sys[1].coerceAtLeast((30 * density).toInt())
        val safeBottom = sys[3].coerceAtLeast((48 * density).toInt())
        val containerH = ((sizeDp.coerceIn(28, 128) * 1.4f) * density).toInt()
        val menuSafe = menuVerticalSafePx()
        val lower = safeTop + menuSafe - containerH / 2
        val upper = screenH - safeBottom - menuSafe - containerH / 2
        return if (upper >= lower) lower..upper else lower..lower
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(target: View, params: WindowManager.LayoutParams) {
        // 用系统标准 touchSlop 的 2 倍，避免轻微抖动被误判为"拖动"导致单击丢失
        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2f
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()

        var downX = 0f
        var downY = 0f
        var initX = 0
        var initY = 0
        var downTime = 0L
        var moved = false
        var longPressFired = false

        val longPressRunnable = Runnable {
            if (!moved) {
                longPressFired = true
                showArcMenu()
            }
        }

        target.setOnTouchListener { v, ev ->
            if (tourStage == TourStage.COMPLETION) {
                return@setOnTouchListener true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 取消自动贴边倒计时 + 上次的吸边动画 + 待机透明度。**不**立即 wake——
                    // 否则长按弹菜单时球会从贴边位置缩进 8dp，视觉错位。wake 推迟到拖动开始时。
                    cancelAutoDock()
                    snapAnimY?.cancel()
                    snapAnimX?.cancel()
                    v.animate().cancel()
                    v.alpha = 1.0f
                    downX = ev.rawX
                    downY = ev.rawY
                    initX = params.x  // 当前位置（可能还在 dock 状态）
                    initY = params.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    longPressFired = false
                    if (tourStage == TourStage.WAITING_FOR_LONG_PRESS) {
                        longPressGuideView?.beginPress(longPressTimeout)
                    }
                    v.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
                        // 第一次确认拖动：**同步**解 dock，不启动 spring（spring 异步会跟手指冲突，把球瞬间拉回边）。
                        // 球的视觉位置可能瞬间从 container 边变到 container 中心（dock NONE 时 visualCx=width/2），
                        // 但 X 偏移仅 0.3r ≈ 8dp，几乎无感。
                        snapAnimX?.cancel()
                        if (dockSide != DockSide.NONE) setDockSide(DockSide.NONE)
                        v.alpha = 1.0f
                        if (tourStage == TourStage.WAITING_FOR_LONG_PRESS) {
                            longPressGuideView?.start()
                            longPressCueOverlay.dismiss()
                        }
                        initX = params.x  // 用球当前真实位置当拖动起点
                        downX = ev.rawX
                        downY = ev.rawY
                        initY = params.y
                    }
                    if (moved) {
                        params.x = (initX + dx).toInt()
                        params.y = (initY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPressRunnable)
                    // 只要没明显拖动 + 长按 callback 还没烧 → 都算单击
                    if (!moved && !longPressFired) {
                        if (tourStage == TourStage.WAITING_FOR_LONG_PRESS) {
                            longPressGuideView?.start()
                        } else {
                            onSingleTap()
                        }
                    } else if (moved) {
                        // 拖动后松手 → 弹性吸附到最近的左/右边
                        snapToEdge()
                        if (tourStage == TourStage.WAITING_FOR_LONG_PRESS) {
                            v.postDelayed(
                                {
                                    if (tourStage == TourStage.WAITING_FOR_LONG_PRESS) {
                                        showMainBallTourStep()
                                    }
                                },
                                TOUR_RESTART_DELAY_MS,
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 长按弹弧形菜单入口。流程：
     *  1) 按 [menuItemOrder] 构造 spec → paginate 切页（每页 [arcMenuPageSize] 项，数量包含
     *     「下一组」按钮；最后一页的「下一组」回到第一页）；
     *  2) 估算首页扇形空间，若球当前位置塞不下扇形，先 spring 到「最近的安全位」，再展开。
     *     这样按钮**不需要缩小、不需要折叠**，避免越界；菜单关闭时 spring 回原位（仿 MIUI 悬浮球）；
     *  3) 翻页时不回滚球位置（保留腾位状态，下一页用同一位置展开）。
     */
    private fun showArcMenu() = openArcMenuPage(0)

    /** 真正展开某一页菜单（含必要时的腾位 spring）。翻页按钮 / 入口都走这里。 */
    private fun openArcMenuPage(pageIndex: Int) {
        android.util.Log.d(TAG_MENU, "openArcMenuPage(page=$pageIndex) arcMenuView=${arcMenuView != null} positionBeforeMenu=$positionBeforeMenu")
        if (arcMenuView != null) {
            android.util.Log.d(TAG_MENU, "  skip: arcMenuView already exists")
            return
        }
        val params = layoutParams ?: run {
            android.util.Log.w(TAG_MENU, "  abort: layoutParams null")
            return
        }

        val allItems = MenuItemRegistry.build(
            ids = menuItemOrder,
            currentSkill = skill,
            callbacks = MenuItemRegistry.Callbacks(
                onSwitchToLoop = { dismissArcMenu(); onSwitchToLoop() },
                onRegion = { dismissArcMenu(); onMenuPickRegion() },
                onLanguagePair = { dismissArcMenu(); onMenuLanguagePair() },
                onOpenMain = { dismissArcMenu(); onMenuOpenMainActivity() },
                onOpenSettings = { dismissArcMenu(); onMenuOpenSettings() },
                onPresetSwitch = { dismissArcMenu(); onMenuPresetSwitch() },
                onSwitchToFullScreen = { dismissArcMenu(); onSwitchSkill(FloatingSkill.FULL_SCREEN) },
                onSwitchToWordSelect = { dismissArcMenu(); onSwitchSkill(FloatingSkill.WORD_SELECT) }
            )
        )
        val pages = MenuItemRegistry.paginate(allItems, pageSize = arcMenuPageSize) { nextIdx ->
            // 翻页：关菜单但**不**回滚球位（保留腾位状态，下一页用同一位置开），再开下一页
            if (tourStage == TourStage.MENU_ITEMS) {
                stopTourPulse()
                tourOverlay.dismiss()
            }
            dismissArcMenu(restorePosition = false)
            view?.post { openArcMenuPage(nextIdx) }
        }
        val pageItems = pages.getOrNull(pageIndex) ?: pages.firstOrNull() ?: return
        if (pageItems.isEmpty()) return

        val density = context.resources.displayMetrics.density
        val (screenW, screenH) = currentScreenSize()
        val ballRadiusPx = (sizeDp / 2f * density).toInt()
        val itemSize = (sizeDp * 0.85f * density).toInt().coerceAtLeast((40 * density).toInt())
        val itemRadiusPx = itemSize / 2
        val gapPx = (28 * density).toInt()
        val radius = ballRadiusPx + itemRadiusPx + gapPx
        val itemCount = pageItems.size
        // 按当前页实际按钮数动态展开：2/3/4/5/6 项分别为 ±30°/±45°/±54°/±72°/±90°。
        val spread = arcSpreadFor(itemCount)
        // 用 snapToEdge 同源 verticalSafe（按 MAX_PAGE_SIZE 最坏情况），避免「snapToEdge 把球贴边
        // 后 openArcMenuPage 又判定 needsMove」死循环。
        val verticalSafe = menuVerticalSafePx()

        // 算球当前中心位置
        val cx = params.x + when (dockSide) {
            DockSide.LEFT -> ballRadiusPx
            DockSide.RIGHT -> params.width - ballRadiusPx
            DockSide.NONE -> params.width / 2
        }
        val cy = params.y + params.height / 2

        // 腾位判定（按需）：用 menuYRange() —— 含 status bar / nav bar inset。球当前 y 在 range
        // 外才 spring；range 内直接展开，不打扰用户。翻页时 positionBeforeMenu != null 跳过 spring。
        val yRange = menuYRange()
        val needsMove = params.y < yRange.first || params.y > yRange.last
        android.util.Log.d(TAG_MENU, "  itemCount=$itemCount params=(${params.x},${params.y},${params.width}x${params.height}) screen=${screenW}x${screenH} dock=$dockSide cy=$cy yRange=$yRange needsMove=$needsMove positionBeforeMenu=$positionBeforeMenu")
        if (needsMove && positionBeforeMenu == null) {
            positionBeforeMenu = params.x to params.y
            val targetY = params.y.coerceIn(yRange.first, yRange.last.coerceAtLeast(yRange.first))
            android.util.Log.d(TAG_MENU, "  → spring ball y ${params.y} -> $targetY, x unchanged ${params.x}")
            springBallToThen(params.x, targetY) {
                android.util.Log.d(TAG_MENU, "  ← spring settled, reopen page=$pageIndex")
                openArcMenuPage(pageIndex)
            }
            return
        }
        val space = radius + itemSize  // legacy 检查仅作 baseAngle 估算输入

        // 选基础朝向：4 角时取对角 45° 反弹（最大可用空间），4 边时取垂直反弹
        val nearTop = cy - space < 0
        val nearBottom = cy + space > screenH
        val nearLeft = cx - space < 0
        val nearRight = cx + space > screenW
        val baseAngle: Double = when {
            nearTop && nearLeft -> Math.PI / 4
            nearTop && nearRight -> 3 * Math.PI / 4
            nearBottom && nearLeft -> -Math.PI / 4
            nearBottom && nearRight -> -3 * Math.PI / 4
            nearTop -> Math.PI / 2
            nearBottom -> -Math.PI / 2
            cx < screenW / 2 -> 0.0
            else -> Math.PI
        }
        // 角度均匀分布在 [baseAngle - spread, baseAngle + spread]
        val rawAngles = if (itemCount == 1) {
            doubleArrayOf(baseAngle)
        } else {
            DoubleArray(itemCount) { i ->
                baseAngle - spread + 2 * spread * i / (itemCount - 1)
            }
        }
        val isVertical = Math.abs(Math.sin(baseAngle)) > 0.9
        val angles = rawAngles.sortedBy {
            if (isVertical) Math.cos(it) else Math.sin(it)
        }.toDoubleArray()

        // 全屏背景层：透明可点，点空白关菜单
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener {
                if (tourStage == TourStage.NONE) dismissArcMenu()
            }
        }

        val iconPad = (itemSize * 0.22f).toInt()
        val menuButtons = mutableListOf<ImageView>()
        pageItems.forEachIndexed { idx, item ->
            val angle = angles[idx]
            val centerOffsetX = (radius * Math.cos(angle)).toFloat()
            val centerOffsetY = (radius * Math.sin(angle)).toFloat()
            val rawLeft = (cx + centerOffsetX - itemSize / 2f).toInt()
            val rawTop = (cy + centerOffsetY - itemSize / 2f).toInt()
            val left = rawLeft.coerceIn(0, (screenW - itemSize).coerceAtLeast(0))
            val top = rawTop.coerceIn(0, (screenH - itemSize).coerceAtLeast(0))

            // 起始 translationX/Y：把按钮视觉起点放在悬浮球中心，动画到目标位置 → "从球里旋出来"的轨迹感
            val btnCenterX = left + itemSize / 2f
            val btnCenterY = top + itemSize / 2f
            val startTransX = cx - btnCenterX
            val startTransY = cy - btnCenterY

            val btn = ImageView(context).apply {
                setImageResource(item.iconRes)
                setBackgroundResource(item.bgRes)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                contentDescription = context.getString(item.labelRes)
                isClickable = true
                setOnClickListener {
                    if (tourStage == TourStage.MENU_ITEMS) {
                        if (idx == tourTargetIndex) advanceFirstUseTour()
                    } else {
                        item.onTap()
                    }
                }
                alpha = 0f
                scaleX = 0.4f
                scaleY = 0.4f
                rotation = -360f
                translationX = startTransX
                translationY = startTransY
            }
            val lp = FrameLayout.LayoutParams(itemSize, itemSize).apply {
                leftMargin = left
                topMargin = top
            }
            root.addView(btn, lp)
            menuButtons += btn

            // 旋出入场：从球中心边旋转边飞出，OvershootInterpolator 让落位时轻微"过冲"再回弹
            btn.animate()
                .alpha(MENU_ITEM_ALPHA)
                .scaleX(1f).scaleY(1f)
                .rotation(0f)
                .translationX(0f).translationY(0f)
                .setStartDelay(50L * idx)
                .setDuration(380L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // FLAG_NOT_FOCUSABLE 即可——不要加 FLAG_NOT_TOUCHABLE，Android 12+ 会把
            // SYSTEM_ALERT_WINDOW + NOT_TOUCHABLE 视为 untrusted touch 直接拦掉点击。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // **必须**与 floating button window 同 cutout mode（这里两者都设 ALWAYS），
            // 否则两个 window 原点差 (cutout, status bar) → 按钮 root 本地坐标对应的物理位置
            // 与球物理位置偏 (147, 147)，弧菜单整体歪斜不绕球。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val addResult = runCatching { wm.addView(root, menuParams) }
        arcMenuView = root
        android.util.Log.d(TAG_MENU, "  addView root success=${addResult.isSuccess} buttons=${pageItems.size} baseAngle=${"%.2f".format(Math.toDegrees(baseAngle))}° spread=±${"%.2f".format(Math.toDegrees(spread))}°")
        addResult.exceptionOrNull()?.let { android.util.Log.e(TAG_MENU, "  addView failed", it) }
        if (addResult.isSuccess && tourStage != TourStage.NONE) {
            if (tourStage == TourStage.WAITING_FOR_LONG_PRESS) {
                tourStage = TourStage.MENU_ITEMS
            }
            tourPageIndex = pageIndex
            tourTargetIndex = 0
            tourMenuItems = pageItems
            tourMenuButtons = menuButtons
            stopTourPulse()
            longPressGuideView?.stop()
            longPressCueOverlay.dismiss()
            tourOverlay.dismiss()
            showCurrentMenuTourStep()
        }
    }

    private fun dismissArcMenu(restorePosition: Boolean = true) {
        android.util.Log.d(TAG_MENU, "dismissArcMenu(restore=$restorePosition) hadView=${arcMenuView != null} positionBeforeMenu=$positionBeforeMenu")
        stopTourPulse()
        restoreMenuTourVisuals()
        arcMenuView?.let { runCatching { wm.removeView(it) } }
        arcMenuView = null
        if (restorePosition) {
            positionBeforeMenu?.let { (origX, origY) ->
                positionBeforeMenu = null
                android.util.Log.d(TAG_MENU, "  rollback ball to ($origX,$origY)")
                springBallTo(origX, origY)
            }
        }
        scheduleAutoDock()
    }

    /** SpringAnimation 平滑 spring 球到 (targetX, targetY)。供腾位 / 回滚 / 翻页复用。 */
    private fun springBallTo(targetX: Int, targetY: Int) = springBallToThen(targetX, targetY, null)

    /**
     * spring 球到 (targetX, targetY)，**Y 动画 settle 后**调 [onSettle]。
     * Y 不需要动（targetY == params.y）时立即 post 一帧后调 onSettle。
     */
    private fun springBallToThen(targetX: Int, targetY: Int, onSettle: (() -> Unit)?) {
        val v = view ?: return
        val params = layoutParams ?: return
        android.util.Log.d(TAG_MENU, "springBallToThen from (${params.x},${params.y}) -> ($targetX,$targetY) hasOnSettle=${onSettle != null}")
        snapAnimX?.cancel(); snapAnimY?.cancel()
        if (targetX != params.x) {
            snapAnimX = SpringAnimation(FloatValueHolder(params.x.toFloat())).apply {
                spring = SpringForce(targetX.toFloat()).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    stiffness = SpringForce.STIFFNESS_MEDIUM
                }
                addUpdateListener { _, value, _ ->
                    params.x = value.toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                }
                start()
            }
        }
        if (targetY == params.y) {
            // Y 已经在位，X 也不需要动 / 也已经在路上：把 onSettle 推到下一帧避免栈递归
            if (onSettle != null) v.post(onSettle)
            return
        }
        snapAnimY = SpringAnimation(FloatValueHolder(params.y.toFloat())).apply {
            spring = SpringForce(targetY.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
            addUpdateListener { _, value, _ ->
                params.y = value.toInt()
                runCatching { wm.updateViewLayout(v, params) }
            }
            if (onSettle != null) {
                addEndListener { _, canceled, value, _ ->
                    android.util.Log.d(TAG_MENU, "  spring Y end value=$value canceled=$canceled")
                    if (!canceled) onSettle()
                }
            }
            start()
        }
    }

    private enum class TourStage {
        NONE,
        WAITING_FOR_LONG_PRESS,
        MENU_ITEMS,
        COMPLETION,
    }

    companion object {
        /** 自动贴边倒计时（ms）。固定 3s，未做成可配（产品决策）。 */
        private const val AUTO_DOCK_DELAY_MS: Long = 3000L
        private const val TOUR_START_DELAY_MS: Long = 700L
        private const val TOUR_RESTART_DELAY_MS: Long = 350L
        private const val TOUR_PULSE_DURATION_MS: Long = 650L
        private const val TOUR_PULSE_SCALE: Float = 1.14f
        private const val TOUR_DIMMED_ALPHA: Float = 0.32f
        /** 弧形菜单按钮稳定后的 alpha。略低于 1，给点透明感能透出后面的内容但又不影响图标识别。 */
        private const val MENU_ITEM_ALPHA: Float = 0.85f
        /** 弧菜单调试 logcat tag。`adb logcat -s FBM-Menu:D` 一键过滤。 */
        private const val TAG_MENU: String = "FBM-Menu"
    }
}
