package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gameocr.app.R
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.Language
import com.gameocr.app.data.Languages
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.Settings

internal enum class LanguageSlot {
    SOURCE,
    TARGET
}

internal object LanguageQuickSwitchOptions {
    private val COMMON_CODES = listOf(
        Languages.AUTO.code,
        "ja",
        "en",
        "ko",
        Languages.ZH_CN.code,
        Languages.ZH_TW.code
    )

    fun ordered(
        slot: LanguageSlot,
        pinned: List<String>,
        currentSource: String,
        currentTarget: String,
        allLanguages: List<Language> = Languages.ALL
    ): List<Language> {
        val allowed = allLanguages.filter { slot == LanguageSlot.SOURCE || it.code != Languages.AUTO.code }
        val byCode = allowed.associateBy { it.code.lowercase() }
        val out = mutableListOf<Language>()
        val seen = mutableSetOf<String>()

        fun addCode(code: String) {
            val key = code.trim().lowercase()
            if (key.isEmpty() || !seen.add(key)) return
            byCode[key]?.let(out::add)
        }

        addCode(if (slot == LanguageSlot.SOURCE) currentSource else currentTarget)
        pinned.forEach(::addCode)
        COMMON_CODES.forEach(::addCode)
        allowed.forEach { addCode(it.code) }
        return out
    }

    fun filtered(
        context: Context,
        slot: LanguageSlot,
        query: String,
        pinned: List<String>,
        currentSource: String,
        currentTarget: String
    ): List<Language> {
        val ordered = ordered(slot, pinned, currentSource, currentTarget)
        val q = query.trim()
        if (q.isEmpty()) return ordered
        val lower = q.lowercase()
        return ordered.filter { lang ->
            context.getString(lang.nameRes).contains(q, ignoreCase = true) ||
                lang.code.lowercase().contains(lower)
        }
    }
}

class LanguageQuickSwitchOverlay(private val context: Context) {

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var rootView: View? = null

    fun isShown(): Boolean = rootView != null

    fun dismiss() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
    }

    fun show(
        settings: Settings,
        onPairChanged: (source: String, target: String) -> Unit
    ) {
        dismiss()

        val density = context.resources.displayMetrics.density
        val theme = settings.overlayTheme
        val bgColor = themeBgColor(theme, settings)
        val fgColor = themeFgColor(theme, settings)
        val mutedColor = themeFgMutedColor(theme, settings)
        val accentColor = themeAccentColor(theme, settings)

        var selectedSlot = LanguageSlot.SOURCE
        var sourceCode = settings.sourceLang.ifBlank { Languages.AUTO.code }
        var targetCode = settings.targetLang.ifBlank { Languages.ZH_CN.code }

        lateinit var pairTitle: TextView
        lateinit var sourceChip: TextView
        lateinit var targetChip: TextView
        lateinit var searchBox: EditText
        lateinit var list: LinearLayout

        fun slotText(labelRes: Int, code: String): String =
            context.getString(labelRes) + "\n" + Languages.nameOf(context, code)

        fun refreshHeader() {
            pairTitle.text = "${Languages.nameOf(context, sourceCode)} \u2192 ${Languages.nameOf(context, targetCode)}"
            sourceChip.text = slotText(R.string.settings_source_lang, sourceCode)
            targetChip.text = slotText(R.string.settings_target_lang, targetCode)
            sourceChip.background = slotBackground(selectedSlot == LanguageSlot.SOURCE, accentColor, bgColor, density)
            targetChip.background = slotBackground(selectedSlot == LanguageSlot.TARGET, accentColor, bgColor, density)
        }

        fun refreshList() {
            list.removeAllViews()
            val items = LanguageQuickSwitchOptions.filtered(
                context = context,
                slot = selectedSlot,
                query = searchBox.text?.toString().orEmpty(),
                pinned = settings.pinnedLanguages,
                currentSource = sourceCode,
                currentTarget = targetCode
            )
            if (items.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = context.getString(R.string.lang_picker_no_match)
                    setTextColor(mutedColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
                return
            }
            items.forEach { lang ->
                val selected = when (selectedSlot) {
                    LanguageSlot.SOURCE -> lang.code.equals(sourceCode, ignoreCase = true)
                    LanguageSlot.TARGET -> lang.code.equals(targetCode, ignoreCase = true)
                }
                list.addView(buildLanguageRow(
                    lang = lang,
                    selected = selected,
                    fgColor = fgColor,
                    mutedColor = mutedColor,
                    accentColor = accentColor,
                    density = density
                ) {
                    if (selectedSlot == LanguageSlot.SOURCE) {
                        sourceCode = lang.code
                        selectedSlot = LanguageSlot.TARGET
                    } else {
                        targetCode = lang.code
                    }
                    onPairChanged(sourceCode, targetCode)
                    refreshHeader()
                    refreshList()
                })
            }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.language_quick_title)
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val closeBtn = TextView(context).apply {
            text = "\u00d7"
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)
            contentDescription = context.getString(R.string.word_card_dismiss)
            setOnClickListener { dismiss() }
        }
        topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(closeBtn, LinearLayout.LayoutParams(dp(40), dp(36)))
        card.addView(topRow)

        pairTitle = TextView(context).apply {
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(pairTitle)

        val slotRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceChip = buildSlotChip(fgColor) {
            selectedSlot = LanguageSlot.SOURCE
            refreshHeader()
            refreshList()
        }
        targetChip = buildSlotChip(fgColor) {
            selectedSlot = LanguageSlot.TARGET
            refreshHeader()
            refreshList()
        }
        val arrow = TextView(context).apply {
            text = "\u2192"
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        slotRow.addView(sourceChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        slotRow.addView(arrow, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))
        slotRow.addView(targetChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(slotRow)

        searchBox = EditText(context).apply {
            hint = context.getString(R.string.lang_picker_search_placeholder)
            setSingleLine(true)
            setTextColor(fgColor)
            setHintTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedBackground((bgColor and 0x00FFFFFF) or 0x33000000, 10f * density)
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        card.addView(
            searchBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(12) }
        )

        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.48f).toInt()
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            isFillViewport = false
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        val shell = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val parentW = View.MeasureSpec.getSize(widthMeasureSpec)
                val maxW = (parentW * 0.88f).toInt()
                super.onMeasure(
                    View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY),
                    heightMeasureSpec
                )
            }
        }.apply {
            background = shellBackground(theme, settings, density)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { }
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val backdrop = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        dismiss()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x55000000.toInt())
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
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

        refreshHeader()
        refreshList()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(backdrop, params) }
        rootView = backdrop
        backdrop.requestFocus()
    }

    private fun buildSlotChip(textColor: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun buildLanguageRow(
        lang: Language,
        selected: Boolean,
        fgColor: Int,
        mutedColor: Int,
        accentColor: Int,
        density: Float,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBackground(
                if (selected) withAlpha(accentColor, 0x24) else Color.TRANSPARENT,
                8f * density
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(context).apply {
            text = if (selected) "\u2713" else ""
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(context).apply {
            text = context.getString(lang.nameRes)
            setTextColor(if (selected) accentColor else fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = lang.code
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, 0, 0)
        })
        return row
    }

    private fun slotBackground(selected: Boolean, accentColor: Int, bgColor: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(if (selected) withAlpha(accentColor, 0x24) else withAlpha(bgColor, 0x22))
            setStroke((1f * density).toInt().coerceAtLeast(1), if (selected) accentColor else withAlpha(accentColor, 0x66))
        }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics
        ).toInt()

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

    private fun themeFgMutedColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xCCB0BEC5.toInt()
        OverlayTheme.AMBER_GOLD -> 0xCCB68850.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xCC8B6F47.toInt()
        OverlayTheme.FROST_GLASS -> 0xCC94A3B8.toInt()
        OverlayTheme.CUSTOM -> (s.customFgColor and 0x00FFFFFF) or 0xB0000000.toInt()
    }

    private fun themeAccentColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFF90CAF9.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFB8860B.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFFB68850.toInt()
        OverlayTheme.FROST_GLASS -> 0xFF60A5FA.toInt()
        OverlayTheme.CUSTOM -> if (s.customBorderColor != 0) s.customBorderColor else s.customFgColor
    }

    private fun themeStroke(theme: OverlayTheme, s: Settings): Pair<Int, Int> = when (theme) {
        OverlayTheme.AMBER_GOLD -> 2 to 0xFFB8860B.toInt()
        OverlayTheme.PAPER_LIGHT -> 1 to 0xFFB68850.toInt()
        OverlayTheme.FROST_GLASS -> 1 to 0xFF60A5FA.toInt()
        OverlayTheme.CUSTOM -> if (s.customBorderWidth > 0) s.customBorderWidth to s.customBorderColor else 0 to 0
        else -> 0 to 0
    }

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
