package com.gameocr.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gameocr.app.R

/** Existing menu actions in stacked or side-by-side groups on an opaque surface. */
internal object FloatingQuickPanelView {
    val panelColor = 0xFF0F172A.toInt()
    val actionColor = 0xFF1E293B.toInt()
    val selectedColor = 0xFFF8FAFC.toInt()
    private val textColor = 0xFFF1F5F9.toInt()
    private val secondaryColor = 0xFFCBD5E1.toInt()

    fun create(
        context: Context,
        modeItems: List<MenuItem>,
        selectedMode: Int,
        actionItems: List<MenuItem>,
        home: MenuItem,
        languagePair: Pair<String, String>,
        sideBySide: Boolean,
    ): ScrollView {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun surface(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        fun label(value: String, color: Int = textColor, bold: Boolean = false) = TextView(context).apply {
            text = value
            textSize = 14f
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fun icon(item: MenuItem, color: Int = textColor) = ImageView(context).apply {
            setImageResource(item.iconRes)
            imageTintList = ColorStateList.valueOf(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun LinearLayout.bind(item: MenuItem, color: Int) {
            background = RippleDrawable(ColorStateList.valueOf(0x33CBD5E1), surface(color, 14), null)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(item.labelRes)
            setOnClickListener { item.onTap() }
        }
        val modes = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            modeItems.forEachIndexed { index, item ->
                val selected = index == selectedMode
                val foreground = if (selected) panelColor else textColor
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    bind(item, if (selected) selectedColor else panelColor)
                    isSelected = selected
                    addView(icon(item, foreground), LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        marginEnd = dp(12)
                    })
                    addView(label(context.getString(item.labelRes), foreground, selected),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.RadioButton"
                            info.isCheckable = true
                            info.isChecked = selected
                        }
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(4) })
            }
        }
        val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        actionItems.firstOrNull { it.labelRes == R.string.menu_language_pair }?.let { item ->
            actions.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                bind(item, actionColor)
                contentDescription = "${context.getString(item.labelRes)}, ${languagePair.first} → ${languagePair.second}"
                addView(icon(item), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    listOf(languagePair.first, "→ ${languagePair.second}").forEachIndexed { index, text ->
                        addView(label(text, if (index == 0) textColor else secondaryColor).apply { maxLines = 1 },
                            LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(2) })
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        // Reserve two label lines so larger accessibility fonts do not clip the tiles.
        val metrics = label("").paint.fontMetricsInt
        val actionHeight = maxOf(dp(72), 2 * (metrics.bottom - metrics.top) + dp(44))
        val shortcuts = actionItems.filter { it.labelRes != R.string.menu_language_pair } + home
        shortcuts.chunked(2).forEach { items ->
            actions.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                items.forEachIndexed { index, item ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        bind(item, actionColor)
                        addView(icon(item), LinearLayout.LayoutParams(dp(22), dp(22)).apply { bottomMargin = dp(6) })
                        addView(label(context.getString(item.labelRes)).apply { gravity = Gravity.CENTER },
                            LinearLayout.LayoutParams(-1, -2))
                    }, LinearLayout.LayoutParams(0, actionHeight, 1f).apply { if (index > 0) marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val content = LinearLayout(context).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isBaselineAligned = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
            if (sideBySide) {
                addView(modes, LinearLayout.LayoutParams(0, -2, 0.46f))
                addView(View(context).apply { setBackgroundColor(actionColor) },
                    LinearLayout.LayoutParams(dp(1), -1).apply {
                        marginStart = dp(12)
                        marginEnd = dp(12)
                    })
                addView(actions, LinearLayout.LayoutParams(0, -2, 0.54f))
            } else {
                addView(modes, LinearLayout.LayoutParams(-1, -2))
                addView(View(context).apply { setBackgroundColor(actionColor) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(12)
                    })
                addView(actions, LinearLayout.LayoutParams(-1, -2))
            }
        }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isClickable = true
            background = surface(panelColor, 24)
            clipToOutline = true
            elevation = dp(12).toFloat()
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
    }
}
