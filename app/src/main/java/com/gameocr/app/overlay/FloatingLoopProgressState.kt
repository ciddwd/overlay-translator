package com.gameocr.app.overlay

import com.gameocr.app.capture.LoopIndicatorSpec

/** The loop outlives individual floating-ball windows, for example while editing a region. */
internal class FloatingLoopProgressState {
    @Volatile private var current: LoopIndicatorSpec? = null
    private var render: ((LoopIndicatorSpec?) -> Unit)? = null

    val isActive: Boolean get() = current != null

    fun update(spec: LoopIndicatorSpec?) {
        current = spec
        render?.invoke(spec)
    }

    fun attach(renderer: (LoopIndicatorSpec?) -> Unit) {
        render = renderer
        renderer(current)
    }

    /** Release the removed view, but retain the latest loop state for the next window. */
    fun detach() {
        render = null
    }
}
