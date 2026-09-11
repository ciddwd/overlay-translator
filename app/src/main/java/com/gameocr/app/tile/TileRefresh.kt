package com.gameocr.app.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import timber.log.Timber

/** 请系统重绑磁贴重读状态。磁贴已在监听时该请求会被系统吞掉 —— 点击后不收起面板正属此况。 */
internal fun requestCaptureTileRefresh(context: Context) {
    runCatching {
        TileService.requestListeningState(
            context,
            ComponentName(context, CaptureTileService::class.java),
        )
    }.onFailure { Timber.d(it, "tile refresh request ignored") }
}
