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
 * Chooses one full-display coordinate space for screenshots and overlay windows.
 * Real display metrics are preferred because some ROMs expose an inset application workspace
 * through service-context WindowMetrics. The remaining candidates are defensive fallbacks.
 */
internal fun selectPhysicalDisplaySize(
    realDisplay: DisplayPixelSize,
    maximumWindow: DisplayPixelSize,
    resources: DisplayPixelSize,
): DisplayPixelSize = sequenceOf(realDisplay, maximumWindow, resources)
    .firstOrNull(DisplayPixelSize::isValid)
    ?: DisplayPixelSize(width = 1, height = 1)

@Suppress("DEPRECATION")
internal fun physicalDisplaySize(context: Context): DisplayPixelSize {
    val realMetrics = DisplayMetrics()
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    runCatching { display?.getRealMetrics(realMetrics) }

    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val maximumWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            val bounds = requireNotNull(wm).maximumWindowMetrics.bounds
            DisplayPixelSize(bounds.width(), bounds.height())
        }.getOrDefault(DisplayPixelSize(0, 0))
    } else {
        DisplayPixelSize(0, 0)
    }
    val resourceMetrics = context.resources.displayMetrics
    return selectPhysicalDisplaySize(
        realDisplay = DisplayPixelSize(realMetrics.widthPixels, realMetrics.heightPixels),
        maximumWindow = maximumWindow,
        resources = DisplayPixelSize(resourceMetrics.widthPixels, resourceMetrics.heightPixels),
    )
}
