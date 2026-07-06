package com.gameocr.app.capture

import android.hardware.display.DisplayManager

internal object MediaProjectionCaptureConfig {
    val virtualDisplayFlags: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
}
