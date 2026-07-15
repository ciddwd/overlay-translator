package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperOcrDebugPolicyTest {

    @Test
    fun redBoxAndTranslationPolicy_coverAllSwitchCombinations() {
        data class Case(
            val name: String,
            val developer: Boolean,
            val redBox: Boolean,
            val translation: Boolean,
            val expectedRedBox: Boolean,
            val expectedTranslate: Boolean,
        )

        listOf(
            Case("all off", false, false, false, false, true),
            Case("red box ignored without developer mode", false, true, false, false, true),
            Case("developer only", true, false, false, false, true),
            Case("red boxes without translation", true, true, false, true, false),
            Case("red boxes with translation", true, true, true, true, true),
        ).forEach { case ->
            assertEquals(
                "${case.name} red box",
                case.expectedRedBox,
                DeveloperOcrDebugPolicy.redBoxActive(case.developer, case.redBox),
            )
            assertEquals(
                "${case.name} translation",
                case.expectedTranslate,
                DeveloperOcrDebugPolicy.shouldTranslate(case.developer, case.redBox, case.translation),
            )
        }
    }
}
