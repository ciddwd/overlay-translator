package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingWindowContentMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCorrectionEntryPointTest {
    @Test
    fun floatingSelection_mapsOnlyOneTranslationSegment_tableDriven() {
        val pairs = listOf(
            "Source A" to "Translation A",
            "Source B" to "Translation B",
        )
        val bothText = floatingWindowTextSegments(
            pairs,
            FloatingWindowContentMode.SRC_AND_DST,
        ).joinToString("") { it.text }
        val translationOnlyText = floatingWindowTextSegments(
            pairs,
            FloatingWindowContentMode.DST_ONLY,
        ).joinToString("") { it.text }

        data class Case(
            val name: String,
            val mode: FloatingWindowContentMode,
            val start: Int,
            val end: Int,
            val expectedIndex: Int?,
        )

        val firstTranslation = bothText.indexOf("Translation A")
        val secondTranslation = bothText.indexOf("Translation B")
        val firstTranslationOnly = translationOnlyText.indexOf("Translation A")
        listOf(
            Case(
                "first translation",
                FloatingWindowContentMode.SRC_AND_DST,
                firstTranslation,
                firstTranslation + "Translation A".length,
                0,
            ),
            Case(
                "second translation with reversed handles",
                FloatingWindowContentMode.SRC_AND_DST,
                secondTranslation + 5,
                secondTranslation,
                1,
            ),
            Case(
                "source selection is not correctable",
                FloatingWindowContentMode.SRC_AND_DST,
                1,
                5,
                null,
            ),
            Case(
                "selection crossing two translations is rejected",
                FloatingWindowContentMode.SRC_AND_DST,
                firstTranslation,
                secondTranslation + 3,
                null,
            ),
            Case(
                "translation-only mode",
                FloatingWindowContentMode.DST_ONLY,
                firstTranslationOnly,
                firstTranslationOnly + 4,
                0,
            ),
            Case("collapsed selection", FloatingWindowContentMode.DST_ONLY, 2, 2, null),
            Case("negative selection", FloatingWindowContentMode.DST_ONLY, -1, 2, null),
            Case(
                "selection beyond rendered content",
                FloatingWindowContentMode.DST_ONLY,
                0,
                translationOnlyText.length + 1,
                null,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedIndex,
                floatingWindowTranslationIndexForSelection(
                    pairs = pairs,
                    mode = case.mode,
                    selectionStart = case.start,
                    selectionEnd = case.end,
                ),
            )
        }
    }

    @Test
    fun correctionActionAvailability_requiresFinalActionablePair_tableDriven() {
        data class Case(
            val name: String,
            val isFinal: Boolean,
            val source: String?,
            val translation: String?,
            val expected: Boolean,
        )

        listOf(
            Case("final pair", true, "Source", "Translation", true),
            Case("streaming partial", false, "Source", "Partial translation", false),
            Case("blank source", true, " ", "Translation", false),
            Case("blank translation", true, "Source", "", false),
            Case("loading placeholder", true, "Source", "...", false),
            Case("missing source", true, null, "Translation", false),
            Case("missing translation", true, "Source", null, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                isTranslationCorrectionActionAvailable(
                    isFinal = case.isFinal,
                    source = case.source,
                    translation = case.translation,
                ),
            )
        }
    }

    @Test
    fun quickGlossaryDraft_tableDriven_isIndependentFromTranslationMemoryRules() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val source: String,
            val target: String,
            val expected: TranslationCorrectionGlossaryDraft?,
        )

        listOf(
            Case("disabled", false, "", "", null),
            Case(
                "trims a character name",
                true,
                "  アルトリア  ",
                "  阿尔托莉雅  ",
                TranslationCorrectionGlossaryDraft("アルトリア", "阿尔托莉雅"),
            ),
            Case(
                "accepts a long phrase without the old suggestion gate",
                true,
                "The Promised Place Beyond the Silver Horizon",
                "银色地平线彼端的约定之地",
                TranslationCorrectionGlossaryDraft(
                    "The Promised Place Beyond the Silver Horizon",
                    "银色地平线彼端的约定之地",
                ),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                buildTranslationCorrectionGlossaryDraft(
                    enabled = case.enabled,
                    sourceTerm = case.source,
                    targetTerm = case.target,
                ),
            )
        }

        listOf(
            "blank source" to ("" to "Translation"),
            "blank target" to ("Source" to " "),
        ).forEach { (name, pair) ->
            assertThrows(name, IllegalArgumentException::class.java) {
                buildTranslationCorrectionGlossaryDraft(
                    enabled = true,
                    sourceTerm = pair.first,
                    targetTerm = pair.second,
                )
            }
        }
        assertNull(buildTranslationCorrectionGlossaryDraft(false, "Source", "Translation"))
    }

    @Test
    fun correctionOverlay_exposesQuickGlossaryFields_tableDriven() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/overlay/TranslationCorrectionOverlay.kt"
        ).readText()
        data class Case(val name: String, val marker: String)

        listOf(
            Case("quick add is opt in", "checked = false"),
            Case("game-scoped label", "R.string.translation_correction_glossary_format"),
            Case("global fallback label", "R.string.translation_correction_glossary_global"),
            Case("separate source term field", "translation_correction_glossary_source_hint"),
            Case("separate target term field", "translation_correction_glossary_target_hint"),
            Case("fields expand on demand", "glossaryFields.visibility = if (checked)"),
            Case("draft carries independent term pair", "glossary = buildTranslationCorrectionGlossaryDraft("),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }

    @Test
    fun selectableTranslationModes_exposeTheSharedCorrectionAction_tableDriven() {
        data class Case(
            val name: String,
            val path: String,
            val markers: List<String>,
        )

        listOf(
            Case(
                name = "native selection action",
                path = "src/main/java/com/gameocr/app/overlay/SelectionSpeechAction.kt",
                markers = listOf(
                    "R.id.action_correct_translation",
                    "correctionAction()",
                ),
            ),
            Case(
                name = "floating window",
                path = "src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt",
                markers = listOf(
                    "R.id.action_correct_translation",
                    "selectionCorrectionAction()",
                ),
            ),
            Case(
                name = "positioned translation blocks",
                path = "src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
                markers = listOf(
                    "onTranslationCorrectionRequested",
                    "floatingWindowTranslationIndexForSelection(",
                    "correctionLabel = context.getString(R.string.translation_correction_action)",
                ),
            ),
            Case(
                name = "translation card",
                path = "src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
                markers = listOf(
                    "onCorrectTranslation",
                    "correctionAction =",
                ),
            ),
            Case(
                name = "block copy panel",
                path = "src/main/java/com/gameocr/app/overlay/TranslationBlockCopyOverlay.kt",
                markers = listOf(
                    "onCorrectTranslation",
                    "isTranslationCorrectionActionAvailable(",
                ),
            ),
        ).forEach { case ->
            val source = sourceFile(case.path).readText()
            case.markers.forEach { marker ->
                assertTrue("${case.name}: $marker", source.contains(marker))
            }
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
