package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextRegionGranularity
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.ocr.sortTextBlocksForReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationUnitTest {

    @Test
    fun finalUnits_tableDriven_remainOneToOneForEveryContextAndPresentation() {
        data class Case(
            val contextMode: TranslationContextMode,
            val presentation: RenderMode,
        )

        val blocks = listOf(
            block("line", 0, TextRegionGranularity.LINE),
            block("bubble", 100, TextRegionGranularity.BUBBLE),
            block("free", 200, TextRegionGranularity.FREE_TEXT),
        )
        val cases = TranslationContextMode.entries.flatMap { contextMode ->
            listOf(RenderMode.BLOCKS, RenderMode.FLOATING_WINDOW).map { presentation ->
                Case(contextMode, presentation)
            }
        }

        cases.forEach { case ->
            val units = planPageTranslationUnits(blocks)

            assertEquals("$case unit count", blocks.size, units.size)
            assertEquals("$case block indexes", listOf(0, 1, 2), units.map { it.blockIndex })
            assertEquals("$case sources", blocks.map { it.text }, units.map { it.sourceText })
            assertTrue("$case must not combine OCR blocks", units.all { unit ->
                '\n' !in unit.sourceText
            })
        }
    }

    @Test
    fun finalUnits_tableDriven_preserveEmptySingleAndAdjacentBlocks() {
        data class Case(
            val name: String,
            val blocks: List<TextBlock>,
            val expectedSources: List<String>,
        )

        listOf(
            Case("empty page", emptyList(), emptyList()),
            Case("single block", listOf(block("one", 0)), listOf("one")),
            Case(
                "adjacent blocks remain separate",
                listOf(block("first", 0), block("second", 1)),
                listOf("first", "second"),
            ),
            Case(
                "ruby-like neighbor remains separate",
                listOf(block("教室で", 0), block("きょうしつ", 1)),
                listOf("教室で", "きょうしつ"),
            ),
        ).forEach { case ->
            val units = planPageTranslationUnits(case.blocks)
            assertEquals(case.name, case.expectedSources, units.map(PageTranslationUnit::sourceText))
            assertEquals(case.name, case.blocks.indices.toList(), units.map(PageTranslationUnit::blockIndex))
        }
    }

    @Test
    fun mergeAll_tableDriven_isEnabledOnlyForFloatingWindow() {
        data class Case(
            val name: String,
            val presentation: RenderMode,
            val mergeEnabled: Boolean,
            val strength: MergeStrength,
            val expectedCount: Int,
        )
        val blocks = listOf(block("first", 0), block("second", 500))
        listOf(
            Case("Blocks hides and ignores all", RenderMode.BLOCKS, true, MergeStrength.ALL, 2),
            Case("floating merge switch off", RenderMode.FLOATING_WINDOW, false, MergeStrength.ALL, 2),
            Case("floating aggressive still uses geometry result", RenderMode.FLOATING_WINDOW, true,
                MergeStrength.AGGRESSIVE, 2),
            Case("floating all ignores distance", RenderMode.FLOATING_WINDOW, true, MergeStrength.ALL, 1),
        ).forEach { case ->
            val actual = planPageTranslationUnits(
                blocks = blocks,
                presentation = case.presentation,
                mergeAdjacentBlocks = case.mergeEnabled,
                mergeStrength = case.strength,
            )
            assertEquals(case.name, case.expectedCount, actual.size)
        }
    }

    @Test
    fun mergeAll_tableDriven_buildsOneSingleLineUnitInExistingReadingOrder() {
        val blocks = listOf(
            TextBlock("実際の\n授業", rect(20, 30, 80, 90)),
            TextBlock("  ", rect(200, 200, 210, 210)),
            TextBlock("two\r\nwords", rect(400, 10, 520, 70)),
        )

        val unit = planPageTranslationUnits(
            blocks = blocks,
            presentation = RenderMode.FLOATING_WINDOW,
            mergeAdjacentBlocks = true,
            mergeStrength = MergeStrength.ALL,
        ).single()

        assertEquals("current OCR order with one boundary space", "実際の授業 two words", unit.sourceText)
        assertEquals("one floating row", 0, unit.blockIndex)
        assertEquals("blank OCR block excluded", listOf(0, 2), unit.blockIndexes)
        assertEquals(DialogueGeometry(20, 10, 520, 90), unit.geometry)
    }

    @Test
    fun mergeAll_tableDriven_sortsAtomicRegionsBeforeSingleUnitConcatenation() {
        data class Case(
            val name: String,
            val blocks: List<TextBlock>,
            val orientation: TextOrientation,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "vertical manga reads upper tier right to left before lower tier",
                blocks = listOf(
                    mangaBlock("左下", 100, 600, 180, 760),
                    mangaBlock("右上", 800, 50, 880, 210),
                    mangaBlock("右下", 780, 600, 860, 760),
                    mangaBlock("左上", 120, 50, 200, 210),
                ),
                orientation = TextOrientation.VERTICAL_RTL,
                expected = "右上 左上 右下 左下",
            ),
            Case(
                name = "single-link bridge candidates retain ordinary left-to-right order",
                blocks = listOf(
                    block("C", 440),
                    block("A", 0),
                    block("B", 220),
                ),
                orientation = TextOrientation.HORIZONTAL_LTR,
                expected = "A B C",
            ),
            Case(
                name = "overlapping vertical regions retain top-to-bottom order",
                blocks = listOf(
                    mangaBlock("下", 700, 180, 800, 330),
                    mangaBlock("上", 700, 80, 800, 230),
                ),
                orientation = TextOrientation.VERTICAL_RTL,
                expected = "上 下",
            ),
            Case(
                name = "empty page remains empty",
                blocks = emptyList(),
                orientation = TextOrientation.VERTICAL_RTL,
                expected = "",
            ),
        )

        cases.forEach { case ->
            val ordered = sortTextBlocksForReading(case.blocks, case.orientation)
            val units = planPageTranslationUnits(
                blocks = ordered,
                presentation = RenderMode.FLOATING_WINDOW,
                mergeAdjacentBlocks = true,
                mergeStrength = MergeStrength.ALL,
            )
            assertEquals(case.name, case.expected, units.singleOrNull()?.sourceText.orEmpty())
        }
    }

    @Test
    fun rowUpdates_tableDriven_mapOnlyToTheOwningOcrBlock() {
        data class Case(val blockIndex: Int, val translation: String)

        listOf(
            Case(0, "译文"),
            Case(3, "multi\nline"),
            Case(8, ""),
        ).forEach { case ->
            val updates = pageTranslationRowUpdates(
                translatedText = case.translation,
                unit = PageTranslationUnit(case.blockIndex, "source"),
            )
            assertEquals(case.toString(), 1, updates.size)
            assertEquals(case.toString(), case.blockIndex, updates.single().blockIndex)
            assertEquals(case.toString(), case.translation, updates.single().text)
        }
    }

    private fun block(
        text: String,
        offset: Int,
        granularity: TextRegionGranularity = TextRegionGranularity.UNKNOWN,
    ): TextBlock = TextBlock(
        text = text,
        boundingBox = rect(offset, 0, offset + 50, 100),
        regionGranularity = granularity,
    )

    private fun mangaBlock(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): TextBlock = TextBlock(
        text = text,
        boundingBox = rect(left, top, right, bottom),
        layoutOrientation = TextOrientation.VERTICAL_RTL,
        regionGranularity = TextRegionGranularity.BUBBLE,
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
