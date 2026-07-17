package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCacheTest {
    @Test
    fun cachePolicy_tableDriven() {
        data class Case(
            val name: String,
            val developerOptionsEnabled: Boolean,
            val disableTranslationCache: Boolean,
            val value: String,
            val expectedEnabled: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("normal mode valid translation", false, false, " translated ", true, true),
            Case("stored disable flag is inert when developer mode is off", false, true, "text", true, true),
            Case("developer mode with cache enabled", true, false, "text", true, true),
            Case("developer mode disables cache", true, true, "text", false, false),
            Case("empty", false, false, "", true, false),
            Case("space", false, false, "   ", true, false),
            Case("line breaks and tabs", false, false, "\n\t", true, false),
        ).forEach { case ->
            val settings = Settings(
                developerOptionsEnabled = case.developerOptionsEnabled,
                disableTranslationCache = case.disableTranslationCache,
            )
            assertEquals(
                "${case.name} enabled",
                case.expectedEnabled,
                TranslationCachePolicy.isEnabled(settings),
            )
            assertEquals(
                "${case.name} write",
                case.expected,
                TranslationCachePolicy.shouldCache(settings, case.value),
            )
        }
    }
}
