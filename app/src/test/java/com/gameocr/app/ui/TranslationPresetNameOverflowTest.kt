package com.gameocr.app.ui

import com.gameocr.app.data.TranslationPresetCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPresetNameOverflowTest {

    @Test
    fun maxLines_varyByPresetTypeAndSurface() {
        data class Case(
            val name: String,
            val presetId: String,
            val expectedSettingsLines: Int,
            val expectedMainLines: Int,
        )

        listOf(
            Case(
                name = "system preset",
                presetId = TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH,
                expectedSettingsLines = 2,
                expectedMainLines = 1,
            ),
            Case(
                name = "custom preset keeps existing behavior",
                presetId = "custom-user-preset",
                expectedSettingsLines = Int.MAX_VALUE,
                expectedMainLines = 2,
            ),
            Case(
                name = "unsaved draft keeps existing behavior",
                presetId = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                expectedSettingsLines = Int.MAX_VALUE,
                expectedMainLines = 2,
            ),
        ).forEach { case ->
            assertEquals(
                "${case.name}: settings",
                case.expectedSettingsLines,
                settingsTranslationPresetNameMaxLines(case.presetId),
            )
            assertEquals(
                "${case.name}: main",
                case.expectedMainLines,
                mainTranslationPresetNameMaxLines(case.presetId),
            )
        }
    }

    @Test
    fun presetNameText_usesTheSurfacePolicyAndEllipsis() {
        data class Case(
            val name: String,
            val sourcePath: String,
            val blockStart: String,
            val blockEnd: String,
            val maxLinesMarker: String,
        )

        listOf(
            Case(
                name = "settings preset row",
                sourcePath = "src/main/java/com/gameocr/app/ui/SettingsScreen.kt",
                blockStart = "private fun TranslationPresetRow(",
                blockEnd = "internal fun settingsTranslationPresetNameMaxLines(",
                maxLinesMarker = "maxLines = settingsTranslationPresetNameMaxLines(preset.id)",
            ),
            Case(
                name = "main preset carousel",
                sourcePath = "src/main/java/com/gameocr/app/ui/MainScreen.kt",
                blockStart = "internal fun PresetCarousel(",
                blockEnd = "internal data class PresetCarouselPlans(",
                maxLinesMarker = "maxLines = mainTranslationPresetNameMaxLines(preset.id)",
            ),
        ).forEach { case ->
            val source = moduleFile(case.sourcePath).readText()
            val start = source.indexOf(case.blockStart)
            val end = source.indexOf(case.blockEnd, startIndex = start)
            assertTrue("${case.name}: block exists", start >= 0 && end > start)
            val block = source.substring(start, end)
            assertTrue(
                "${case.name}: uses its max-lines policy",
                block.contains(case.maxLinesMarker),
            )
            assertTrue(
                "${case.name}: uses ellipsis",
                block.contains("overflow = TextOverflow.Ellipsis"),
            )
        }
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
