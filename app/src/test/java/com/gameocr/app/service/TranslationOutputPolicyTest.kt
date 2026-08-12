package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOutputPolicyTest {
    @Test
    fun shouldRetryInCaller_tableDriven_avoidsDoubleRetry() {
        data class Case(
            val name: String,
            val settingEnabled: Boolean,
            val translatorHandlesRetry: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("disabled setting", false, false, false),
            Case("disabled setting and internal engine", false, true, false),
            Case("caller owns retry", true, false, true),
            Case("engine owns retry", true, true, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                TranslationOutputPolicy.shouldRetryInCaller(
                    settingEnabled = case.settingEnabled,
                    translatorHandlesRetry = case.translatorHandlesRetry,
                ),
            )
        }
    }

    @Test
    fun action_retriesOnlyTheFirstBlankResultWhenEnabled() {
        data class Case(
            val name: String,
            val output: String?,
            val retryEnabled: Boolean,
            val attempt: Int,
            val expected: TranslationRetryAction,
        )

        listOf(
            Case("valid first result", "translated", true, 0, TranslationRetryAction.ACCEPT),
            Case("valid retry result", "translated", true, 1, TranslationRetryAction.ACCEPT),
            Case("null first result with retry", null, true, 0, TranslationRetryAction.RETRY),
            Case("empty first result with retry", "", true, 0, TranslationRetryAction.RETRY),
            Case("whitespace first result with retry", " \n\t", true, 0, TranslationRetryAction.RETRY),
            Case("blank first result without retry", "", false, 0, TranslationRetryAction.FAIL),
            Case("blank retry result", "", true, 1, TranslationRetryAction.FAIL),
            Case("blank later result never retries", null, true, 2, TranslationRetryAction.FAIL),
            Case("invalid negative attempt never retries", null, true, -1, TranslationRetryAction.FAIL),
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
