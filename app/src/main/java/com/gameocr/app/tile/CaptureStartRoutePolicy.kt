package com.gameocr.app.tile

/** 磁贴一次点击的去向。名字里的 "Start" 是历史遗留（表里也含停止与补权限）。[TRAMPOLINE] 指经 MediaProjection 授权窗中转。 */
internal enum class CaptureStartRoute {
    STOP,
    DIRECT_FGS,
    TRAMPOLINE,
    BLOCKED_NEED_UNLOCK,
    NEEDS_OVERLAY_PERMISSION,
    UNAVAILABLE,
}

/**
 * @param canDrawOverlay 必须用 `Settings.canDrawOverlays()` 回读，不能相信自己刚做的权限写入。
 * @param preferShizuku 由调用方按「Shizuku 是否 READY」推导：主屏的 StartMode 是本地 Compose 状态、
 *   不持久化，磁贴读不到用户的显式选择；且比主屏窄一档 —— 主屏还能用 `ensureShizukuReady()` 引导授权，
 *   磁贴没有这条路，未授权就直连只会得到一个跑不动 `screencap` 的 Shizuku。
 */
internal data class CaptureStartRouteInput(
    val setupCompleted: Boolean,
    val canDrawOverlay: Boolean,
    val serviceRunning: Boolean,
    val isLocked: Boolean,
    val preferShizuku: Boolean,
)

/**
 * @param useShizuku 这是「请求」而非保证：服务会用更新的一次探测覆盖它。覆盖失败时服务直接
 *   `stopSelf()`（直连路径不带 MediaProjection token，没有可回落的东西），不会自行降级 ——
 *   回落授权窗只发生在经 `MediaProjectionRequestActivity` 的中转路径上。
 */
internal data class CaptureStartDecision(
    val route: CaptureStartRoute,
    val useShizuku: Boolean,
)

/** 纯策略：把点击路由到具体动作。判定顺序即优先级，前一条命中即返回。 */
internal fun decideCaptureStartRoute(input: CaptureStartRouteInput): CaptureStartDecision {
    if (!input.setupCompleted) {
        return CaptureStartDecision(CaptureStartRoute.UNAVAILABLE, useShizuku = false)
    }
    // 停止压过悬浮窗权限与后续启动条件（权限被事后回收时用户仍要能把服务关掉），但压不过 setup 闸门。
    if (input.serviceRunning) {
        return CaptureStartDecision(CaptureStartRoute.STOP, useShizuku = false)
    }
    // 排在 preferShizuku 之前：Shizuku 能让服务起来，但画不出悬浮球，用户会以为磁贴坏了。
    if (!input.canDrawOverlay) {
        return CaptureStartDecision(CaptureStartRoute.NEEDS_OVERLAY_PERMISSION, useShizuku = false)
    }
    // Shizuku 路径不需要可见 Activity 承载授权框，故能排在锁屏闸门之前（锁屏直起尚未真机验证）。（与「服务不画 UI」无关 —— 它画悬浮球。）
    if (input.preferShizuku) {
        return CaptureStartDecision(CaptureStartRoute.DIRECT_FGS, useShizuku = true)
    }
    // MediaProjection 的授权窗必须有可见 Activity 承载，且锁屏下无法显示。
    return if (input.isLocked) {
        CaptureStartDecision(CaptureStartRoute.BLOCKED_NEED_UNLOCK, useShizuku = false)
    } else {
        CaptureStartDecision(CaptureStartRoute.TRAMPOLINE, useShizuku = false)
    }
}
