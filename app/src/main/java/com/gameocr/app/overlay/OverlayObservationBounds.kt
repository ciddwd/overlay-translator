package com.gameocr.app.overlay

import android.graphics.Rect
import android.view.View
import kotlin.math.ceil

/** Read-only screen bounds, including transformed children and a margin for window shadows. */
internal fun View.observationBounds(): Rect? {
    if (!isShown || alpha <= 0f) return null
    val result = Rect()
    if (!getGlobalVisibleRect(result)) return null
    val origin = IntArray(2)
    rootView.getLocationOnScreen(origin)
    result.offset(origin[0], origin[1])
    val margin = ceil(elevation * 2f + resources.displayMetrics.density * 2f).toInt()
    result.inset(-margin, -margin)
    return result
}
