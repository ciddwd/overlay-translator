package com.gameocr.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
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
import com.gameocr.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

private typealias DockSide = LiquidFloatingContainer.DockSide

/**
 * 悬浮触发按钮。
 * - 单击 → [onSingleTap] 触发一次截屏 → OCR → 翻译。
 * - 长按 → 弹出半圆弧菜单（3 项：循环翻译 / 截图区域 / 返回主应用）。菜单 3 个回调
 *   分别用 [onLongPress]（=「循环翻译」，保留构造名以便外部代码不动）、
 *   [onMenuPickRegion]、[onMenuOpenMainActivity]。
 *
 * 拖动跟系统拨号一样：手指按住后跟随移动，松开后用 SpringAnimation 弹性吸附到最近的左/右边，
 * 位置写回 [SettingsRepository] 持久化，下次启动 Service 还原。
 */
class FloatingButtonManager(
    private val context: Context,
    private val onSingleTap: () -> Unit,
    /** 菜单第一项「循环翻译」回调。命名沿用「onLongPress」让 0.3.x 旧逻辑不破。 */
    private val onLongPress: () -> Unit,
    private val settingsRepository: SettingsRepository,
    private val ioScope: CoroutineScope
) {
    @Volatile var sizeDp: Int = 56
    /** 初始位置：构造后由 CaptureService 注入；若 ≥ 0 则 show() 时优先使用。 */
    @Volatile var initialX: Int = -1
    @Volatile var initialY: Int = -1
    /** 菜单第二项「截图区域调整」回调，由 CaptureService 赋值。 */
    @Volatile var onMenuPickRegion: () -> Unit = {}
    /** 菜单第三项「返回主应用」回调，由 CaptureService 赋值。 */
    @Volatile var onMenuOpenMainActivity: () -> Unit = {}
    /** 吸附边缘开关（用户在 Settings 里可关）。关时松手保持原位 + 不藏半边。 */
    @Volatile var snapToEdgeEnabled: Boolean = true
    /** 当前是否处于循环模式（影响菜单第一项的视觉指示）。由 CaptureService 通过 setLoopActive 同步。 */
    @Volatile private var isLooping: Boolean = false

    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var progressView: LoopProgressView? = null

    private var snapAnimX: SpringAnimation? = null
    private var snapAnimY: SpringAnimation? = null
    private var arcMenuView: View? = null

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

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return

        val density = context.resources.displayMetrics.density
        val size = (sizeDp.coerceIn(28, 128) * density).toInt()  // 球直径
        // 容器横纵 1.3x：球本体距屏幕边仅 0.3R，紧贴边；弧线短小（≈0.3R 横向）
        val containerW = (size * 1.3f).toInt()
        val containerH = (size * 1.4f).toInt()

        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_button)
            // 球本体（圆形 / 液态）由 LiquidFloatingContainer 在 dispatchDraw 里画，
            // ImageView 只显示图标，不再设 bg_floating_button（避免与 path 错位）
            val pad = (size * 0.18f).toInt()
            setPadding(pad, pad, pad, pad)
        }
        // 循环模式进度环：叠加在 iv 上层 size×size 居中，stop 状态不绘任何东西
        val progress = LoopProgressView(context)
        val container = LiquidFloatingContainer(context).apply {
            fillColor = androidx.core.content.ContextCompat.getColor(
                this@FloatingButtonManager.context, R.color.floating_button
            )
            strokeColor = 0xFFFFFFFF.toInt()
            strokeWidthPx = 2f * density
            ballRadius = size / 2f
            addView(iv, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(progress, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        progressView = progress

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
        }

        attachTouchListener(container, params)
        wm.addView(container, params)
        view = container
        layoutParams = params
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

    /** 屏幕方向变了：把球重新 clamp 进可见区域。 */
    fun onConfigurationChanged() {
        val params = layoutParams ?: return
        val v = view ?: return
        val (screenW, screenH) = currentScreenSize()
        params.x = params.x.coerceIn(0, screenW - params.width)
        params.y = params.y.coerceIn(0, screenH - params.height)
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    fun hide() {
        dismissArcMenu()
        snapAnimX?.cancel(); snapAnimX = null
        snapAnimY?.cancel(); snapAnimY = null
        progressView?.stop()
        progressView = null
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
        // 只在球当前贴边时离开，否则不动 X
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
        val statusBarSafe = (30 * density).toInt()
        val navBarSafe = (48 * density).toInt()
        val size = params.width

        // Y 方向无论开关都安全 clamp，避免球被状态栏 / 导航栏盖住点不到
        val targetY = params.y.coerceIn(
            statusBarSafe,
            (screenH - size - navBarSafe).coerceAtLeast(statusBarSafe)
        )

        snapAnimX?.cancel(); snapAnimY?.cancel()

        if (snapToEdgeEnabled) {
            // 球完整贴边：X = 0 或 screenW - size，圆球完全在屏内、贴墙立着 + 半透待机
            val centerX = params.x + size / 2
            val dockLeft = centerX < screenW / 2
            val targetX = if (dockLeft) 0 else (screenW - size).coerceAtLeast(0)
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

    /** 循环模式开关：active=true 时 [LoopProgressView] 按 [intervalMs] 周期匀速转一圈；false 时停。 */
    fun setLoopActive(active: Boolean, intervalMs: Long) {
        isLooping = active
        val pv = progressView ?: return
        if (active) pv.start(intervalMs) else pv.stop()
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
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 用户重新按下：取消正在跑的吸边动画 + 若处于藏起来的待机态，把球拉回全显
                    snapAnimY?.cancel()
                    val wokeX = wakeFromSnap()
                    downX = ev.rawX
                    downY = ev.rawY
                    initX = wokeX
                    initY = params.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    longPressFired = false
                    v.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        v.removeCallbacks(longPressRunnable)
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
                        onSingleTap()
                    } else if (moved) {
                        // 拖动后松手 → 弹性吸附到最近的左/右边
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 弹出 3 项弧形菜单。基础朝向按悬浮球当前位置自动选 4 方向之一（向右 / 向左 / 向下 / 向上），
     * 3 个子按钮在以悬浮球为中心、半径 R 的圆弧上分布在 baseAngle ± 45°。
     *
     * 全屏一层透明背景吸收点击关闭；点子按钮不冒泡到背景。Z 序上比悬浮球 view 后加，
     * 期间无法再触发圆球单击 / 长按，符合菜单展开的语义。
     */
    private fun showArcMenu() {
        if (arcMenuView != null) return
        val params = layoutParams ?: return

        val density = context.resources.displayMetrics.density
        val (screenW, screenH) = currentScreenSize()
        val ballSize = params.width
        val cx = params.x + ballSize / 2
        val cy = params.y + ballSize / 2

        val itemSize = (sizeDp * 0.85f * density).toInt().coerceAtLeast((40 * density).toInt())
        val radius = (sizeDp * 1.65f * density).toInt().coerceAtLeast((72 * density).toInt())
        val space = radius + itemSize  // 三个按钮分布所需的最小可用空间

        // 选基础朝向：4 角时取对角 45° 反弹（最大可用空间），4 边时取垂直反弹
        val nearTop = cy - space < 0
        val nearBottom = cy + space > screenH
        val nearLeft = cx - space < 0
        val nearRight = cx + space > screenW
        val baseAngle: Double = when {
            nearTop && nearLeft -> Math.PI / 4                 // 左上角 → 弹右下
            nearTop && nearRight -> 3 * Math.PI / 4            // 右上角 → 弹左下
            nearBottom && nearLeft -> -Math.PI / 4             // 左下角 → 弹右上
            nearBottom && nearRight -> -3 * Math.PI / 4        // 右下角 → 弹左上
            nearTop -> Math.PI / 2                              // 顶边：向下
            nearBottom -> -Math.PI / 2                          // 底边：向上
            cx < screenW / 2 -> 0.0                             // 左半：向右
            else -> Math.PI                                     // 右半：向左
        }
        val spread = Math.PI / 4  // ±45°
        // raw 顺序由 baseAngle 决定 sin 方向，左弹/底弹时会反序。统一按"屏幕上→下"或
        // "屏幕左→右"排序，让菜单 3 项的视觉顺序与项目顺序（循环/区域/主应用）一致。
        val rawAngles = doubleArrayOf(baseAngle - spread, baseAngle, baseAngle + spread)
        val isVertical = Math.abs(Math.sin(baseAngle)) > 0.9   // 朝上/下时按 cos 排（左→右），否则按 sin 排（上→下）
        val angles = rawAngles.sortedBy {
            if (isVertical) Math.cos(it) else Math.sin(it)
        }.toDoubleArray()

        // 全屏背景层：透明可点，点空白关菜单
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { dismissArcMenu() }
        }

        // 菜单第一项「循环翻译」按当前循环状态切换：ON 时蓝底 + 「关闭循环」文案；OFF 时默认深灰
        val loopLabelRes = if (isLooping) R.string.menu_loop_translate_active else R.string.menu_loop_translate
        val loopBgRes = if (isLooping) R.drawable.bg_arc_menu_item_active else R.drawable.bg_arc_menu_item

        data class MenuItem(val iconRes: Int, val bgRes: Int, val labelRes: Int, val onTap: () -> Unit)
        val items = listOf(
            MenuItem(R.drawable.ic_menu_loop, loopBgRes, loopLabelRes) {
                dismissArcMenu(); onLongPress()
            },
            MenuItem(R.drawable.ic_menu_region, R.drawable.bg_arc_menu_item, R.string.menu_pick_region) {
                dismissArcMenu(); onMenuPickRegion()
            },
            MenuItem(R.drawable.ic_menu_home, R.drawable.bg_arc_menu_item, R.string.menu_open_main) {
                dismissArcMenu(); onMenuOpenMainActivity()
            }
        )

        val iconPad = (itemSize * 0.22f).toInt()
        items.forEachIndexed { idx, item ->
            val angle = angles[idx]
            val centerOffsetX = (radius * Math.cos(angle)).toFloat()
            val centerOffsetY = (radius * Math.sin(angle)).toFloat()
            val left = (cx + centerOffsetX - itemSize / 2f).toInt()
                .coerceIn(0, (screenW - itemSize).coerceAtLeast(0))
            val top = (cy + centerOffsetY - itemSize / 2f).toInt()
                .coerceIn(0, (screenH - itemSize).coerceAtLeast(0))

            val btn = ImageView(context).apply {
                setImageResource(item.iconRes)
                setBackgroundResource(item.bgRes)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                contentDescription = context.getString(item.labelRes)
                isClickable = true
                setOnClickListener { item.onTap() }
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
            }
            val lp = FrameLayout.LayoutParams(itemSize, itemSize).apply {
                leftMargin = left
                topMargin = top
            }
            root.addView(btn, lp)

            // 错峰入场：圆球向外"绽放"的感觉
            btn.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(40L * idx)
                .setDuration(220L)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(root, menuParams) }
        arcMenuView = root
    }

    private fun dismissArcMenu() {
        arcMenuView?.let { runCatching { wm.removeView(it) } }
        arcMenuView = null
    }
}
