package com.gameocr.app.tile

/**
 * 一次点击之后磁贴该自己写下的终值；`null` 表示保留 `onStartListening` 已渲染的值。点击后没有第二次
 * 刷新机会（停止与直连都不收起面板，磁贴仍在监听，服务随后的 `requestListeningState` 会被系统吞掉
 * —— 已在监听时系统不再回调 `onStartListening`），所以必须在这里定死；调用约定是分发完路由动作之后
 * **无条件**调用一次 —— 写进各分支里的话，将来新增路由漏写一处不会有任何提示，磁贴会一直停在旧状态。
 *
 * @param directStartSucceeded 只有 [CaptureStartRoute.DIRECT_FGS] 会读它；降级走授权窗时传 `false`。
 */
internal fun resolveTileClickState(
    route: CaptureStartRoute,
    directStartSucceeded: Boolean,
): CaptureTileState? = when (route) {
    // 停止是异步的：此刻回读必然拿到陈旧的 true，只能在这里写死。
    CaptureStartRoute.STOP -> CaptureTileState.INACTIVE
    // 直连不收起面板 ⇒ 同 STOP；降级走授权窗时不写 —— 面板会收起，服务那时的刷新通知是有效的。
    CaptureStartRoute.DIRECT_FGS ->
        if (directStartSucceeded) CaptureTileState.ACTIVE else null
    // 这四个分支要么会收起面板（跳 Activity），要么压根不改运行状态，留着已渲染的值即可。
    CaptureStartRoute.TRAMPOLINE,
    CaptureStartRoute.BLOCKED_NEED_UNLOCK,
    CaptureStartRoute.NEEDS_OVERLAY_PERMISSION,
    CaptureStartRoute.UNAVAILABLE,
    -> null
}
