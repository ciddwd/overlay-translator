package com.gameocr.app.llm

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmDeviceCapabilityTest {
    @Test
    fun sdkSupportBoundary_isTableDriven() {
        data class Case(val sdk: Int, val supported: Boolean)
        val cases = listOf(
            Case(Build.VERSION_CODES.O, false),
            Case(Build.VERSION_CODES.S_V2, false),
            Case(Build.VERSION_CODES.TIRAMISU - 1, false),
            Case(Build.VERSION_CODES.TIRAMISU, true),
            Case(Build.VERSION_CODES.VANILLA_ICE_CREAM, true),
        )

        cases.forEach { case ->
            assertEquals(
                "sdk=${case.sdk}",
                case.supported,
                LocalLlmDeviceCapability.supportsSdk(case.sdk),
            )
        }
    }
}
