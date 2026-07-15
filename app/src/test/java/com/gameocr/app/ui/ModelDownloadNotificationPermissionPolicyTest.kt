package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadNotificationPermissionPolicyTest {
    @Test
    fun shouldRequestModelDownloadNotificationPermission_isTableDriven() {
        data class Case(
            val name: String,
            val sdkInt: Int,
            val permissionGranted: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("Android 12 denied does not require runtime request", 32, false, false),
            Case("Android 13 denied requests permission", 33, false, true),
            Case("Android 15 denied requests permission", 35, false, true),
            Case("Android 13 granted continues directly", 33, true, false),
            Case("Android 15 granted continues directly", 35, true, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldRequestModelDownloadNotificationPermission(
                    sdkInt = case.sdkInt,
                    permissionGranted = case.permissionGranted,
                ),
            )
        }
    }
}
