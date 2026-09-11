package com.gameocr.app.service

/** 启动失败后还能做什么。 */
internal enum class CaptureStartRecovery {
    NONE,
    OPEN_PROJECTION_GATEWAY,
}

/**
 * @param tokenPresent 本 intent 是否已带 MediaProjection 授权 token。
 * @param shizukuRequested 调用方是否要求走 Shizuku（只有磁贴直连会带 `EXTRA_USE_SHIZUKU`）。
 */
internal data class CaptureStartRecoveryInput(
    val tokenPresent: Boolean,
    val shizukuRequested: Boolean,
)

/**
 * 启动已失败、服务即将 `stopSelf()` 时，是否要把用户交给
 * [com.gameocr.app.capture.MediaProjectionRequestActivity]：直连路径不带授权 token，服务侧复核不成立后
 * 就只剩「点了没反应」（Toast 被 ROM 丢、服务没起来画不出悬浮错误条、面板开着时磁贴刷新又被系统吞），
 * 而缺的那个 token 只能由系统授权窗产出；反过来，带 token 的启动一定是授权窗自己发起的，再拉起它会让
 * 用户在「弹窗 → 失败 → 弹窗」之间打转。
 */
internal fun decideCaptureStartRecovery(input: CaptureStartRecoveryInput): CaptureStartRecovery = when {
    input.tokenPresent -> CaptureStartRecovery.NONE
    input.shizukuRequested -> CaptureStartRecovery.OPEN_PROJECTION_GATEWAY
    else -> CaptureStartRecovery.NONE
}
