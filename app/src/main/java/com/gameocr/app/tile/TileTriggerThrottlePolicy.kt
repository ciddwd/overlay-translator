package com.gameocr.app.tile

/** 磁贴连点抑制窗口。 */
internal const val TILE_TRIGGER_MIN_INTERVAL_MS = 800L

/** 连点会让启动重跑 `handleStart()` 重建悬浮球；**只约束启动**，挡住停止会让服务变成关不掉的僵尸。 */
internal fun shouldAcceptTileTrigger(
    lastAcceptedAtMs: Long?,
    nowMs: Long,
): Boolean {
    if (lastAcceptedAtMs == null) return true
    // 防御分支：调用方传的是 `SystemClock.elapsedRealtime()`（单调，不随墙钟倒退），所以实际不可达。
    // 留着是为了将来若换成墙钟计时，不会把磁贴永久锁死。
    if (nowMs < lastAcceptedAtMs) return true
    return nowMs - lastAcceptedAtMs >= TILE_TRIGGER_MIN_INTERVAL_MS
}
