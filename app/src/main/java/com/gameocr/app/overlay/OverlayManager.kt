package com.gameocr.app.overlay

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.gameocr.app.R
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.FloatingWindowContentMode
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TranslationBlockInteractionMode
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.util.VerticalDiagnosticLog
import com.gameocr.app.util.physicalDisplaySize
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

internal fun performPlaybackOverlayDismiss(
    stopPlayback: () -> Unit,
    clearOverlay: () -> Unit,
): Result<Unit> {
    val stopResult = runCatching(stopPlayback)
    clearOverlay()
    return stopResult
}

/**
 * 译文叠加渲染。
 * - [showFullScreen]：可拖拽 / 可缩放的悬浮窗口（[DraggableOverlayWindow]），列出所有原文 → 译文；
 *   流式翻译走 [prepareFloatingWindow] + [updateFloatingWindowText]，逐段填入。
 * - [showBlocks]：按每段文本的 boundingBox 紧贴原文下方贴一行译文。
 * - [showLoadingHint]：点击瞬间立刻显示，给用户反馈，避免几秒空窗。
 */
class OverlayManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val ioScope: CoroutineScope,
    private val onTranslationBlockDetailRequested: (source: String, translation: String) -> Unit = { _, _ -> },
    private val onFloatingWindowDismissed: () -> Unit = {},
    @Volatile var textSizeSp: Int = 14,
    @Volatile var alpha: Float = 0.85f,
    @Volatile var regionOffset: android.graphics.Point = android.graphics.Point(0, 0),
    @Volatile var placement: OverlayPlacement = OverlayPlacement.BELOW,
    @Volatile var overlayStyleMode: OverlayStyleMode = OverlayStyleMode.FIXED,
    @Volatile var offsetX: Int = 0,
    @Volatile var offsetY: Int = 0,
    @Volatile var theme: OverlayTheme = OverlayTheme.CLASSIC_DARK,
    @Volatile var customBg: Int = 0xE6000000.toInt(),
    @Volatile var customFg: Int = 0xFFFFFFFF.toInt(),
    @Volatile var customBorder: Int = 0,
    @Volatile var customBorderWidthDp: Int = 0,
    /** 允许译文换行。关闭后强制单行（可能横向溢出原文宽度）。 */
    @Volatile var allowWrap: Boolean = true,
    /** 启用碰撞检测：限制译文不挤进相邻原文的 box。关闭后只受屏幕边界约束。 */
    @Volatile var avoidCollision: Boolean = true,
    /** 悬浮窗口内容形态。CaptureService 在 applyOverlayConfig 时同步。 */
    @Volatile var floatingWindowContentMode: FloatingWindowContentMode =
        FloatingWindowContentMode.SRC_AND_DST,
    @Volatile var translationBlockInteractionMode: TranslationBlockInteractionMode =
        TranslationBlockInteractionMode.COPY_BUTTON,
    @Volatile var translationBlockSelectionSpeechAction: TtsPlaybackAction? = null,
    /** CUSTOM 主题的边框样式（仅 CUSTOM 主题生效）。CaptureService 同步。 */
    @Volatile var customBorderStyle: BorderStyle = BorderStyle.SOLID,
    @Volatile var overlayTypeface: Typeface? = null,
    @Volatile var overlayTextStyle: OverlayTextStyle = OverlayTextStyle(),
    @Volatile var ocrDebugRedBoxActive: Boolean = false,
    @Volatile var ocrDebugShowSourceText: Boolean = true,
    @Volatile var ocrDebugShowTranslation: Boolean = false,
) {

    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var blocksView: View? = null
    private var blocksDialog: Dialog? = null
    private var loadingView: View? = null
    private var errorView: View? = null
    private var countdownView: View? = null
    // 译文 View 缓存：横排走 TextView，竖排走 VerticalTextView。updateBlockText 会按实际类型分支 setText
    private val blockViews = mutableMapOf<Int, View>()
    private val blockContents = mutableMapOf<Int, TranslationBlockContent>()
    private val blockStreamingUpdateCounts = mutableMapOf<Int, Int>()
    private val blockFrameUpdateCoalescers =
        mutableMapOf<Int, LatestFrameUpdateCoalescer<PendingOverlayTextUpdate>>()
    private var blocksDiagnosticId: Long? = null

    private data class TranslationBlockContent(
        val source: String,
        var translation: String,
    )

    private data class PendingOverlayTextUpdate(
        val text: String,
        val phase: AdaptiveTextLayoutPhase,
    )

    private data class OcrDebugBlockState(
        val source: String,
        val showSource: Boolean,
        val showTranslation: Boolean,
    )

    /** 悬浮窗口（[com.gameocr.app.data.RenderMode.FLOATING_WINDOW]）外壳。lazy 创建。 */
    private val floatingWindow: DraggableOverlayWindow by lazy {
        DraggableOverlayWindow(context, settingsRepository, ioScope)
    }
    /** 悬浮窗口唯一的连续文本宿主；流式更新会整体重建其 Spannable 内容。 */
    private var floatingContentView: StyledTranslationTextView? = null
    private val floatingStreamingUpdateCounts = mutableMapOf<Int, Int>()
    private val floatingFrameUpdateCoalescers =
        mutableMapOf<Int, LatestFrameUpdateCoalescer<PendingOverlayTextUpdate>>()
    /** 上一次悬浮窗口内容（用户改配色保存时重建内容，立即生效）。Pair = (src, dst)。 */
    private var lastFloatingPairs: MutableList<Pair<String, String>>? = null
    /** 上一次是流式还是整批显示。重建 content 时保留原渲染阶段。 */
    private var lastFloatingStreaming: Boolean = false

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    /**
     * 即时反馈：显示一个旋转 ProgressBar（图标，OCR 抓不到 → 避免 loading 文字被翻译）。
     * 透明圆底，浮在屏幕顶部中央。后续译文出来后会被替换。
     */
    fun showLoadingHint(): Boolean {
        clearLoading()
        val density = context.resources.displayMetrics.density
        val size = (40 * density).toInt()
        val pad = (8 * density).toInt()
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0xC0000000.toInt())
            }
            setPadding(pad, pad, pad, pad)
        }
        val pb = android.widget.ProgressBar(context).apply {
            // indeterminate 默认转圈
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        val pbLp = FrameLayout.LayoutParams(size, size)
        container.addView(pb, pbLp)

        val params = newLayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * density).toInt()
        }
        return runCatching {
            wm.addView(container, params)
            loadingView = container
        }.onFailure {
            Timber.w(it, "Failed to show loading overlay")
        }.isSuccess
    }

    /** 关闭 loading 圈。captureOnce 失败 / 帧差跳过等"没有译文要显示"的路径里调用，避免一直转圈。 */
    fun dismissLoading() {
        loadingView?.let { runCatching { wm.removeView(it) } }
        loadingView = null
    }

    private fun clearLoading() = dismissLoading()

    /**
     * 错误悬浮提示。国产 ROM（HyperOS / MIUI）对后台 Service 的 Toast 会静默丢弃，
     * 用户只看到 loading 圈转一下就消失，必须翻日志才知道失败原因。
     * 这里用 [TYPE_APPLICATION_OVERLAY] 悬浮窗显示一段红底文字，自动定时关闭；
     * 与 [showLoadingHint] 同链路，全屏游戏沉浸模式下也能可靠显示。
     */
    fun showErrorHint(message: String, durationMs: Long = 4500L) {
        runCatching { errorView?.let { wm.removeView(it) } }
        errorView = null

        val density = context.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (12 * density).toInt()
        val maxW = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()

        val tv = TextView(context).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = maxW
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xF0B71C1C.toInt())
            }
            setPadding(padH, padV, padH, padV)
            addView(tv)
        }

        val params = newLayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            // 屏幕下方 1/4 处，避开 loading 圈（顶部）与导航栏
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * density).toInt()
        }
        runCatching { wm.addView(container, params) }
        errorView = container

        // 用户点击立即关闭
        container.setOnClickListener {
            if (errorView === container) {
                runCatching { wm.removeView(container) }
                errorView = null
            }
        }
        // 定时自动关闭
        container.postDelayed({
            if (errorView === container) {
                runCatching { wm.removeView(container) }
                errorView = null
            }
        }, durationMs)
    }

    fun dismissError() {
        errorView?.let { runCatching { wm.removeView(it) } }
        errorView = null
    }

    /**
     * 循环模式启动倒计时圆圈。屏幕正中显示灰底白字大圆圈（3 → 2 → 1），圆下方一行小字提示。
     *
     * **关键约束**：MediaProjection 会把所有 system overlay 一起截到画面里——若倒计时圆圈
     * 还在屏幕上时执行 `captureOnce()`，OCR 会把"3 自动翻译开启中"这类文字翻译出来贴回屏幕。
     * 所以 [onFinish] 必须在 `wm.removeView` **之后** + 一个小的 VSYNC 缓冲后才回调，调用方
     * 在 `onFinish` 里启动第一次截屏即可保证画面干净。
     *
     * 重复调用会先取消上一个倒计时。
     */
    fun showStartCountdown(seconds: Int = 3, hintText: String, onFinish: () -> Unit) {
        cancelStartCountdown()

        val density = context.resources.displayMetrics.density
        val circleSize = (140 * density).toInt()
        val gapDp = (12 * density).toInt()

        val countText = TextView(context).apply {
            text = seconds.toString()
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6303030.toInt())
            }
            addView(
                countText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        val hintTv = TextView(context).apply {
            text = hintText
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(circle, LinearLayout.LayoutParams(circleSize, circleSize))
        container.addView(
            hintTv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = gapDp }
        )

        val params = newLayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        }
        runCatching { wm.addView(container, params) }
        countdownView = container

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun tick(left: Int) {
            if (countdownView !== container) return // 已被 cancelStartCountdown
            if (left <= 0) {
                runCatching { wm.removeView(container) }
                if (countdownView === container) countdownView = null
                // 给 WindowManager ~3 帧时间让圆圈真正从屏幕消失，再放调用方进 captureOnce
                handler.postDelayed({ onFinish() }, 80L)
                return
            }
            countText.text = left.toString()
            handler.postDelayed({ tick(left - 1) }, 1000L)
        }
        tick(seconds)
    }

    /** 倒计时未结束时调用：移除圆圈，**不** 触发 onFinish（适合「用户中途又切回 OFF」的场景）。 */
    fun cancelStartCountdown() {
        countdownView?.let { runCatching { wm.removeView(it) } }
        countdownView = null
    }

    /**
     * 中性悬浮提示，跟 [showErrorHint] 同链路但深灰底色，用于循环开 / 关等非错误反馈。
     * 国产 ROM 对后台 Service 的 [android.widget.Toast.makeText] 会静默丢弃，这里用悬浮窗替代。
     */
    fun showInfoHint(message: String, durationMs: Long = 1800L) {
        runCatching { errorView?.let { wm.removeView(it) } }
        errorView = null

        val density = context.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()
        val maxW = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()

        val tv = TextView(context).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = maxW
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(0xE6303030.toInt()) // 深灰半透明（区别于 error 的红色）
            }
            setPadding(padH, padV, padH, padV)
            addView(tv)
        }

        val params = newLayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * density).toInt()
        }
        runCatching { wm.addView(container, params) }
        errorView = container

        container.setOnClickListener {
            if (errorView === container) {
                runCatching { wm.removeView(container) }
                errorView = null
            }
        }
        container.postDelayed({
            if (errorView === container) {
                runCatching { wm.removeView(container) }
                errorView = null
            }
        }, durationMs)
    }

    /**
     * 悬浮窗口模式（[com.gameocr.app.data.RenderMode.FLOATING_WINDOW]）：一次性整批渲染。
     * 批模式翻译（如 DeepL）走这条；流模式走 [prepareFloatingWindow] + [updateFloatingWindowText]。
     */
    fun showFullScreen(pairs: List<Pair<String, String>>) {
        clearBlocksAndLoading()
        if (pairs.isEmpty()) {
            clearFloatingWindow()
            return
        }
        floatingStreamingUpdateCounts.clear()
        VerticalDiagnosticLog.i("overlay showFullScreen pairs=${pairs.size} textSizeSp=$textSizeSp mode=$floatingWindowContentMode")
        pairs.forEachIndexed { index, (src, dst) ->
            VerticalDiagnosticLog.i(
                "overlay showFullScreen #${index + 1} src=${src.toDiagText()} dst=${dst.toDiagText()}"
            )
        }
        lastFloatingPairs = pairs.toMutableList()
        lastFloatingStreaming = false
        floatingWindow.applySettings()
        val content = buildFloatingContent(pairs, streaming = false)
        if (floatingWindow.isShown()) {
            floatingWindow.setContent(content)
        } else {
            floatingWindow.show(content, onDismiss = ::handleFloatingWindowUserDismiss)
        }
    }

    /**
     * 流式翻译启动：先把所有原文铺到窗口里，每段译文用占位 "…"，等 [updateFloatingWindowText]
     * 逐段填入。等价于 [showBlocks] 之于 BLOCKS 模式，但内容渲染在悬浮窗里。
     */
    fun prepareFloatingWindow(sources: List<String>) {
        clearBlocksAndLoading()
        if (sources.isEmpty()) {
            clearFloatingWindow()
            return
        }
        floatingStreamingUpdateCounts.clear()
        VerticalDiagnosticLog.i("overlay prepareFloatingWindow sources=${sources.size} textSizeSp=$textSizeSp mode=$floatingWindowContentMode")
        sources.forEachIndexed { index, source ->
            VerticalDiagnosticLog.i("overlay prepareFloatingWindow #${index + 1} src=${source.toDiagText()}")
        }
        val placeholder = sources.map { it to "…" }
        lastFloatingPairs = placeholder.toMutableList()
        lastFloatingStreaming = true
        floatingWindow.applySettings()
        val content = buildFloatingContent(placeholder, streaming = true)
        if (floatingWindow.isShown()) {
            floatingWindow.setContent(content)
        } else {
            floatingWindow.show(content, onDismiss = ::handleFloatingWindowUserDismiss)
        }
    }

    /** 流式：更新第 [index] 段译文。需先调过 [prepareFloatingWindow]。 */
    internal fun updateFloatingWindowText(
        index: Int,
        text: String,
        phase: AdaptiveTextLayoutPhase = AdaptiveTextLayoutPhase.FINAL,
    ) {
        lastFloatingPairs?.let { list ->
            if (index in list.indices) {
                val (src, _) = list[index]
                list[index] = src to text
            }
        }
        if (phase == AdaptiveTextLayoutPhase.STREAMING) {
            floatingStreamingUpdateCounts[index] = (floatingStreamingUpdateCounts[index] ?: 0) + 1
            val target = floatingContentView ?: return
            val coalescer = floatingFrameUpdateCoalescers.getOrPut(index) {
                LatestFrameUpdateCoalescer(
                    scheduleFrame = { target.postOnAnimation(it) },
                    publish = { update -> applyFloatingWindowText(index, update) },
                )
            }
            coalescer.submit(PendingOverlayTextUpdate(text, phase))
            return
        }
        val streamingUpdates = floatingStreamingUpdateCounts.remove(index) ?: 0
        val coalescer = floatingFrameUpdateCoalescers.remove(index)
        coalescer?.discardPending()
        val stats = coalescer?.stats() ?: FrameUpdateStats(0, 0)
        VerticalDiagnosticLog.i(
            "overlay updateFloatingWindowText block#${index + 1} phase=${phase.name} " +
                "streamingUpdates=$streamingUpdates renderedUpdates=${stats.published} " +
                "coalescedUpdates=${stats.coalesced} " + text.toDiagText()
        )
        applyFloatingWindowText(index, PendingOverlayTextUpdate(text, phase))
    }

    /** 仅悬浮窗口模式有效：是否当前可见。 */
    fun isFloatingWindowShown(): Boolean = floatingWindow.isShown()

    /** 当前悬浮窗口在屏幕坐标系中的边界；用于循环截图后遮蔽自身内容。 */
    fun currentFloatingWindowBounds(): android.graphics.Rect? = floatingWindow.currentBounds()

    /** 循环截图前临时停止/恢复悬浮窗口绘制，不销毁常驻窗口及其内容。 */
    fun setFloatingWindowHiddenForCapture(hidden: Boolean): Boolean =
        floatingWindow.setHiddenForCapture(hidden)

    /**
     * 重新加载 DraggableOverlayWindow 字段：在 applyOverlayConfig 时由外部调用，确保
     * 已显示的窗口跟着主题 / contentMode 变化。
     */
    /**
     * **线程约束：必须在主线程调用**。内部会改 rootView.background / setContent，View 系统只
     * 接受主线程操作。调用方（CaptureService.applyOverlayConfig）记得 mainScope.launch 包一层。
     */
    fun syncFloatingWindowFromSettings(settings: Settings) {
        floatingWindow.applyFromSettings(settings)
        // 配色 / 字号 / 内容模式变了 → 重建内容，让用户在 Settings 改完立即看到效果。
        // 不在显示中或者从没渲染过则不动。
        val pairs = lastFloatingPairs ?: return
        if (!floatingWindow.isShown()) return
        val content = buildFloatingContent(pairs, streaming = lastFloatingStreaming)
        floatingWindow.setContent(content)
    }

    /** 重置悬浮窗口位置/大小到默认（居中 + 默认尺寸）；UI 上的"恢复默认"按钮回调到这里。 */
    fun resetFloatingWindow() {
        floatingWindow.resetToDefault()
    }

    /** 屏幕方向变化时被 CaptureService.onConfigurationChanged 调用——按比例重算悬浮窗位置。 */
    fun onConfigurationChanged() {
        val dm = context.resources.displayMetrics
        VerticalDiagnosticLog.i(
            "${blocksDiagnosticId.toDiagPrefix()}overlay configurationChanged " +
                "screen=${dm.widthPixels}x${dm.heightPixels} activeBlocks=${blockViews.size}"
        )
        floatingWindow.onConfigurationChanged()
    }

    /**
     * 构造悬浮窗口内容 View（不含外壳——外壳由 DraggableOverlayWindow 提供）。
     * 按 [floatingWindowContentMode] 分支：
     *  - SRC_AND_DST：每段「・原文」+「译文」上下两行
     *  - DST_ONLY：只显示译文，段间用分隔线
     */
    private fun buildFloatingContent(
        pairs: List<Pair<String, String>>,
        streaming: Boolean
    ): View {
        floatingFrameUpdateCoalescers.values.forEach { it.discardPending() }
        floatingFrameUpdateCoalescers.clear()
        val density = context.resources.displayMetrics.density
        val contentView = StyledTranslationTextView(context).apply {
            text = buildFloatingWindowText(pairs)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
            setTextColor(themeFgColor())
            applyOverlayTextStyle(overlayTextStyle, overlayTypeface)
            val padH = (16 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, padV)
            adaptiveTextLayoutPhase = if (streaming) {
                AdaptiveTextLayoutPhase.STREAMING
            } else {
                AdaptiveTextLayoutPhase.FINAL
            }
        }
        floatingContentView = contentView
        floatingWindow.configureSelectableText(contentView,
            isContentSelectable = {
                hasSelectableFloatingWindowContent(
                    lastFloatingPairs ?: pairs,
                    floatingWindowContentMode,
                )
            },
            speechLabel = context.getString(R.string.word_card_speak_selection),
            selectionSpeechAction = {
                translationBlockSelectionSpeechAction?.onStart
            },
        )
        return contentView
    }

    private fun buildFloatingWindowText(pairs: List<Pair<String, String>>): CharSequence {
        val result = SpannableStringBuilder()
        val sourceScale = (textSizeSp - 1).coerceAtLeast(10).toFloat() /
            textSizeSp.coerceAtLeast(1).toFloat()
        floatingWindowTextSegments(pairs, floatingWindowContentMode).forEach { segment ->
            val start = result.length
            result.append(segment.text)
            val end = result.length
            if (start == end) return@forEach
            when (segment.role) {
                FloatingWindowTextRole.SOURCE -> {
                    result.setSpan(
                        ForegroundColorSpan(themeFgMutedColor()),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    result.setSpan(
                        RelativeSizeSpan(sourceScale),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                FloatingWindowTextRole.TRANSLATION -> Unit
                FloatingWindowTextRole.SEPARATOR -> result.setSpan(
                    ForegroundColorSpan(themeFgMutedColor()),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return result
    }

    private fun DraggableOverlayWindow.applySettings() {
        theme = this@OverlayManager.theme
        alpha = this@OverlayManager.alpha
        customBg = this@OverlayManager.customBg
        customFg = this@OverlayManager.customFg
        customBorder = this@OverlayManager.customBorder
        customBorderWidthDp = this@OverlayManager.customBorderWidthDp
    }

    fun showBlocks(
        blocks: List<Pair<TextBlock, String>>,
        orientation: TextOrientation = TextOrientation.HORIZONTAL_LTR,
        diagnosticId: Long? = null,
        adaptiveStyles: List<AdaptiveOverlayStyle> = emptyList(),
        followBlockOrientations: Boolean = false,
    ) {
        clearLoading()
        clear()
        if (blocks.isEmpty()) return
        blocksDiagnosticId = diagnosticId
        if (ocrDebugRedBoxActive) {
            showOcrDebugBlocks(blocks, diagnosticId)
            return
        }
        val diagPrefix = diagnosticId.toDiagPrefix()

        val root = FrameLayout(context).apply {
            this.alpha = if (overlayStyleMode == OverlayStyleMode.ADAPTIVE) 1f else this@OverlayManager.alpha
        }
        val dm = context.resources.displayMetrics
        val physicalSize = physicalDisplaySize(context)
        val screenW = physicalSize.width
        val screenH = physicalSize.height
        val interactionPlan = translationBlockInteractionPlan(translationBlockInteractionMode)
        val defaultIsVertical = orientation == TextOrientation.VERTICAL_RTL ||
            orientation == TextOrientation.VERTICAL_LTR
        val defaultLeftToRight = orientation == TextOrientation.VERTICAL_LTR
        VerticalDiagnosticLog.i(
            "${diagPrefix}overlay showBlocks count=${blocks.size} orientation=$orientation vertical=$defaultIsVertical " +
                "leftToRight=$defaultLeftToRight followBlockOrientations=$followBlockOrientations " +
                "screen=${screenW}x${screenH} resources=${dm.widthPixels}x${dm.heightPixels} " +
                "textSizeSp=$textSizeSp " +
                "alpha=$alpha placement=$placement offset=(${offsetX},${offsetY}) " +
                "regionOffset=(${regionOffset.x},${regionOffset.y}) allowWrap=$allowWrap " +
                "avoidCollision=$avoidCollision theme=$theme styleMode=$overlayStyleMode"
        )
        // 估算每行像素高度（行间距系数 1.3，跟 setLineSpacing 一致）
        val lineHeightPx = (
            textSizeSp * dm.density * 1.3f * overlayTextStyle.lineSpacingMultiplier
        ).toInt().coerceAtLeast(16)
        val verticalTextPaddingHorizontalPx = 8
        val verticalMinReadableSlotWidthPx = (
            ceil(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    textSizeSp.toFloat(),
                    dm
                ) * 1.15f
            ).toInt() + verticalTextPaddingHorizontalPx * 2
        ).coerceAtLeast(1)

        // 所有 bounding box 一份用于碰撞检测（不影响 blocks 原始顺序，流式 updateBlockText 仍按 idx 找）
        val allBoxes = blocks.map { it.first.boundingBox }
        val allOverlayRects = allBoxes.map {
            OverlayIntRect(
                left = it.left + regionOffset.x + offsetX,
                top = it.top + regionOffset.y + offsetY,
                right = it.right + regionOffset.x + offsetX,
                bottom = it.bottom + regionOffset.y + offsetY
            )
        }

        blocks.forEachIndexed { idx, (block, dst) ->
            val b: Rect = block.boundingBox
            val blockOrientation = resolveOverlayBlockOrientation(
                pageOrientation = orientation,
                blockOrientation = block.layoutOrientation,
                followRecognition = followBlockOrientations,
            )
            val isVertical = blockOrientation == TextOrientation.VERTICAL_RTL ||
                blockOrientation == TextOrientation.VERTICAL_LTR
            val leftToRight = blockOrientation == TextOrientation.VERTICAL_LTR
            val adaptiveStyle = if (overlayStyleMode == OverlayStyleMode.ADAPTIVE) {
                adaptiveStyles.getOrNull(idx)
            } else {
                null
            }
            val blockTextSizeSp = adaptiveStyle?.maxTextSizeSp ?: textSizeSp.toFloat()
            val blockTextStyle = if (adaptiveStyle != null) OverlayTextStyle() else overlayTextStyle
            val blockBackground = adaptiveStyle?.let { adaptiveBg(it, block) } ?: themeBg()
            val blockForeground = adaptiveStyle?.foregroundColor ?: themeFgColor()
            blockContents[idx] = TranslationBlockContent(block.text, dst)
            val baseLeft = (b.left + regionOffset.x + offsetX).coerceAtLeast(0)
            val overlayRect = allOverlayRects[idx]
            val origW = (b.right - b.left).coerceAtLeast(0)
            val origH = (b.bottom - b.top).coerceAtLeast(0)
            val adaptiveSize = adaptiveStyle?.let { adaptiveOverlaySize(origW, origH) }
            VerticalDiagnosticLog.i(
                "${diagPrefix}overlay block#${idx + 1} pageOrientation=$orientation " +
                    "blockOrientation=$blockOrientation box=${b.toDiagString()} " +
                    "screenBox=${overlayRect.toDiagString()} orig=${origW}x${origH} " +
                    "src=${block.text.toDiagText()} dst=${dst.toDiagText()} " +
                    (adaptiveStyle?.let {
                        val estimate = it.textSizeEstimate
                        "adaptiveBg=#${it.backgroundColor.toUInt().toString(16)} " +
                            "adaptiveFg=#${it.foregroundColor.toUInt().toString(16)} " +
                            "adaptiveMaxSp=${String.format(java.util.Locale.US, "%.1f", it.maxTextSizeSp)} " +
                            "textSizeSource=${estimate.source.name} " +
                            "sourceMedianPx=${estimate.sourceLineMedianPx?.let { value ->
                                String.format(java.util.Locale.US, "%.1f", value)
                            } ?: "none"} " +
                            "glyphs=${estimate.sourceGlyphCount} " +
                            "scaledDensity=${String.format(java.util.Locale.US, "%.2f", estimate.scaledDensity)} " +
                            "sourceBoxes=${block.sourceBoxes.size} " +
                            "eraseMode=${if (block.sourceBoxes.isEmpty()) "full" else "source_boxes"} " +
                            "exactSize=${adaptiveSize?.width}x${adaptiveSize?.height} " +
                            "confidence=${String.format(java.util.Locale.US, "%.2f", it.confidence)} " +
                            "fallback=${it.fallbackReason.name}"
                    } ?: "adaptive=none")
            )

            // 四方向碰撞检测：用矩形相交判断"水平重叠"，比"中心距离 < origW"准得多
            // （短原文 + 长译文的场景，中心距离误判会漏掉下方实际相撞的 box）。
            val verticalTolerance = (origH * 1.5).toInt().coerceAtLeast(30)

            // 右邻：同一行内（top 接近）、左边比本块右
            val rightNeighborLeft = if (avoidCollision) {
                allBoxes.asSequence()
                    .filter { it !== b }
                    .filter { kotlin.math.abs(it.top - b.top) <= verticalTolerance }
                    .filter { it.left > b.right - 10 }
                    .minOfOrNull { it.left }
                    ?: screenW
            } else screenW

            val collisionMaxW = (rightNeighborLeft + regionOffset.x + offsetX - baseLeft - 8)
                .coerceAtLeast(origW.coerceAtLeast(120))
            val finalMaxW = minOf(collisionMaxW, screenW - baseLeft - 8).coerceAtLeast(120)

            // 译文展开后的水平范围（OCR 坐标系，取 finalMaxW 作为上限，保守估算最坏情况）
            val textRightOcr = (baseLeft + finalMaxW - regionOffset.x - offsetX).coerceAtMost(screenW)
            val textLeftOcr = b.left  // 译文起点对齐原文左边

            val baseTop = when (placement) {
                OverlayPlacement.BELOW -> b.bottom + regionOffset.y + 2 + offsetY
                OverlayPlacement.OVERLAP -> b.top + regionOffset.y + offsetY
                OverlayPlacement.ABOVE -> b.top + regionOffset.y - (textSizeSp * 3).toInt() - 4 + offsetY
            }
            val resolvedBaseTop = adaptiveSize?.let { b.top + regionOffset.y + offsetY } ?: baseTop

            // 下邻：水平有矩形相交 + top 在本块下方。比"中心距离"准。
            val belowNeighborTop = if (avoidCollision) {
                allBoxes.asSequence()
                    .filter { it !== b }
                    .filter { it.right > textLeftOcr && it.left < textRightOcr }  // 水平 overlap
                    .filter { it.top > b.bottom - 4 }
                    .minOfOrNull { it.top }
                    ?: screenH
            } else screenH

            val overlayGapPx = (8 * dm.density).toInt().coerceAtLeast(8)
            val belowBoundaryScreen = if (belowNeighborTop >= screenH) {
                screenH
            } else {
                belowNeighborTop + regionOffset.y + offsetY
            }
            val horizontalAvailableHeightPx = when (placement) {
                OverlayPlacement.ABOVE -> {
                    b.top + regionOffset.y + offsetY - resolvedBaseTop - overlayGapPx
                }
                OverlayPlacement.BELOW,
                OverlayPlacement.OVERLAP -> belowBoundaryScreen - resolvedBaseTop - overlayGapPx
            }.coerceAtLeast(lineHeightPx)

            var verticalSlotForLayout: VerticalOverlaySlot? = null
            var verticalHeightForLayout = FrameLayout.LayoutParams.WRAP_CONTENT
            val view: View = if (isVertical) {
                val slot = adaptiveSize?.let {
                    val left = overlayRect.left.coerceIn(0, screenW - 1)
                    val right = overlayRect.right.coerceIn(left + 1, screenW)
                    VerticalOverlaySlot(left = left, right = right)
                } ?: verticalOverlaySlot(
                    rect = overlayRect,
                    allRects = allOverlayRects,
                    screenWidth = screenW,
                    rightToLeft = !leftToRight,
                    minGapPx = (8 * dm.density).toInt().coerceAtLeast(8),
                    minReadableWidthPx = verticalMinReadableSlotWidthPx
                )
                val verticalHeightPx = adaptiveSize?.height ?: origH
                    .coerceAtLeast(lineHeightPx * 2 + 8)
                    .coerceAtLeast(1)
                verticalSlotForLayout = slot
                verticalHeightForLayout = verticalHeightPx
                Timber.tag("Overlay").i(
                    "vertical block #%d box=(%d,%d,%d,%d) slot=(%d,%d) slotW=%d minW=%d h=%d",
                    idx + 1,
                    overlayRect.left,
                    overlayRect.top,
                    overlayRect.right,
                    overlayRect.bottom,
                    slot.left,
                    slot.right,
                    slot.width,
                    verticalMinReadableSlotWidthPx,
                    verticalHeightPx
                )
                VerticalDiagnosticLog.i(
                    "${diagPrefix}overlay vertical block#${idx + 1} slot=${slot.toDiagString()} " +
                        "slotW=${slot.width} minW=$verticalMinReadableSlotWidthPx " +
                        "height=$verticalHeightPx leftToRight=$leftToRight " +
                        "normalizedDst=${normalizeVerticalOverlayText(dst).toDiagText()}"
                )
                // 竖排（tategaki）走 VerticalTextView：逐字纵向画，列从右往左换。
                // 强制 OVERLAP 语义——竖排里 BELOW/ABOVE 没意义（日漫气泡是固定的，译文
                // 覆盖原文位置）；用户即使选了 BELOW/ABOVE，竖排也按 OVERLAP 处理。
                VerticalTextView(context).apply verticalView@ {
                    this.text = dst
                    this.leftToRight = leftToRight
                    this.typeface = overlayTypeface
                    applyTextStyle(blockTextStyle)
                    this.background = blockBackground
                    setTextColor(blockForeground)
                    setTextSizeSp(blockTextSizeSp)
                    if (adaptiveStyle != null) {
                        minimumReadableTextSizeSp = ADAPTIVE_MIN_TEXT_SIZE_SP.toFloat()
                        minimumReadableTextSizeRatio = 0f
                        adaptiveTextFitEnabled = true
                        adaptiveMaximumTextSizeSp = ADAPTIVE_MAX_TEXT_SIZE_SP.toFloat()
                        adaptiveTextLayoutPhase = resolveAdaptiveTextLayoutPhase(dst)
                        onAdaptiveTextFitResolved = { snapshot ->
                            val expanded = expandAdaptiveVerticalViewport(
                                view = this@verticalView,
                                blockIndex = idx,
                                sourceRect = overlayRect,
                                allRects = allOverlayRects,
                                screenWidth = screenW,
                                rightToLeft = !leftToRight,
                                minGapPx = (8 * dm.density).toInt().coerceAtLeast(8),
                                snapshot = snapshot,
                                diagPrefix = diagPrefix,
                            )
                            if (!expanded) {
                                logAdaptiveVerticalTextSize(
                                    diagPrefix = diagPrefix,
                                    blockIndex = idx,
                                    style = adaptiveStyle,
                                    snapshot = snapshot,
                                )
                            }
                        }
                    }
                    // 竖排高度由 LayoutParams 固定到原文 box；Drawer 会按 boundsH 自动换列。
                    minimumHeight = verticalHeightPx
                    if (adaptiveStyle != null) {
                        setPadding(0, 0, 0, 0)
                    } else {
                        setPadding(verticalTextPaddingHorizontalPx, 4, verticalTextPaddingHorizontalPx, 4)
                    }
                }
            } else {
                val horizontalRightToLeft = blockOrientation == TextOrientation.HORIZONTAL_RTL
                StyledTranslationTextView(context).apply horizontalView@ {
                    this.horizontalRightToLeft = horizontalRightToLeft
                    text = if (horizontalRightToLeft) horizontalRtlDisplayText(dst) else dst
                    // RLO keeps logical paragraph order but lets Android reorder each wrapped line.
                    textDirection = if (horizontalRightToLeft) {
                        View.TEXT_DIRECTION_RTL
                    } else {
                        View.TEXT_DIRECTION_LTR
                    }
                    gravity = if (horizontalRightToLeft) {
                        Gravity.END
                    } else {
                        Gravity.START
                    }
                    background = blockBackground
                    setTextColor(blockForeground)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, blockTextSizeSp)
                    applyOverlayTextStyle(
                        style = blockTextStyle,
                        baseTypeface = overlayTypeface,
                        baseLineSpacingMultiplier = 1.05f
                    )
                    if (adaptiveStyle != null && allowWrap) {
                        adaptiveTextFitEnabled = true
                        adaptiveTextLayoutPhase = resolveAdaptiveTextLayoutPhase(dst)
                        val initialMaxSizeSp = when (adaptiveTextLayoutPhase) {
                            AdaptiveTextLayoutPhase.PLACEHOLDER -> adaptiveStyle.maxTextSizeSp
                            AdaptiveTextLayoutPhase.STREAMING,
                            AdaptiveTextLayoutPhase.FINAL -> ADAPTIVE_MAX_TEXT_SIZE_SP.toFloat()
                        }
                        adaptiveAutoSizeMaxSp(initialMaxSizeSp)?.let { maxSizeSp ->
                            setAutoSizeTextTypeUniformWithConfiguration(
                                ADAPTIVE_MIN_TEXT_SIZE_SP,
                                maxSizeSp,
                                1,
                                TypedValue.COMPLEX_UNIT_SP,
                            )
                        }
                        onAdaptiveTextLayoutResolved = { snapshot ->
                            val expanded = expandAdaptiveHorizontalViewport(
                                view = this@horizontalView,
                                blockIndex = idx,
                                topPx = resolvedBaseTop.coerceAtLeast(0),
                                screenHeight = screenH,
                                lowerBoundaryPx = belowBoundaryScreen,
                                gapPx = overlayGapPx,
                                snapshot = snapshot,
                                diagPrefix = diagPrefix,
                            )
                            if (!expanded) {
                                logAdaptiveHorizontalTextSize(
                                    diagPrefix = diagPrefix,
                                    blockIndex = idx,
                                    style = adaptiveStyle,
                                    snapshot = snapshot,
                                )
                            }
                        }
                    }
                    if (horizontalRightToLeft) {
                        gravity = (gravity and Gravity.VERTICAL_GRAVITY_MASK) or Gravity.END
                        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    }
                    if (allowWrap) {
                        setSingleLine(false)
                        // Use the actual vertical slot. A fixed line cap leaves most of a tall
                        // merged OVERLAP box empty while TextView scrolls inside its first rows.
                        maxLines = horizontalOverlayMaxLines(
                            allowWrap = true,
                            adaptiveTextFitEnabled = adaptiveStyle != null,
                            availableHeightPx = horizontalAvailableHeightPx,
                            estimatedLineHeightPx = lineHeightPx,
                        )
                        ellipsize = null
                    } else {
                        // 强制单行模式：长译文不再显示"…"截断，改用 MARQUEE 跑马灯——文本超
                        // 出可视区域时自动横向滚动，能看到完整内容；短文本则像普通 TextView。
                        // marquee 需要 view 拿到 focus 或 isSelected=true 才会启动；overlay 窗
                        // 口拿不到 focus（我们设的 FLAG_NOT_FOCUSABLE），所以靠 isSelected。
                        setSingleLine(true)
                        maxLines = horizontalOverlayMaxLines(
                            allowWrap = false,
                            adaptiveTextFitEnabled = adaptiveStyle != null,
                            availableHeightPx = horizontalAvailableHeightPx,
                            estimatedLineHeightPx = lineHeightPx,
                        )
                        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                        marqueeRepeatLimit = -1
                        isSelected = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    isHorizontalFadingEdgeEnabled = false
                    // 智能 maxWidth：受 (相邻块左边界, 屏幕右边) 双重约束
                    maxWidth = minOf(collisionMaxW, screenW - baseLeft - 8)
                        .coerceAtLeast(120)
                    if (adaptiveStyle != null) {
                        setPadding(0, 0, 0, 0)
                    } else if (placement == OverlayPlacement.OVERLAP) {
                        minWidth = origW
                        minHeight = origH
                        setPadding(8, 4, 8, 4)
                    }
                }
            }

            // 竖排位置：强制 OVERLAP。RTL 时把 View 右边缘锚到原文 box 右边缘，让新增列向左展开；
            // LTR 时把左边缘锚到原文 box 左边缘。横排走原 placement 逻辑。
            val finalLeft: Int
            val finalTop: Int
            val finalRight: Int
            if (isVertical) {
                finalLeft = (b.left + regionOffset.x + offsetX).coerceAtLeast(0)
                finalRight = (b.right + regionOffset.x + offsetX).coerceIn(0, screenW)
                finalTop = (b.top + regionOffset.y + offsetY).coerceAtLeast(0)
            } else {
                finalLeft = baseLeft
                finalRight = 0
                finalTop = resolvedBaseTop.coerceAtLeast(0)
            }
            val lp = FrameLayout.LayoutParams(
                if (adaptiveSize != null) {
                    adaptiveSize.width
                } else if (isVertical) {
                    verticalSlotForLayout?.width ?: FrameLayout.LayoutParams.WRAP_CONTENT
                } else {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                },
                if (adaptiveSize != null) {
                    adaptiveSize.height
                } else if (isVertical) {
                    verticalHeightForLayout.coerceAtLeast(1)
                } else {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                }
            ).apply {
                topMargin = finalTop
                if (isVertical && !leftToRight) {
                    gravity = Gravity.TOP or Gravity.RIGHT
                    rightMargin = (screenW - (verticalSlotForLayout?.right ?: finalRight)).coerceAtLeast(0)
                } else if (isVertical) {
                    leftMargin = verticalSlotForLayout?.left ?: finalLeft
                } else {
                    leftMargin = finalLeft
                }
            }
            VerticalDiagnosticLog.i(
                "${diagPrefix}overlay layout block#${idx + 1} left=${lp.leftMargin} top=${lp.topMargin} " +
                    "rightMargin=${lp.rightMargin} width=${lp.width} height=${lp.height} gravity=${lp.gravity} " +
                    "baseTop=$baseTop resolvedBaseTop=$resolvedBaseTop " +
                    "finalMaxW=$finalMaxW belowNeighborTop=$belowNeighborTop"
            )
            root.addView(view, lp)
            blockViews[idx] = view
            configureTranslationBlockView(idx, view, interactionPlan)
            view.post {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                VerticalDiagnosticLog.i(
                    "${diagPrefix}overlay measured block#${idx + 1} " +
                        "screenPos=(${location[0]},${location[1]}) measured=${view.width}x${view.height}"
                )
            }
        }
        root.setOnClickListener { clear() }

        val params = newLayoutParams(focusable = interactionPlan.windowFocusable)
        val cutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cutoutModeLogLabel(params.layoutInDisplayCutoutMode)
        } else {
            cutoutModeLogLabel(null)
        }
        val host = if (interactionPlan.useDecorViewActionModeHost) "dialog" else "window-manager"
        VerticalDiagnosticLog.i(
            "${diagPrefix}overlay window add host=$host screen=${screenW}x$screenH type=${params.type} " +
                "flags=0x${params.flags.toString(16)} cutoutMode=$cutoutMode"
        )
        val addResult = if (interactionPlan.useDecorViewActionModeHost) {
            showBlocksInActionModeDialog(root, params)
        } else {
            runCatching { wm.addView(root, params) }
        }
        addResult
            .onSuccess { VerticalDiagnosticLog.i("${diagPrefix}overlay window added host=$host") }
            .onFailure { VerticalDiagnosticLog.w(it, "${diagPrefix}overlay window add failed host=$host") }
        blocksView = root
    }

    private fun showBlocksInActionModeDialog(
        root: View,
        params: WindowManager.LayoutParams,
    ): Result<Unit> {
        var pendingDialog: Dialog? = null
        return runCatching {
            val dialog = Dialog(context, R.style.Theme_GameOcr_Transparent)
                .also { pendingDialog = it }
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setContentView(root)
            val window = requireNotNull(dialog.window) { "Overlay dialog window is unavailable" }
            window.setType(params.type)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0f)
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            window.attributes = window.attributes.apply {
                width = params.width
                height = params.height
                gravity = params.gravity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = params.layoutInDisplayCutoutMode
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = params.fitInsetsTypes
                    fitInsetsSides = params.fitInsetsSides
                }
            }
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            dialog.show()
            window.setLayout(params.width, params.height)
            blocksDialog = dialog
        }.onFailure {
            runCatching { pendingDialog?.dismiss() }
        }
    }

    private fun showOcrDebugBlocks(
        blocks: List<Pair<TextBlock, String>>,
        diagnosticId: Long?,
    ) {
        val root = FrameLayout(context)
        val dm = context.resources.displayMetrics
        val physicalSize = physicalDisplaySize(context)
        val screenW = physicalSize.width
        val screenH = physicalSize.height
        val strokePx = (2f * dm.density).toInt().coerceAtLeast(2)
        val paddingPx = (3f * dm.density).toInt().coerceAtLeast(2)
        val red = 0xFFFF2020.toInt()

        blocks.forEachIndexed { index, (block, translation) ->
            val box = block.boundingBox
            val left = (box.left + regionOffset.x).coerceIn(0, screenW)
            val top = (box.top + regionOffset.y).coerceIn(0, screenH)
            val right = (box.right + regionOffset.x).coerceIn(0, screenW)
            val bottom = (box.bottom + regionOffset.y).coerceIn(0, screenH)
            val state = OcrDebugBlockState(
                source = block.text,
                showSource = ocrDebugShowSourceText,
                showTranslation = ocrDebugShowTranslation,
            )
            val label = debugBoxLabel(state, translation)
            val view = TextView(context).apply {
                text = label
                tag = state
                contentDescription = label.ifBlank { context.getString(R.string.ocr_debug_box_only) }
                setTextColor(red)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                maxLines = 8
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                background = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke(strokePx, red)
                }
            }
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    (right - left).coerceAtLeast(1),
                    (bottom - top).coerceAtLeast(1),
                ).apply {
                    leftMargin = left
                    topMargin = top
                },
            )
            blockViews[index] = view
        }
        root.setOnClickListener { clear() }
        val params = newLayoutParams()
        runCatching { wm.addView(root, params) }
            .onFailure { Timber.w(it, "Failed to show OCR debug boxes") }
        blocksView = root
        VerticalDiagnosticLog.i(
            "${diagnosticId.toDiagPrefix()}overlay OCR debug boxes count=${blocks.size} " +
                "source=$ocrDebugShowSourceText translation=$ocrDebugShowTranslation"
        )
    }

    private fun debugBoxLabel(state: OcrDebugBlockState, translation: String): String =
        OcrDebugBoxLabelFormatter.format(
            source = state.source,
            translation = translation,
            showSource = state.showSource,
            showTranslation = state.showTranslation,
            sourceLabel = context.getString(R.string.ocr_debug_source_label),
            translationLabel = context.getString(R.string.ocr_debug_translation_label),
        )

    private fun configureTranslationBlockView(
        index: Int,
        view: View,
        plan: TranslationBlockInteractionPlan,
    ) {
        view.isClickable = true
        val nativeSelectionEnabled = plan.enableNativeTextSelection && view is TextView
        if (nativeSelectionEnabled) {
            (view as TextView).apply {
                setTextIsSelectable(true)
                if (plan.enableSelectedTextSpeech) {
                    enableSelectionSpeech(
                        label = context.getString(R.string.word_card_speak_selection),
                        isEnabled = { translationBlockSelectionSpeechAction != null },
                        onSpeak = { selectedText ->
                            translationBlockSelectionSpeechAction?.onStart?.invoke(selectedText)
                        },
                    )
                }
                isFocusableInTouchMode = true
                setOnTouchListener { touchedView, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            val hadFocus = touchedView.hasFocus()
                            val focusReady = hadFocus || touchedView.requestFocus()
                            VerticalDiagnosticLog.i(
                                "${blocksDiagnosticId.toDiagPrefix()}overlay selection block#${index + 1} down " +
                                    "windowFocus=${touchedView.hasWindowFocus()} hadFocus=$hadFocus " +
                                    "focusReady=$focusReady selectable=$isTextSelectable " +
                                    "movement=${movementMethod?.javaClass?.simpleName ?: "none"}"
                            )
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            VerticalDiagnosticLog.i(
                                "${blocksDiagnosticId.toDiagPrefix()}overlay selection block#${index + 1} " +
                                    "${if (event.actionMasked == MotionEvent.ACTION_UP) "up" else "cancel"} " +
                                    "windowFocus=${touchedView.hasWindowFocus()} focus=${touchedView.hasFocus()} " +
                                    "selection=($selectionStart,$selectionEnd)"
                            )
                        }
                    }
                    false
                }
            }
        } else {
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.isLongClickable = false
            view.setOnLongClickListener(null)
        }
        updateTranslationBlockActionState(index)
        if (plan.openCopyPanelOnBlockTap) {
            view.setOnClickListener {
                val content = blockContents[index] ?: return@setOnClickListener
                if (!isTranslationBlockTextActionable(content.translation)) return@setOnClickListener
                onTranslationBlockDetailRequested(content.source, content.translation)
            }
        } else {
            view.setOnClickListener { clear() }
        }
    }

    private fun updateTranslationBlockActionState(index: Int) {
        val content = blockContents[index] ?: return
        val actionable = isTranslationBlockTextActionable(content.translation)
        val blockView = blockViews[index]
        if (blockView?.isClickable == true) {
            blockView.contentDescription = if (actionable) {
                if (translationBlockInteractionMode == TranslationBlockInteractionMode.OPEN_COPY_PANEL) {
                    context.getString(
                        R.string.translation_block_open_panel_accessibility,
                        content.translation,
                    )
                } else {
                    context.getString(
                        R.string.translation_block_direct_accessibility,
                        content.translation,
                    )
                }
            } else {
                content.translation
            }
        }
    }

    private fun applyFloatingWindowText(index: Int, update: PendingOverlayTextUpdate) {
        val target = floatingContentView ?: return
        val pairs = lastFloatingPairs ?: return
        target.adaptiveTextLayoutPhase = update.phase
        target.text = buildFloatingWindowText(pairs)
        floatingWindow.refreshSelectableTextViews()
    }

    private fun expandAdaptiveVerticalViewport(
        view: VerticalTextView,
        blockIndex: Int,
        sourceRect: OverlayIntRect,
        allRects: List<OverlayIntRect>,
        screenWidth: Int,
        rightToLeft: Boolean,
        minGapPx: Int,
        snapshot: AdaptiveVerticalTextFitSnapshot,
        diagPrefix: String,
    ): Boolean {
        if (snapshot.phase != AdaptiveTextLayoutPhase.FINAL || !snapshot.overflow) return false
        val layout = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        val currentWidth = layout.width.takeIf { it > 0 } ?: view.width.coerceAtLeast(1)
        val target = adaptiveVerticalOverflowSlot(
            rect = sourceRect,
            allRects = allRects,
            screenWidth = screenWidth,
            rightToLeft = rightToLeft,
            minGapPx = minGapPx,
            requiredWidthPx = snapshot.requiredWidthPx,
        )
        if (target.width <= currentWidth) return false

        layout.width = target.width
        if (rightToLeft) {
            layout.gravity = Gravity.TOP or Gravity.RIGHT
            layout.leftMargin = 0
            layout.rightMargin = (screenWidth - target.right).coerceAtLeast(0)
        } else {
            layout.gravity = Gravity.TOP or Gravity.LEFT
            layout.leftMargin = target.left
            layout.rightMargin = 0
        }
        view.layoutParams = layout
        VerticalDiagnosticLog.i(
            "${diagPrefix}adaptive expand block#${blockIndex + 1} axis=V " +
                "fromW=$currentWidth targetSlot=${target.toDiagString()} targetW=${target.width} " +
                "requiredW=${snapshot.requiredWidthPx} constrained=${target.width < snapshot.requiredWidthPx}"
        )
        return true
    }

    private fun expandAdaptiveHorizontalViewport(
        view: StyledTranslationTextView,
        blockIndex: Int,
        topPx: Int,
        screenHeight: Int,
        lowerBoundaryPx: Int,
        gapPx: Int,
        snapshot: AdaptiveHorizontalTextLayoutSnapshot,
        diagPrefix: String,
    ): Boolean {
        if (snapshot.phase != AdaptiveTextLayoutPhase.FINAL || !snapshot.overflow) return false
        val layout = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        val currentHeight = layout.height.takeIf { it > 0 } ?: view.height.coerceAtLeast(1)
        val targetHeight = adaptiveHorizontalOverflowHeight(
            currentHeightPx = currentHeight,
            contentHeightPx = snapshot.contentHeightPx,
            requiredContentHeightPx = snapshot.layoutHeightPx,
            topPx = topPx,
            screenHeightPx = screenHeight,
            lowerBoundaryPx = lowerBoundaryPx,
            gapPx = gapPx,
        )
        if (targetHeight <= currentHeight) return false

        layout.height = targetHeight
        view.layoutParams = layout
        VerticalDiagnosticLog.i(
            "${diagPrefix}adaptive expand block#${blockIndex + 1} axis=H " +
                "fromH=$currentHeight targetH=$targetHeight requiredContentH=${snapshot.layoutHeightPx} " +
                "boundary=$lowerBoundaryPx constrained=${targetHeight < currentHeight +
                    (snapshot.layoutHeightPx - snapshot.contentHeightPx).coerceAtLeast(0)}"
        )
        return true
    }

    private fun logAdaptiveHorizontalTextSize(
        diagPrefix: String,
        blockIndex: Int,
        style: AdaptiveOverlayStyle,
        snapshot: AdaptiveHorizontalTextLayoutSnapshot,
    ) {
        val density = style.textSizeEstimate.scaledDensity.takeIf { it > 0f }
            ?: (context.resources.displayMetrics.density * context.resources.configuration.fontScale)
                .coerceAtLeast(1f)
        val finalSp = snapshot.finalTextSizePx / density
        val autoSizeMaxSp = snapshot.autoSizeMaxTextSizePx
            .takeIf { it > 0 }
            ?.let { it / density }
        val overflowReasons = snapshot.overflowReasons
            .joinToString(separator = ",") { it.name }
            .ifEmpty { "none" }
        val heightFillRatio = if (snapshot.contentHeightPx > 0) {
            snapshot.layoutHeightPx.toFloat() / snapshot.contentHeightPx
        } else {
            0f
        }
        VerticalDiagnosticLog.i(
            "${diagPrefix}adaptive textSize block#${blockIndex + 1} axis=H " +
                "phase=${snapshot.phase.name} " +
                "source=${style.textSizeEstimate.source.name} " +
                "sourceEstimateSp=${String.format(java.util.Locale.US, "%.1f", style.maxTextSizeSp)} " +
                "autoRangeSp=${autoSizeMaxSp?.let {
                    "$ADAPTIVE_MIN_TEXT_SIZE_SP..${String.format(java.util.Locale.US, "%.1f", it)}"
                } ?: "off"} " +
                "finalSp=${String.format(java.util.Locale.US, "%.1f", finalSp)} " +
                "textLength=${snapshot.textLength} measured=${snapshot.measuredWidthPx}x${snapshot.measuredHeightPx} " +
                "viewport=${snapshot.contentWidthPx}x${snapshot.contentHeightPx} " +
                "layoutH=${snapshot.layoutHeightPx} lines=${snapshot.lineCount} " +
                "heightFill=${String.format(java.util.Locale.US, "%.2f", heightFillRatio)} " +
                "visibleEnd=${snapshot.visibleTextEnd} ellipsized=${snapshot.ellipsized} " +
                "overflow=${snapshot.overflow} overflowReasons=$overflowReasons"
        )
    }

    private fun logAdaptiveVerticalTextSize(
        diagPrefix: String,
        blockIndex: Int,
        style: AdaptiveOverlayStyle,
        snapshot: AdaptiveVerticalTextFitSnapshot,
    ) {
        val density = style.textSizeEstimate.scaledDensity.takeIf { it > 0f }
            ?: (context.resources.displayMetrics.density * context.resources.configuration.fontScale)
                .coerceAtLeast(1f)
        val fillRatio = if (snapshot.contentWidthPx > 0) {
            snapshot.requiredWidthPx.toFloat() / snapshot.contentWidthPx
        } else {
            0f
        }
        VerticalDiagnosticLog.i(
            "${diagPrefix}adaptive textSize block#${blockIndex + 1} axis=V " +
                "phase=${snapshot.phase.name} " +
                "source=${style.textSizeEstimate.source.name} " +
                "sourceEstimateSp=${String.format(java.util.Locale.US, "%.1f", style.maxTextSizeSp)} " +
                "rangeSp=${String.format(java.util.Locale.US, "%.1f", snapshot.minTextSizePx / density)}.." +
                "${String.format(java.util.Locale.US, "%.1f", snapshot.maxTextSizePx / density)} " +
                "startSp=${String.format(java.util.Locale.US, "%.1f", snapshot.initialTextSizePx / density)} " +
                "finalSp=${String.format(java.util.Locale.US, "%.1f", snapshot.finalTextSizePx / density)} " +
                "textLength=${snapshot.textLength} probes=${snapshot.probes} " +
                "requiredW=${snapshot.requiredWidthPx} viewport=${snapshot.contentWidthPx}x${snapshot.contentHeightPx} " +
                "widthFill=${String.format(java.util.Locale.US, "%.2f", fillRatio)} " +
                "nextSizeFits=${snapshot.nextSizeFits?.toString() ?: "n/a"} " +
                "overflow=${snapshot.overflow}"
        )
    }

    internal fun updateBlockText(
        index: Int,
        text: String,
        phase: AdaptiveTextLayoutPhase = AdaptiveTextLayoutPhase.FINAL,
    ) {
        blockContents[index]?.translation = text
        val target = blockViews[index]
        if (phase == AdaptiveTextLayoutPhase.STREAMING) {
            blockStreamingUpdateCounts[index] = (blockStreamingUpdateCounts[index] ?: 0) + 1
            target ?: return
            val coalescer = blockFrameUpdateCoalescers.getOrPut(index) {
                LatestFrameUpdateCoalescer(
                    scheduleFrame = { target.postOnAnimation(it) },
                    publish = { update -> applyBlockText(index, update) },
                )
            }
            coalescer.submit(PendingOverlayTextUpdate(text, phase))
            return
        }
        val streamingUpdates = blockStreamingUpdateCounts.remove(index) ?: 0
        val coalescer = blockFrameUpdateCoalescers.remove(index)
        coalescer?.discardPending()
        val stats = coalescer?.stats() ?: FrameUpdateStats(0, 0)
        VerticalDiagnosticLog.i(
            "${blocksDiagnosticId.toDiagPrefix()}overlay updateBlockText block#${index + 1} " +
                "phase=${phase.name} streamingUpdates=$streamingUpdates " +
                "renderedUpdates=${stats.published} coalescedUpdates=${stats.coalesced} " +
                "view=${target?.javaClass?.simpleName ?: "missing"} " +
                text.toDiagText()
        )
        applyBlockText(index, PendingOverlayTextUpdate(text, phase))
    }

    private fun applyBlockText(index: Int, update: PendingOverlayTextUpdate) {
        val text = update.text
        val phase = update.phase
        when (val v = blockViews[index]) {
            is TextView -> {
                val debugState = v.tag as? OcrDebugBlockState
                if (debugState != null) {
                    val label = debugBoxLabel(debugState, text)
                    v.text = label
                    v.contentDescription = label.ifBlank { context.getString(R.string.ocr_debug_box_only) }
                } else {
                    if (v is StyledTranslationTextView) {
                        if (
                            v.adaptiveTextFitEnabled &&
                            v.adaptiveTextLayoutPhase == AdaptiveTextLayoutPhase.PLACEHOLDER &&
                            phase != AdaptiveTextLayoutPhase.PLACEHOLDER
                        ) {
                            v.setAutoSizeTextTypeUniformWithConfiguration(
                                ADAPTIVE_MIN_TEXT_SIZE_SP,
                                ADAPTIVE_MAX_TEXT_SIZE_SP,
                                1,
                                TypedValue.COMPLEX_UNIT_SP,
                            )
                        }
                        v.adaptiveTextLayoutPhase = phase
                    }
                    v.text = if (v is StyledTranslationTextView && v.horizontalRightToLeft) {
                        horizontalRtlDisplayText(text)
                    } else {
                        text
                    }
                }
            }
            is VerticalTextView -> {
                v.adaptiveTextLayoutPhase = phase
                v.text = text
            }
            else -> { /* 找不到 view 静默忽略，避免清屏 race condition 抛 NPE */ }
        }
        updateTranslationBlockActionState(index)
    }

    /** 贴字框必须手动关闭后循环才能继续；常驻悬浮窗口不属于阻塞结果。 */
    fun hasBlockingLoopResult(): Boolean =
        blocksView != null && blockViews.isNotEmpty()

    private fun handleFloatingWindowUserDismiss() {
        performPlaybackOverlayDismiss(
            stopPlayback = onFloatingWindowDismissed,
            clearOverlay = ::clear,
        ).onFailure { error ->
            Timber.w(error, "Failed to stop TTS when floating window was dismissed")
        }
    }

    private fun clearBlocksAndLoading() {
        clearLoading()
        dismissError()
        val dialog = blocksDialog
        blocksDialog = null
        if (dialog != null) {
            runCatching { dialog.dismiss() }
        } else {
            blocksView?.let { runCatching { wm.removeView(it) } }
        }
        blocksView = null
        blockViews.clear()
        blockContents.clear()
        blockStreamingUpdateCounts.clear()
        blockFrameUpdateCoalescers.values.forEach { it.discardPending() }
        blockFrameUpdateCoalescers.clear()
        blocksDiagnosticId = null
    }

    private fun clearFloatingWindow() {
        floatingWindow.hide()
        floatingContentView = null
        floatingStreamingUpdateCounts.clear()
        floatingFrameUpdateCoalescers.values.forEach { it.discardPending() }
        floatingFrameUpdateCoalescers.clear()
        lastFloatingPairs = null
    }

    fun clear() {
        clearBlocksAndLoading()
        clearFloatingWindow()
    }

    /**
     * 构造 overlay 通用 LayoutParams。关键：
     * - LAYOUT_NO_LIMITS + LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS 让坐标系跟物理屏幕 (0,0) 对齐，
     *   避免横屏 cutout / status bar inset 把整体推右导致译文偏右。
     * - NOT_FOCUSABLE / NOT_TOUCH_MODAL 让 overlay 不抢焦点。
     */
    private fun newLayoutParams(focusable: Boolean = false): WindowManager.LayoutParams {
        val physicalSize = physicalDisplaySize(context)
        return WindowManager.LayoutParams(
            physicalSize.width,
            physicalSize.height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            // 历史教训：曾经加过 FLAG_SECURE 阻止 MediaProjection 截到自己的 overlay，理论上
            // SurfaceFlinger 只单独排除该层但物理屏正常。可惜 MIUI / HyperOS 的反盗版逻辑
            // 看到屏幕上有 FLAG_SECURE 层就直接拒绝整张 MediaProjection 输出，Shizuku
            // screencap 也 exit=1。代价远大于自循环防护，已撤回。BLOCKS 模式自循环靠
            // [hasBlockingLoopResult] 阻塞下一帧；悬浮窗口则只在截图瞬间临时停止绘制。
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 把 cutout / system bar 也算进 layout 区域，确保 overlay (0,0) = 物理屏幕 (0,0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            // 设置 fitInsetsTypes = 0，告诉系统这个 window 不要让出任何 system inset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
    }

    private fun String.toDiagText(): String =
        "len=$length text=\"${VerticalDiagnosticLog.text(this)}\""

    private fun Rect.toDiagString(): String =
        "($left,$top,$right,$bottom)"

    private fun OverlayIntRect.toDiagString(): String =
        "($left,$top,$right,$bottom)"

    private fun VerticalOverlaySlot.toDiagString(): String =
        "($left,$right)"

    private fun Long?.toDiagPrefix(): String = this?.let { "capture#$it " } ?: ""

    private fun themeFgColor(): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFFFFFFFF.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFFFD27F.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFF3E2A1F.toInt()
        OverlayTheme.FROST_GLASS -> 0xFFE0F2FE.toInt()
        OverlayTheme.CUSTOM -> customFg
    }

    private fun themeFgMutedColor(): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFFB0BEC5.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFB68850.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFF8B6F47.toInt()
        OverlayTheme.FROST_GLASS -> 0xFF94A3B8.toInt()
        OverlayTheme.CUSTOM -> (customFg and 0xFFFFFF) or 0x99000000.toInt()
    }

    private fun themeBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 8f
        setColor(when (theme) {
            OverlayTheme.CLASSIC_DARK -> 0xE6000000.toInt()
            OverlayTheme.AMBER_GOLD -> 0xF0241608.toInt()
            OverlayTheme.PAPER_LIGHT -> 0xF0F5EFE0.toInt()
            OverlayTheme.FROST_GLASS -> 0xCC1E293B.toInt()
            OverlayTheme.CUSTOM -> customBg
        })
        val density = context.resources.displayMetrics.density
        when (theme) {
            OverlayTheme.AMBER_GOLD -> setStroke(2, 0xFFB8860B.toInt())
            OverlayTheme.PAPER_LIGHT -> setStroke(1, 0xFFB68850.toInt())
            OverlayTheme.FROST_GLASS -> setStroke(1, 0xFF60A5FA.toInt())
            OverlayTheme.CUSTOM -> if (customBorderWidthDp > 0) {
                val px = (customBorderWidthDp * density).toInt()
                // BLOCKS 模式的 box 用 GradientDrawable，只支持 SOLID/DASHED/DOTTED；
                // DOUBLE/GROOVE 需要 LayerDrawable，在小 box 上视觉混乱，统一回退到 SOLID。
                // 完整 5 种样式仅在 FLOATING_WINDOW 模式（DraggableOverlayWindow.shellBackground）生效。
                when (customBorderStyle) {
                    BorderStyle.DASHED -> setStroke(px, customBorder, 8f * density, 5f * density)
                    BorderStyle.DOTTED -> setStroke(px, customBorder, 2f * density, 3f * density)
                    else -> setStroke(px, customBorder)
                }
            }
            else -> { /* CLASSIC_DARK: 无边 */ }
        }
    }

    private fun adaptiveBg(style: AdaptiveOverlayStyle, block: TextBlock): Drawable {
        val bounds = block.boundingBox
        val eraseRects = adaptiveEraseRects(
            block = OverlayIntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            sourceBoxes = block.sourceBoxes.map { source ->
                OverlayIntRect(source.left, source.top, source.right, source.bottom)
            },
        )
        return AdaptiveEraseDrawable(style.backgroundColor, eraseRects)
    }
}

internal fun cutoutModeLogLabel(mode: Int?): String = mode?.toString() ?: "unsupported"
