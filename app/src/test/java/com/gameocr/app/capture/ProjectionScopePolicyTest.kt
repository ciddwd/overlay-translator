package com.gameocr.app.capture

import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectionScopePolicyTest {
    @Test fun scopeRequiresSupportedAndroidAndBothSwitches() {
        for (sdk in listOf(26, 33, 34, 35, 36)) {
            for (developer in listOf(false, true)) {
                for (enabled in listOf(false, true)) {
                    assertEquals("sdk=$sdk developer=$developer enabled=$enabled",
                        sdk >= 34 && developer && enabled,
                        shouldRequestEntireScreen(sdk, developer, enabled))
                }
            }
        }
    }

    @Test fun defaultPreservesSystemChoice() {
        assertFalse(Settings().shareEntireScreen)
    }
}
