package com.gameocr.app.overlay

import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.Settings

/** Translation appearance the floating window renders with (shell and text). */
data class FloatingWindowAppearance(
    val textSizeSp: Int = 14,
    val alpha: Float = 0.85f,
    val theme: OverlayTheme = OverlayTheme.CLASSIC_DARK,
    val textStyle: OverlayTextStyle = OverlayTextStyle(),
) {
    companion object {
        /** [settings] should come from `floatingWindowRenderSettings()`. */
        fun from(settings: Settings) = FloatingWindowAppearance(
            textSizeSp = settings.overlayTextSizeSp,
            alpha = settings.overlayAlpha,
            theme = settings.overlayTheme,
            textStyle = settings.overlayTextStyle.normalized(),
        )
    }
}
