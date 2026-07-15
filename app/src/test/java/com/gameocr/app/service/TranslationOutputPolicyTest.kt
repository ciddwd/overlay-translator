package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOutputPolicyTest {
    @Test
    fun action_retriesOnlyTheFirstBlankResultWhenEnabled() {
        data class Case(
            val name: String,
            val output: String?,
            val retryEnabled: Boolean,
            val attempt: Int,
            val expected: EmptyTranslationAction,
        )

        listOf(
            Case("valid first result", "translated", true, 0, EmptyTranslationAction.ACCEPT),
            Case("valid retry result", "translated", true, 1, EmptyTranslationAction.ACCEPT),
            Case("null first result with retry", null, true, 0, EmptyTranslationAction.RETRY),
            Case("empty first result with retry", "", true, 0, EmptyTranslationAction.RETRY),
            Case("whitespace first result with retry", " \n\t", true, 0, EmptyTranslationAction.RETRY),
            Case("blank first result without retry", "", false, 0, EmptyTranslationAction.FAIL),
            Case("blank retry result", "", true, 1, EmptyTranslationAction.FAIL),
            Case("blank later result never retries", null, true, 2, EmptyTranslationAction.FAIL),
            Case("invalid negative attempt never retries", null, true, -1, EmptyTranslationAction.FAIL),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                TranslationOutputPolicy.action(case.output, case.retryEnabled, case.attempt),
            )
        }
    }

    @Test
    fun resolve_neverLeavesBlankOrSourceFallbackForMissingTranslation() {
        data class Case(
            val name: String,
            val output: String?,
            val expectedText: String,
            val expectedFailed: Boolean,
        )
        val failure = "[!] Translation failed"
        listOf(
            Case("null result", null, failure, true),
            Case("empty result", "", failure, true),
            Case("whitespace result", " \n\t", failure, true),
            Case("valid result", "译文", "译文", false),
            Case("valid formatting is preserved", " line one\nline two ", " line one\nline two ", false),
        ).forEach { case ->
            val result = TranslationOutputPolicy.resolve(case.output, failure)
            assertEquals(case.name, case.expectedText, result.text)
            assertEquals(case.name, case.expectedFailed, result.failed)
        }
    }
}
