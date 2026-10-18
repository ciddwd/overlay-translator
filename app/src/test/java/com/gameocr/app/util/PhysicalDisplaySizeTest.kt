package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalDisplaySizeTest {
    @Test
    fun currentSystemBounds_tableDriven_overrideStaleMetricsWithoutInferringTheirRelationship() {
        data class Case(val name: String, val current: DisplayPixelSize,
                        val legacy: DisplayPixelSize, val resources: DisplayPixelSize)
        listOf(
            Case("phone landscape after rotation", DisplayPixelSize(2400, 1080),
                DisplayPixelSize(1080, 2400), DisplayPixelSize(2270, 1080)),
            Case("phone portrait after rotation", DisplayPixelSize(1080, 2400),
                DisplayPixelSize(2400, 1080), DisplayPixelSize(1080, 2270)),
            Case("same direction resolution reduction", DisplayPixelSize(720, 1600),
                DisplayPixelSize(1080, 2400), DisplayPixelSize(720, 1510)),
            Case("same direction resolution increase", DisplayPixelSize(1440, 3200),
                DisplayPixelSize(1080, 2400), DisplayPixelSize(1440, 3053)),
            Case("tablet", DisplayPixelSize(2560, 1600),
                DisplayPixelSize(1600, 2560), DisplayPixelSize(2560, 1504)),
            Case("fold unfolded without swapping the old dimensions", DisplayPixelSize(2208, 1840),
                DisplayPixelSize(1080, 2520), DisplayPixelSize(2208, 1720)),
            Case("fold closed", DisplayPixelSize(1080, 2520),
                DisplayPixelSize(2208, 1840), DisplayPixelSize(1080, 2400)),
            Case("odd pixel dimensions", DisplayPixelSize(981, 2055),
                DisplayPixelSize(1440, 3200), DisplayPixelSize(981, 1991)),
            Case("square display", DisplayPixelSize(2048, 2048),
                DisplayPixelSize(1080, 2400), DisplayPixelSize(2048, 1948)),
            Case("all system bar and cutout areas remain included", DisplayPixelSize(3200, 1440),
                DisplayPixelSize(3200, 1440), DisplayPixelSize(3053, 1293)),
            Case("missing resource geometry is not needed", DisplayPixelSize(1920, 1080),
                DisplayPixelSize(1080, 1920), DisplayPixelSize(0, 0)),
        ).forEach { case ->
            val reads = mutableListOf<String>()
            val actual = readFullDisplaySize(
                maximumWindow = { reads += "current"; case.current },
                legacyDisplay = { reads += "legacy"; case.legacy },
                resources = { reads += "resources"; case.resources },
            )
            assertEquals(case.name, case.current, actual)
            assertEquals(case.name, listOf("current"), reads)
        }
    }

    @Test
    fun theSameReaderQueriesNewBoundsOnEveryCall_acrossRotationResizeAndFoldChanges() {
        val frames = listOf(DisplayPixelSize(1080, 2400), DisplayPixelSize(2400, 1080),
            DisplayPixelSize(720, 1600), DisplayPixelSize(1440, 3200),
            DisplayPixelSize(2208, 1840), DisplayPixelSize(1080, 2520))
        var current = frames.first()
        var queries = 0
        var fallbackQueries = 0
        val readCurrent = { queries++; current }
        val stale = { fallbackQueries++; frames.first() }
        val actual = frames.map { next ->
            current = next
            readFullDisplaySize(readCurrent, stale, stale)
        }
        assertEquals(frames, actual)
        assertEquals(frames.size, queries)
        assertEquals(0, fallbackQueries)
    }

    @Test
    fun unavailableAndInvalidSources_tableDriven_fallBackInOrder() {
        data class Case(val current: DisplayPixelSize, val legacy: DisplayPixelSize,
                        val resources: DisplayPixelSize, val expected: DisplayPixelSize)
        listOf(
            Case(DisplayPixelSize(0, 0), DisplayPixelSize(1080, 2400),
                DisplayPixelSize(1080, 2270), DisplayPixelSize(1080, 2400)),
            Case(DisplayPixelSize(-1, 1600), DisplayPixelSize(2560, 1600),
                DisplayPixelSize(2400, 1600), DisplayPixelSize(2560, 1600)),
            Case(DisplayPixelSize(2560, 0), DisplayPixelSize(0, 0),
                DisplayPixelSize(1920, 1080), DisplayPixelSize(1920, 1080)),
            Case(DisplayPixelSize(0, 0), DisplayPixelSize(-1, -1),
                DisplayPixelSize(0, 100), DisplayPixelSize(1, 1)),
            Case(DisplayPixelSize(1, 1), DisplayPixelSize(0, 0),
                DisplayPixelSize(0, 0), DisplayPixelSize(1, 1)),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected,
                readFullDisplaySize({ case.current }, { case.legacy }, { case.resources }))
        }
    }

    @Test
    fun sourceFailures_tableDriven_doNotPreventUsingTheNextAvailableSource() {
        for (failedSources in 1..3) {
            val reads = mutableListOf<Int>()
            val sources = (0..2).map { index ->
                {
                    reads += index
                    if (index < failedSources) throw IllegalStateException("Unavailable source $index")
                    DisplayPixelSize(1920, 1080)
                }
            }
            assertEquals(if (failedSources == 3) DisplayPixelSize(1, 1) else DisplayPixelSize(1920, 1080),
                readFullDisplaySize(sources[0], sources[1], sources[2]))
            assertEquals((0..minOf(failedSources, 2)).toList(), reads)
        }
    }
}
