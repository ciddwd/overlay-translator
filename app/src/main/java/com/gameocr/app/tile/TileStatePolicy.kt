package com.gameocr.app.tile

/** 保持零 Android 依赖（不直接用 `Tile.STATE_*`），才能放进纯 JVM 单测。 */
internal enum class CaptureTileState {
    ACTIVE,
    INACTIVE,
    UNAVAILABLE,
}

/** 不因「Shizuku 未就绪」置 UNAVAILABLE —— 那时会降级走 MediaProjection，磁贴依然可用。 */
internal fun resolveCaptureTileState(
    setupCompleted: Boolean,
    serviceRunning: Boolean,
): CaptureTileState = when {
    !setupCompleted -> CaptureTileState.UNAVAILABLE
    serviceRunning -> CaptureTileState.ACTIVE
    else -> CaptureTileState.INACTIVE
}
