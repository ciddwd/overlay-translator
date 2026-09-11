package com.gameocr.app.tile

/** 这两条路由会去启动服务（异步，有结果可等）；其余要么停服务、要么跳别的界面，没有可等的东西。 */
internal fun routeStartsCaptureAttempt(route: CaptureStartRoute): Boolean =
    route == CaptureStartRoute.DIRECT_FGS || route == CaptureStartRoute.TRAMPOLINE

/**
 * 点击启动之后磁贴该补写的状态；`null` 表示保留点击时写的值。不能用「等够时间就当失败」来判失败：
 * 显示 INACTIVE 而服务其实在跑时，用户再点一次会被路由成 STOP 把服务关掉；反过来显示 ACTIVE 而服务
 * 没跑，再点一次只是重试。所以只认服务主动报过的失败，结果未知就什么都不写。
 */
internal fun resolveTilePostClickState(
    serviceRunning: Boolean,
    stoppedWithoutRunningSinceClick: Boolean,
): CaptureTileState? = when {
    serviceRunning -> CaptureTileState.ACTIVE
    stoppedWithoutRunningSinceClick -> CaptureTileState.INACTIVE
    else -> null
}
