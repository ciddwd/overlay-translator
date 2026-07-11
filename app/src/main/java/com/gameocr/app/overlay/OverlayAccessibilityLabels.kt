package com.gameocr.app.overlay

internal object OverlayAccessibilityLabels {
    fun actionWithState(
        action: String,
        state: String,
        hint: String
    ): String = joinParts(action, state, hint)

    fun option(
        title: String,
        detail: String,
        selected: Boolean,
        selectedLabel: String,
        actionHint: String
    ): String = joinParts(
        title,
        detail,
        if (selected) selectedLabel else actionHint
    )

    fun slot(
        label: String,
        value: String,
        selected: Boolean,
        selectedLabel: String,
        actionHint: String
    ): String = joinParts(
        "$label: $value",
        if (selected) selectedLabel else actionHint
    )

    fun regionPicker(
        title: String,
        width: Int?,
        height: Int?,
        drawingHint: String,
        adjustingHint: String
    ): String {
        if (width == null || height == null) return joinParts(title, drawingHint)
        return joinParts(title, "${width} x ${height}", adjustingHint)
    }

    private fun joinParts(vararg parts: String): String =
        parts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = ". ")
}
