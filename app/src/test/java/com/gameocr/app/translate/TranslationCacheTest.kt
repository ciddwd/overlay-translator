package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCacheTest {
    @Test
    fun shouldCache_rejectsEmptyTranslationResults() {
        data class Case(
            val name: String,
            val value: String,
            val expected: Boolean,
        )

        listOf(
            Case("empty", "", false),
            Case("space", "   ", false),
            Case("line breaks and tabs", "\n\t", false),
            Case("valid translation", " translated ", true),
        ).forEach { case ->
            assertEquals(case.name, case.expected, TranslationCachePolicy.shouldCache(case.value))
        }
    }
}
