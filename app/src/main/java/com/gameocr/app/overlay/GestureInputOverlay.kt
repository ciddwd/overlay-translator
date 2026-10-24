package com.gameocr.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.gameocr.app.R
import com.gameocr.app.data.*
import com.gameocr.app.ui.theme.ThemeMode

/** Full-screen input is owned only while drawing; the edge entry stream is forwarded separately. */
class GestureInputOverlay(context: Context) {
    private val context = AppLocalePrefs.live(context)
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val wm: WindowManager by lazy {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val windowContext = if (Build.VERSION.SDK_INT >= 31 && display != null) context.createWindowContext(display, type, null) else context
        windowContext.getSystemService(WindowManager::class.java)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var trail: GestureTrailView? = null
    private var title: TextView? = null
    private var hint: TextView? = null
    private var cancelCallback: (() -> Unit)? = null
    private var edge: GestureEdgeSession? = null
    private val pauseCheck = Runnable {
        edge?.let { session ->
            session.pause(SystemClock.uptimeMillis())
            if (session.phase == GestureEdgeSession.Phase.READY) {
                trail?.showMarker(session.anchor, true)
                setPrompt(R.string.gesture_draw_title, R.string.gesture_edge_ready_hint)
            }
        }
    }
    fun isShown() = root != null

    fun show(
        actionFor: (PresetGesture) -> GestureAction,
        loopRunning: () -> Boolean,
        onResult: (PresetGesture?) -> Unit,
        onCancel: () -> Unit,
    ): Boolean {
        if (isShown()) return true
        val dark = when (ThemeModePrefs.read(context)) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val surface = if (dark) 0xFF0F172A.toInt() else 0xFFFFFFFF.toInt()
        val foreground = if (dark) 0xFFF8FAFC.toInt() else 0xFF0F172A.toInt()
        val muted = if (dark) 0xFF94A3B8.toInt() else 0xFF475569.toInt()
        val border = if (dark) 0xFF1E293B.toInt() else 0xFFE2E8F0.toInt()
        cancelCallback = onCancel
        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) cancel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(if (dark) 0x55020617 else 0x170F172A)
        }
        trail = GestureTrailView(context, { result -> dismiss(); onResult(result) }, ::cancel) { gesture ->
            if (gesture == null) setPrompt(R.string.gesture_draw_title, R.string.gesture_draw_hint)
            else {
                val action = actionFor(gesture)
                if (action == GestureAction.NONE || action.targetSkill != null) {
                    setPrompt(R.string.gesture_unbound_title, R.string.gesture_unbound_hint)
                } else {
                    title?.setText(if (action == GestureAction.TOGGLE_LOOP) {
                        if (loopRunning()) R.string.gesture_loop_stop else R.string.gesture_loop_start
                    } else action.labelRes)
                    hint?.setText(R.string.gesture_release_hint)
                }
            }
        }
        container.addView(trail, FrameLayout.LayoutParams(-1, -1))
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(surface); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
            }
        }
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(context).apply { textSize = 14f; setTextColor(foreground); setText(R.string.gesture_draw_title) }
        hint = TextView(context).apply { textSize = 12f; setTextColor(muted); setText(R.string.gesture_draw_hint) }
        copy.addView(title); copy.addView(hint)
        toolbar.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(TextView(context).apply {
            textSize = 14f; setTextColor(foreground); setText(R.string.region_picker_btn_cancel)
            gravity = Gravity.CENTER; minHeight = dp(48); minWidth = dp(48)
            setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(-2, -2))
        container.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })
        val params = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        return try {
            wm.addView(container, params)
            root = container
            container.requestFocus()
            true
        } catch (failure: RuntimeException) {
            android.util.Log.e("GestureOverlay", "Cannot open gesture input", failure)
            dismiss()
            false
        }
    }

    /** Coordinates originate in the small edge window, which retains its original touch target. */
    fun forwardEdge(action: Int, screenX: Float, screenY: Float) {
        val view = trail ?: return
        if (!view.isLaidOut) {
            view.doOnLayout { if (trail === view) forwardEdge(action, screenX, screenY) }
            return
        }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val point = GesturePoint(screenX - location[0], screenY - location[1])
        val now = SystemClock.uptimeMillis()
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                edge = GestureEdgeSession(point, now, context.resources.displayMetrics.density)
                view.showMarker(point)
                setPrompt(R.string.gesture_edge_wait, R.string.gesture_edge_wait_hint)
                schedulePause()
            }
            MotionEvent.ACTION_MOVE -> edge?.let { session ->
                val wasDrawing = session.phase == GestureEdgeSession.Phase.DRAWING
                session.move(point, now)
                if (session.phase == GestureEdgeSession.Phase.DRAWING) {
                    if (!wasDrawing) view.beginStroke(session.anchor.x, session.anchor.y)
                    view.appendStroke(point.x, point.y)
                } else {
                    view.showMarker(session.marker, session.phase == GestureEdgeSession.Phase.READY)
                    if (session.phase == GestureEdgeSession.Phase.WAITING) schedulePause()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(pauseCheck)
                val drawn = edge?.phase == GestureEdgeSession.Phase.DRAWING
                edge = null
                view.showMarker(null)
                if (drawn) view.finishStroke(point.x, point.y)
                else setPrompt(R.string.gesture_draw_title, R.string.gesture_draw_hint)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
    }

    private fun schedulePause() {
        handler.removeCallbacks(pauseCheck)
        edge?.let { handler.postAtTime(pauseCheck, it.lastMovementAt + 240) }
    }
    private fun setPrompt(titleRes: Int, hintRes: Int) {
        title?.setText(titleRes); hint?.setText(hintRes)
    }
    fun cancel() {
        val callback = cancelCallback
        dismiss()
        callback?.invoke()
    }
    fun dismiss() {
        handler.removeCallbacks(pauseCheck)
        edge = null
        root?.let { wm.removeViewImmediate(it) }
        root = null; trail = null; title = null; hint = null; cancelCallback = null
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
