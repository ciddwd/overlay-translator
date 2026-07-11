package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gameocr.app.R
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.OverlayFontPolicy
import com.gameocr.app.data.Settings
import com.gameocr.app.translate.WordResult

private data class TranslationCardWindowArea(
    val widthPx: Int,
    val heightPx: Int,
    val safeInsets: TranslationCardSafeInsets,
)

/**
 * 划词翻译结果浮卡。屏幕中下方居中弹出，**不**自动消失（卡片可能含多行释义，用户需要时间阅读）。
 *
 * 关闭方式：
 *  - 点右上 ✕
 *  - 点卡片外的灰背景
 *
 * 渲染分区：
 *  - 标题：原文（小字、单行）
 *  - 主区：译文（大字、可折行）
 *  - 字典区（仅 [show] 传入的 wordResult 非空时）：音标 / 词性 / 释义 / 难点解释 / 例句
 *  - 动作行：复制原文 / 复制译文 按钮 + 点击短暂反馈
 *
 * 卡片背景 / 文字 / 边框 / 边框样式跟随 [Settings.overlayTheme]——和 [DraggableOverlayWindow]
 * 一致，五种内置主题 + CUSTOM。**主题取色函数与 DraggableOverlayWindow 内的实现保持同步**，
 * 未来改主题色需要两边一起改。
 */
class TranslationCardOverlay(private val context: Context) {

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var rootView: View? = null

    fun isShown(): Boolean = rootView != null

    /** 立即关闭已显示的卡片。 */
    fun dismiss() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
    }

    /**
     * 显示卡片。重复调用先 dismiss 旧的再弹新的。
     *
     * @param sourceText 原文（必填）
     * @param translation 纯译文。若 wordResult 非空，这里可以传 wordResult.definitions.firstOrNull()
     *                    或单独的兜底译文；为空时不显示主区。
     * @param wordResult 词典化结果，null = 不显示字典区（句子翻译走这条）
     * @param settings 当前用户设置，用来取主题色 / 边框样式 — 与悬浮窗口保持视觉一致。
     */
    fun show(
        sourceText: String,
        translation: String?,
        wordResult: WordResult?,
        settings: Settings
    ) {
        dismiss()
        val density = context.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (12 * density).toInt()
        val windowArea = currentWindowArea()
        var layoutSpec = translationCardLayoutSpec(
            screenWidthPx = windowArea.widthPx,
            screenHeightPx = windowArea.heightPx,
            safeInsets = windowArea.safeInsets,
        )

        // 主题色一次取齐，避免多次 when 分支不同步
        val theme = settings.overlayTheme
        val bgColor = themeBgColor(theme, settings)
        val fgColor = themeFgColor(theme, settings)
        val mutedColor = themeFgMutedColor(theme, settings)
        val accentColor = themeAccentColor(theme, settings)

        // 内层 card 只承担"内容容器"角色，不带背景 / padding——
        // 背景 + 边框 + padding 在外壳 shell 上，让滚动时只有文字动、外壳保持不动。
        val card = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = layoutSpec.cardHeightPx(shellVerticalPaddingPx = padV * 2)
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.EXACTLY),
                )
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶部行：原文（weight=1）+ 「✕」关闭键
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val srcLabel = TextView(context).apply {
            text = sourceText
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnLongClickListener {
                copyToClipboard(sourceText)
                true
            }
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val pad = (6 * density).toInt()
            setPadding(pad, 0, pad, 0)
            contentDescription = context.getString(R.string.word_card_dismiss)
            setOnClickListener { dismiss() }
        }
        topRow.addView(
            srcLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        topRow.addView(closeBtn)
        card.addView(topRow)

        // 主区：译文（大字）
        if (!translation.isNullOrBlank()) {
            val translationTv = StyledTranslationTextView(context).apply {
                text = translation
                setTextColor(fgColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                applyOverlayTextStyle(settings.overlayTextStyle, settingsTypeface(settings))
                val mt = (10 * density).toInt()
                val mb = if (wordResult == null) 0 else (8 * density).toInt()
                setPadding(0, mt, 0, mb)
                setLineSpacing(2f, 1.1f)
                setOnLongClickListener {
                    copyToClipboard(translation)
                    true
                }
            }
            scrollContent.addView(translationTv)
        }

        // 字典区
        if (wordResult != null && !wordResult.isEmpty()) {
            scrollContent.addView(buildDivider(density, mutedColor))
            if (wordResult.phonetic.isNotBlank()) {
                scrollContent.addView(buildLabeledLine(
                    context.getString(R.string.word_card_label_phonetic),
                    wordResult.phonetic,
                    density,
                    labelColor = accentColor,
                    valueColor = fgColor,
                    monospace = true
                ))
            }
            if (wordResult.pos.isNotEmpty()) {
                scrollContent.addView(buildLabeledLine(
                    context.getString(R.string.word_card_label_pos),
                    wordResult.pos.joinToString(" / "),
                    density,
                    labelColor = accentColor,
                    valueColor = fgColor
                ))
            }
            if (wordResult.definitions.isNotEmpty()) {
                scrollContent.addView(buildLabel(
                    context.getString(R.string.word_card_label_definitions),
                    density,
                    accentColor
                ))
                wordResult.definitions.forEachIndexed { idx, d ->
                    scrollContent.addView(TextView(context).apply {
                        text = "${idx + 1}. $d"
                        setTextColor(fgColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        val pad = (2 * density).toInt()
                        setPadding(0, pad, 0, pad)
                    })
                }
            }
            if (wordResult.difficultyNotes.isNotEmpty()) {
                scrollContent.addView(buildLabel(
                    context.getString(R.string.word_card_label_difficulty_notes),
                    density,
                    accentColor
                ))
                wordResult.difficultyNotes.forEach { note ->
                    scrollContent.addView(TextView(context).apply {
                        text = "・$note"
                        setTextColor(fgColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        val pad = (2 * density).toInt()
                        setPadding(0, pad, 0, pad)
                    })
                }
            }
            if (wordResult.examples.isNotEmpty()) {
                scrollContent.addView(buildLabel(
                    context.getString(R.string.word_card_label_examples),
                    density,
                    accentColor
                ))
                wordResult.examples.forEach { ex ->
                    val srcTv = TextView(context).apply {
                        text = "・${ex.src}"
                        setTextColor(mutedColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    }
                    val dstTv = TextView(context).apply {
                        text = "  ${ex.dst}"
                        setTextColor(fgColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        val mb = (6 * density).toInt()
                        setPadding(0, 0, 0, mb)
                    }
                    scrollContent.addView(srcTv)
                    scrollContent.addView(dstTv)
                }
            }
        }

        // 动作行：复制原文 / 复制译文（仅在对应文本非空时）。
        // 显式按钮主要解决「用户不知道能复制」；长按 srcLabel/translationTv 的快捷方式保留。
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                scrollContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val mt = (10 * density).toInt()
            setPadding(0, mt, 0, 0)
        }
        val copySrcLabel = context.getString(R.string.word_card_btn_copy_source)
        val copySrcBtn = buildPillButton(copySrcLabel, accentColor, density)
        copySrcBtn.setOnClickListener {
            copyToClipboard(sourceText)
            flashCopied(copySrcBtn, copySrcLabel)
        }
        actionRow.addView(copySrcBtn)
        if (!translation.isNullOrBlank()) {
            actionRow.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
            })
            val copyDstLabel = context.getString(R.string.word_card_btn_copy_translation)
            val copyDstBtn = buildPillButton(copyDstLabel, accentColor, density)
            copyDstBtn.setOnClickListener {
                copyToClipboard(translation)
                flashCopied(copyDstBtn, copyDstLabel)
            }
            actionRow.addView(copyDstBtn)
        }
        card.addView(actionRow)

        // 内层 ScrollView：滚动 viewport。重写 onMeasure 把高度限制为屏幕高 * 0.6 —
        // 旋转后 displayMetrics 跟随系统 configuration 自动更新，省掉 layout listener。
        // 外壳：背景 + 边框 + padding 全在这层，滚动时保持不动。
        // 重写 onMeasure 把宽度锁成"父 (backdrop = 屏幕) 宽 * 0.88"，旋转后 backdrop 自动重算
        // → shell 的 measureSpec 也会带新 parentW 进来，竖屏不会塞横屏宽。
        val shell = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxW = layoutSpec.shellWidthPx()
                super.onMeasure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        maxW,
                        android.view.View.MeasureSpec.EXACTLY
                    ),
                    heightMeasureSpec
                )
            }
        }.apply {
            background = shellBackground(theme, settings, density)
            setPadding(padH, padV, padH, padV)
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 外层全屏背景（半透明黑），吸收点击关闭
        val backdrop = FrameLayout(context).apply {
            setBackgroundColor(0x55000000.toInt())
            setPadding(
                windowArea.safeInsets.left,
                windowArea.safeInsets.top,
                windowArea.safeInsets.right,
                windowArea.safeInsets.bottom,
            )
            isClickable = true
            setOnClickListener { dismiss() }
            addView(
                shell,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(backdrop) { view, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val latestArea = currentWindowArea()
            val latestInsets = TranslationCardSafeInsets(
                left = safe.left,
                top = safe.top,
                right = safe.right,
                bottom = safe.bottom,
            )
            layoutSpec = translationCardLayoutSpec(
                screenWidthPx = latestArea.widthPx,
                screenHeightPx = latestArea.heightPx,
                safeInsets = latestInsets,
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            shell.requestLayout()
            card.requestLayout()
            insets
        }
        // 拦截卡片内点击不冒泡到 backdrop（否则点字典区也会关闭）
        shell.setOnClickListener { /* swallow */ }
        shell.isClickable = true

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // 卡片要可点击关闭 + 长按复制 → 必须 focusable / touch modal
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(backdrop, params) }
        ViewCompat.requestApplyInsets(backdrop)
        rootView = backdrop
    }

    private fun currentWindowArea(): TranslationCardWindowArea {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = wm.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                return TranslationCardWindowArea(
                    widthPx = metrics.bounds.width(),
                    heightPx = metrics.bounds.height(),
                    safeInsets = TranslationCardSafeInsets(
                        left = insets.left,
                        top = insets.top,
                        right = insets.right,
                        bottom = insets.bottom,
                    ),
                )
            }
        }
        val displayMetrics = context.resources.displayMetrics
        return TranslationCardWindowArea(
            widthPx = displayMetrics.widthPixels,
            heightPx = displayMetrics.heightPixels,
            safeInsets = TranslationCardSafeInsets(),
        )
    }

    private fun buildLabel(text: String, density: Float, color: Int): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val mt = (8 * density).toInt()
        setPadding(0, mt, 0, 0)
    }

    private fun buildLabeledLine(
        label: String,
        value: String,
        density: Float,
        labelColor: Int,
        valueColor: Int,
        monospace: Boolean = false
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val mt = (4 * density).toInt()
            setPadding(0, mt, 0, 0)
        }
        val labelTv = TextView(context).apply {
            text = "$label  "
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val valueTv = TextView(context).apply {
            text = value
            setTextColor(valueColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (monospace) {
                typeface = android.graphics.Typeface.MONOSPACE
            }
        }
        row.addView(labelTv)
        row.addView(valueTv)
        return row
    }

    private fun buildDivider(density: Float, color: Int): View = View(context).apply {
        // divider 用 muted 的更淡变体——alpha 减半 → 与卡片背景柔和过渡
        val a = ((color shr 24) and 0xFF) / 2
        val dim = (color and 0x00FFFFFF) or (a shl 24)
        setBackgroundColor(dim)
        val m = (10 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * density).toInt()
        ).apply { topMargin = m; bottomMargin = m / 2 }
    }

    /** 复制按钮：圆角描边「胶囊」，主题强调色描边 + 文字。 */
    private fun buildPillButton(text: String, accentColor: Int, density: Float): TextView = TextView(context).apply {
        this.text = text
        setTextColor(accentColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val padH = (12 * density).toInt()
        val padV = (5 * density).toInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = 14f * density
            // accent 描边 + 同色 alpha 0x22 底（淡淡填充让按钮在浅色主题下也看得见）
            setColor((accentColor and 0x00FFFFFF) or 0x22000000)
            setStroke((1f * density).toInt(), accentColor)
        }
        isClickable = true
    }

    /** 点了「复制」后短暂把按钮文字换成「已复制 ✓」，1.2s 后恢复。 */
    private fun flashCopied(btn: TextView, originalText: String) {
        btn.text = context.getString(R.string.word_card_btn_copied)
        btn.postDelayed({
            if (btn.isAttachedToWindow) btn.text = originalText
        }, 1200)
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("translation", text))
    }

    private fun settingsTypeface(settings: Settings): android.graphics.Typeface? =
        runCatching {
            OverlayFontPolicy.normalizeStoredFileName(settings.overlayFontFileName)?.let { fileName ->
                val directory = java.io.File(context.filesDir, OverlayFontPolicy.FONT_DIR_NAME)
                android.graphics.Typeface.Builder(java.io.File(directory, fileName)).build()
            }
        }.getOrNull()

    // —— 主题色：与 DraggableOverlayWindow 同步。把两边维护成一致以保持视觉统一。——

    private fun themeBgColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xE6000000.toInt()
        OverlayTheme.AMBER_GOLD -> 0xF0241608.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xF0F5EFE0.toInt()
        OverlayTheme.FROST_GLASS -> 0xCC1E293B.toInt()
        OverlayTheme.CUSTOM -> s.customBgColor
    }

    private fun themeFgColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFFFFFFFF.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFFFD27F.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFF3E2A1F.toInt()
        OverlayTheme.FROST_GLASS -> 0xFFE0F2FE.toInt()
        OverlayTheme.CUSTOM -> s.customFgColor
    }

    /** 次要文字色 (原文标题 / 例句原文 / 关闭键)。CUSTOM 时用 fg 半透明替代。 */
    private fun themeFgMutedColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xCCB0BEC5.toInt()
        OverlayTheme.AMBER_GOLD -> 0xCCB68850.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xCC8B6F47.toInt()
        OverlayTheme.FROST_GLASS -> 0xCC94A3B8.toInt()
        OverlayTheme.CUSTOM -> (s.customFgColor and 0x00FFFFFF) or 0xB0000000.toInt()
    }

    /** label / 复制按钮色——主题强调色。默认主题用 stroke 颜色；CLASSIC_DARK / CUSTOM 用 fg 派生。 */
    private fun themeAccentColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFF90CAF9.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFB8860B.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFFB68850.toInt()
        OverlayTheme.FROST_GLASS -> 0xFF60A5FA.toInt()
        OverlayTheme.CUSTOM -> if (s.customBorderColor != 0) s.customBorderColor else s.customFgColor
    }

    /** 主题边框：(widthDp, color)。widthDp = 0 表示无边。 */
    private fun themeStroke(theme: OverlayTheme, s: Settings): Pair<Int, Int> = when (theme) {
        OverlayTheme.AMBER_GOLD -> 2 to 0xFFB8860B.toInt()
        OverlayTheme.PAPER_LIGHT -> 1 to 0xFFB68850.toInt()
        OverlayTheme.FROST_GLASS -> 1 to 0xFF60A5FA.toInt()
        OverlayTheme.CUSTOM -> if (s.customBorderWidth > 0) s.customBorderWidth to s.customBorderColor else 0 to 0
        else -> 0 to 0
    }

    /** factor > 0 变亮，< 0 变暗。保持 alpha 不变。GROOVE 边框模拟凹槽用。 */
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

    /**
     * 卡片外壳背景。按 [Settings.customBorderStyle] 切不同 Drawable：
     * - SOLID/DASHED/DOTTED：单 GradientDrawable，setStroke 4-arg 自带 dash 支持
     * - DOUBLE：LayerDrawable 外层 stroke + 内层 inset stroke（间隙模拟双边）
     * - GROOVE：LayerDrawable，外层暗色 + 内层亮色 stroke，模拟凹槽
     * 主题本身无 stroke（如 CLASSIC_DARK）时 borderStyle 不生效；预设主题（AMBER/PAPER/FROST）
     * 也不读 borderStyle——预设的边框是调好的实线，用户调 borderStyle 只该影响 CUSTOM 主题。
     */
    private fun shellBackground(theme: OverlayTheme, s: Settings, density: Float): android.graphics.drawable.Drawable {
        val bgColor = themeBgColor(theme, s)
        val (strokeWidthDp, strokeColor) = themeStroke(theme, s)

        if (strokeWidthDp <= 0) {
            return GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(bgColor)
            }
        }
        val px = (strokeWidthDp * density).toInt().coerceAtLeast(1)

        if (theme != OverlayTheme.CUSTOM) {
            return GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(bgColor)
                setStroke(px, strokeColor)
            }
        }

        return when (s.customBorderStyle) {
            BorderStyle.SOLID -> GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(bgColor)
                setStroke(px, strokeColor)
            }
            BorderStyle.DASHED -> GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(bgColor)
                setStroke(px, strokeColor, 8f * density, 5f * density)
            }
            BorderStyle.DOTTED -> GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(bgColor)
                setStroke(px, strokeColor, 2f * density, 3f * density)
            }
            BorderStyle.DOUBLE -> {
                val gap = px + (3 * density).toInt()
                val outer = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(bgColor)
                    setStroke(px, strokeColor)
                }
                val inner = GradientDrawable().apply {
                    cornerRadius = (16f * density - gap).coerceAtLeast(8f * density)
                    setColor(Color.TRANSPARENT)
                    setStroke(px, strokeColor)
                }
                LayerDrawable(arrayOf(outer, inner)).apply {
                    setLayerInset(1, gap, gap, gap, gap)
                }
            }
            BorderStyle.GROOVE -> {
                val outer = GradientDrawable().apply {
                    cornerRadius = 16f * density
                    setColor(bgColor)
                    setStroke(px, shadeColor(strokeColor, -0.4f))
                }
                val inner = GradientDrawable().apply {
                    cornerRadius = (16f * density - px).coerceAtLeast(8f * density)
                    setColor(Color.TRANSPARENT)
                    setStroke(px, shadeColor(strokeColor, 0.4f))
                }
                LayerDrawable(arrayOf(outer, inner)).apply {
                    setLayerInset(1, px, px, px, px)
                }
            }
        }
    }
}
