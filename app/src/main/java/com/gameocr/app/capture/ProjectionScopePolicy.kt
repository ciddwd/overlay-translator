package com.gameocr.app.capture

internal fun shouldRequestEntireScreen(
    sdkInt: Int,
    developerOptionsEnabled: Boolean,
    shareEntireScreen: Boolean,
): Boolean = sdkInt >= 34 && developerOptionsEnabled && shareEntireScreen
