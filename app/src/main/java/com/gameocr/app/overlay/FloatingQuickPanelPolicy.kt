package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.MenuItemId

internal data class QuickPanelBounds(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class QuickPanelLayout(val width: Int, val sideBySide: Boolean)

internal object FloatingQuickPanelPolicy {
    val modes = listOf(FloatingSkill.FULL_SCREEN, FloatingSkill.WORD_SELECT, FloatingSkill.LOOP,
        FloatingSkill.INPUT_TRANSLATE, FloatingSkill.GESTURE, FloatingSkill.SPEECH)
    val actions = listOf(MenuItemId.REGION, MenuItemId.LANGUAGE_PAIR, MenuItemId.PRESET_SWITCH, MenuItemId.SETTINGS)

    fun usePanel(enabled: Boolean, tourActive: Boolean): Boolean = enabled && !tourActive

    fun modeChange(current: FloatingSkill, target: FloatingSkill): FloatingSkill? = target.takeIf { it != current }

    /** Choose from the current usable viewport; narrow split windows keep the stacked layout. */
    fun layout(
        availableWidth: Int, availableHeight: Int,
        compactWidth: Int, expandedWidth: Int, minimumExpandedWidth: Int,
    ): QuickPanelLayout {
        val width = availableWidth.coerceAtLeast(1)
        val height = availableHeight.coerceAtLeast(1)
        val sideBySide = width > height && width >= minimumExpandedWidth.coerceAtLeast(1)
        return QuickPanelLayout(
            (if (sideBySide) expandedWidth else compactWidth).coerceIn(1, width),
            sideBySide,
        )
    }

    /** All coordinates share the floating-ball window's physical-screen origin. */
    fun place(
        left: Int, top: Int, right: Int, bottom: Int,
        anchorLeft: Int, anchorTop: Int, anchorRight: Int, anchorBottom: Int,
        desiredWidth: Int, desiredHeight: Int, gap: Int,
    ): QuickPanelBounds {
        val width = desiredWidth.coerceIn(1, (right - left).coerceAtLeast(1))
        val height = desiredHeight.coerceIn(1, (bottom - top).coerceAtLeast(1))
        val spacing = gap.coerceAtLeast(0)
        val besideLeft = anchorLeft - spacing - width >= left
        val besideRight = anchorRight + spacing + width <= right
        val x = when {
            besideLeft -> anchorLeft - spacing - width
            besideRight -> anchorRight + spacing
            else -> (anchorLeft + anchorRight - width) / 2
        }.coerceIn(left, (right - width).coerceAtLeast(left))
        val y = when {
            besideLeft || besideRight -> (anchorTop + anchorBottom - height) / 2
            anchorTop - spacing - height >= top -> anchorTop - spacing - height
            anchorBottom + spacing + height <= bottom -> anchorBottom + spacing
            else -> (anchorTop + anchorBottom - height) / 2
        }.coerceIn(top, (bottom - height).coerceAtLeast(top))
        return QuickPanelBounds(x, y, width, height)
    }
}
