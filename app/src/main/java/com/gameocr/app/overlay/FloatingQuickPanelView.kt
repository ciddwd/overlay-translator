package com.gameocr.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.gameocr.app.R
import com.gameocr.app.data.ThemeModePrefs
import com.gameocr.app.ui.theme.ThemeMode
import com.gameocr.app.ui.theme.SlateDark
import com.gameocr.app.ui.theme.SlateLight

/** Six labeled modes using the app's surfaces and existing menu callbacks. */
internal object FloatingQuickPanelView {
    val panelColor = SlateLight.surface.toArgb()
    val actionColor = SlateLight.surfaceVariant.toArgb()
    val selectedColor = 0xFF403F46.toInt()

    fun create(
        context: Context,
        modeItems: List<MenuItem>,
        selectedMode: Int,
        actionItems: List<MenuItem>,
        home: MenuItem,
        languagePair: Pair<String, String>,
        sideBySide: Boolean,
    ): ScrollView {
        val dark = when (ThemeModePrefs.read(context)) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val colors = if (dark) SlateDark else SlateLight
        val panelColor = colors.surface.toArgb()
        val actionColor = colors.surfaceVariant.toArgb()
        val selectedColor = if (dark) colors.primary.toArgb() else this.selectedColor
        val textColor = colors.onSurface.toArgb()
        val secondaryColor = colors.onSurfaceVariant.toArgb()
        val selectedForeground = if (dark) this.selectedColor else colors.onPrimary.toArgb()
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun surface(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        fun label(value: String, color: Int = textColor, bold: Boolean = false) = TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun icon(item: MenuItem, color: Int = secondaryColor) = ImageView(context).apply {
            setImageResource(item.iconRes)
            imageTintList = ColorStateList.valueOf(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun LinearLayout.bind(item: MenuItem, color: Int) {
            background = RippleDrawable(ColorStateList.valueOf((textColor and 0x00FFFFFF) or 0x1F000000), surface(color, 12), null)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(item.labelRes)
            setOnClickListener { item.onTap() }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        modeItems.withIndex().chunked(3).forEachIndexed { rowIndex, items ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                items.forEachIndexed { column, (index, item) ->
                    val selected = index == selectedMode
                    val foreground = if (selected) selectedForeground else textColor
                    addView(LinearLayout(context).apply {
                        orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        minimumHeight = dp(if (sideBySide) 48 else 78)
                        setPadding(dp(6), dp(9), dp(6), dp(9))
                        bind(item, if (selected) selectedColor else actionColor)
                        isSelected = selected
                        addView(icon(item, if (selected) selectedForeground else secondaryColor),
                            LinearLayout.LayoutParams(dp(23), dp(23)).apply {
                                if (sideBySide) marginEnd = dp(8) else bottomMargin = dp(6)
                            })
                        addView(label(context.getString(item.labelRes), foreground, selected).apply {
                            gravity = if (sideBySide) Gravity.START else Gravity.CENTER
                        }, if (sideBySide) LinearLayout.LayoutParams(0, -2, 1f)
                            else LinearLayout.LayoutParams(-1, -2))
                        accessibilityDelegate = object : View.AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = "android.widget.RadioButton"
                                info.isCheckable = true
                                info.isChecked = selected
                            }
                        }
                    }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (column > 0) marginStart = dp(6) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = dp(6) })
        }
        actionItems.firstOrNull { it.labelRes == R.string.menu_language_pair }?.let { item ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                bind(item, actionColor)
                contentDescription = "${context.getString(item.labelRes)}, ${languagePair.first} → ${languagePair.second}"
                addView(icon(item), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
                addView(label("${languagePair.first} → ${languagePair.second}"),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_menu_next_page)
                    rotation = 90f
                    imageTintList = ColorStateList.valueOf(secondaryColor)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        content.addView(View(context).apply { setBackgroundColor(colors.outlineVariant.toArgb()) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        val shortcuts = actionItems.filter { it.labelRes != R.string.menu_language_pair } + home
        shortcuts.chunked(if (sideBySide) 4 else 2).forEachIndexed { rowIndex, items ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                items.forEachIndexed { column, item ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(44)
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        bind(item, panelColor)
                        addView(icon(item), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(7) })
                        addView(label(context.getString(item.labelRes)), LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(0, -1, 1f).apply { if (column > 0) marginStart = dp(4) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = dp(2) })
        }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isClickable = true
            background = surface(panelColor, 20)
            clipToOutline = true
            elevation = dp(8).toFloat()
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
    }
}
