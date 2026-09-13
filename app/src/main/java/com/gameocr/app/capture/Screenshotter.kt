package com.gameocr.app.capture

import android.graphics.Bitmap

/**
 * 截屏抽象。M0 用 [MediaProjectionScreenshotter]，
 * M2 可加 ShizukuScreenshotter 走 system identity 免每次弹窗。
 */
interface Screenshotter {

    /** 是否已就绪：MediaProjection 已授权且 VirtualDisplay 已建好。 */
    val isReady: Boolean

    /** Backend-specific lower bound for repeated lightweight page observations. */
    val minimumLoopObservationIntervalMs: Long
        get() = 0L

    /** 抓一帧。返回 null 表示这一帧没有就绪 / 上一帧没变化，调用方应跳过。 */
    suspend fun capture(): Bitmap?

    /** 释放资源（VirtualDisplay、ImageReader、MediaProjection token）。 */
    fun release()
}
