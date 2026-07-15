package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOutputSettingsPolicyTest {
    @Test
    fun resolveTranslationOutputSettings_migratesLegacyAndRetainsManualValues() {
        data class Case(
            val name: String,
            val storedFollow: Boolean?,
            val layout: TranslationOutputLayout,
            val direction: TranslationOutputDirection,
            val expected: ResolvedTranslationOutputSettings,
        )

        val cases = listOf(
            Case("legacy both follow", null, followLayout, followDirection, resolved(true, horizontal, ltr)),
            Case("legacy vertical defaults on and keeps manual rtl", null, vertical, followDirection, resolved(true, vertical, rtl)),
            Case("legacy horizontal defaults on and keeps manual ltr", null, horizontal, followDirection, resolved(true, horizontal, ltr)),
            Case("legacy follow layout defaults on and keeps explicit rtl", null, followLayout, rtl, resolved(true, horizontal, rtl)),
            Case("legacy explicit vertical ltr defaults on", null, vertical, ltr, resolved(true, vertical, ltr)),
            Case("new follow keeps manual vertical rtl", true, vertical, rtl, resolved(true, vertical, rtl)),
            Case("new manual keeps horizontal rtl", false, horizontal, rtl, resolved(false, horizontal, rtl)),
            Case("new manual sanitizes old follow values", false, followLayout, followDirection, resolved(false, horizontal, ltr)),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolveTranslationOutputSettings(case.storedFollow, case.layout, case.direction),
            )
        }
    }

    private fun resolved(
        follow: Boolean,
        layout: TranslationOutputLayout,
        direction: TranslationOutputDirection,
    ) = ResolvedTranslationOutputSettings(follow, layout, direction)

    private val followLayout = TranslationOutputLayout.FOLLOW_RECOGNITION
    private val horizontal = TranslationOutputLayout.HORIZONTAL
    private val vertical = TranslationOutputLayout.VERTICAL
    private val followDirection = TranslationOutputDirection.FOLLOW_RECOGNITION
    private val ltr = TranslationOutputDirection.LEFT_TO_RIGHT
    private val rtl = TranslationOutputDirection.RIGHT_TO_LEFT
}
