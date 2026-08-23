package com.gameocr.app.llm

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for the native on-device LLM runtime requirement. */
@Singleton
class LocalLlmDeviceCapability @Inject constructor() {
    fun isSupported(): Boolean = supportsSdk(Build.VERSION.SDK_INT)

    companion object {
        internal fun supportsSdk(sdkInt: Int): Boolean =
            sdkInt >= Build.VERSION_CODES.TIRAMISU
    }
}
