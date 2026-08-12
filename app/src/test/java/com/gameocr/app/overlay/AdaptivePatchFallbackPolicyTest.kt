package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePatchFallbackPolicyTest {

    @Test
    fun unresolvedBlockIndices_tableDriven_fallsBackOnlyForTranslatedUncoveredBlocks() {
        data class Case(
            val name: String,
            val translated: Set<Int>,
            val displayed: Set<Int>,
            val expectedFallback: Set<Int>,
        )

        val cases = listOf(
            Case(
                name = "all shape and text patches displayed",
                translated = setOf(0, 1, 2),
                displayed = setOf(0, 1, 2),
                expectedFallback = emptySet(),
            ),
            Case(
                name = "one missing from an eighteen block page",
                translated = (0 until 18).toSet(),
                displayed = (0 until 18).filterNot { it == 11 }.toSet(),
                expectedFallback = setOf(11),
            ),
            Case(
                name = "approximate ellipse succeeds through text repair",
                translated = setOf(3),
                displayed = setOf(3),
                expectedFallback = emptySet(),
            ),
            Case(
                name = "approximate ellipse and text repair both fail",
                translated = setOf(3),
                displayed = emptySet(),
                expectedFallback = setOf(3),
            ),
            Case(
                name = "generated patch that failed to display remains unresolved",
                translated = setOf(4, 7),
                displayed = setOf(4),
                expectedFallback = setOf(7),
            ),
            Case(
                name = "untranslated blocks do not receive a fallback",
                translated = setOf(2),
                displayed = setOf(2, 8),
                expectedFallback = emptySet(),
            ),
            Case(
                name = "invalid block indices are ignored",
                translated = setOf(-1, 0),
                displayed = emptySet(),
                expectedFallback = setOf(0),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedFallback,
                AdaptivePatchFallbackPolicy.unresolvedBlockIndices(
                    translatedBlockIndices = case.translated,
                    displayedPatchBlockIndices = case.displayed,
                ),
            )
        }
    }
}
