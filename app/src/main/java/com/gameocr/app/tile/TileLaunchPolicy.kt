package com.gameocr.app.tile

/** 两个重载缺一必崩：≥34 用 `PendingIntent`（`Intent` 那时会抛异常），<34 只能用废弃的 `Intent`（另一个不存在）。 */
internal enum class TileLaunchApi {
    LEGACY_INTENT,
    PENDING_INTENT,
}

internal const val TILE_PENDING_INTENT_MIN_SDK = 34

internal fun resolveTileLaunchApi(sdkInt: Int): TileLaunchApi =
    if (sdkInt >= TILE_PENDING_INTENT_MIN_SDK) TileLaunchApi.PENDING_INTENT else TileLaunchApi.LEGACY_INTENT

/** targetSdk ≥ 35 起创建方默认不再委派 BAL 权限，缺了会静默拦下（Logcat 只有 `Background activity launch blocked!`）。 */
internal const val PENDING_INTENT_CREATOR_OPT_IN_MIN_SDK = 34

internal fun needsPendingIntentCreatorOptIn(sdkInt: Int): Boolean =
    sdkInt >= PENDING_INTENT_CREATOR_OPT_IN_MIN_SDK
