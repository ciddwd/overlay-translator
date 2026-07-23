package com.gameocr.app.overlay

internal data class FloatingMenuTourColors(
    val surface: Int,
    val text: Int,
    val secondaryText: Int,
    val accent: Int,
    val actionText: Int,
    val border: Int,
)

internal object FloatingMenuTourPalette {
    fun colors(nightMode: Boolean): FloatingMenuTourColors = if (nightMode) {
        FloatingMenuTourColors(
            surface = 0xFF18181B.toInt(), // zinc-900
            text = 0xFFFAFAFA.toInt(), // zinc-50
            secondaryText = 0xFFA1A1AA.toInt(), // zinc-400
            accent = 0xFFD4D4D8.toInt(), // zinc-300
            actionText = 0xFF18181B.toInt(), // zinc-900
            border = 0xFF52525B.toInt(), // zinc-600
        )
    } else {
        FloatingMenuTourColors(
            surface = 0xFFFAFAFA.toInt(), // zinc-50
            text = 0xFF18181B.toInt(), // zinc-900
            secondaryText = 0xFF52525B.toInt(), // zinc-600
            accent = 0xFF3F3F46.toInt(), // zinc-700
            actionText = 0xFFFFFFFF.toInt(),
            border = 0xFFD4D4D8.toInt(), // zinc-300
        )
    }
}
