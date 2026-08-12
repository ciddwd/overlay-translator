package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextRegionGranularity
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
        boundingBox = Rect(offset, 0, offset + 50, 100),
        regionGranularity = granularity,
    )
}
