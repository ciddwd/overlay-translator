package com.gameocr.app.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

internal data class DisplayPixelSize(
    val width: Int,
    val height: Int,
) {
    val isValid: Boolean
        get() = width > 0 && height > 0
}

/**
 * Reads one full-display coordinate space for screenshots and overlay windows on demand.
 * WindowMetrics bounds already include system bars/cutouts; do not subtract their insets.
 * Legacy Display metrics are a fallback, never an authority over current WindowMetrics.
 */
internal fun readFullDisplaySize(
    maximumWindow: () -> DisplayPixelSize,
    legacyDisplay: () -> DisplayPixelSize,
    resources: () -> DisplayPixelSize,
): DisplayPixelSize {
    for (read in listOf(maximumWindow, legacyDisplay, resources)) {
        val size = runCatching(read).getOrNull()
        if (size != null && size.isValid) return size
    }
    return DisplayPixelSize(width = 1, height = 1)
}

@Suppress("DEPRECATION")
internal fun physicalDisplaySize(context: Context): DisplayPixelSize = readFullDisplaySize(
    maximumWindow = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = requireNotNull(wm).maximumWindowMetrics.bounds
            DisplayPixelSize(bounds.width(), bounds.height())
        } else {
            DisplayPixelSize(0, 0)
        }
    },
    legacyDisplay = {
        val metrics = DisplayMetrics()
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        manager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        DisplayPixelSize(metrics.widthPixels, metrics.heightPixels)
    },
    resources = {
        val metrics = context.resources.displayMetrics
        DisplayPixelSize(metrics.widthPixels, metrics.heightPixels)
    },
)
