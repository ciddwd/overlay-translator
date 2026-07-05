package com.gameocr.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsCoverageAuditTest {

    @Test
    fun settingsFields_areCoveredByCrashSnapshotAndRepositoryPersistence() {
        val settingsSource = sourceFile("src/main/java/com/gameocr/app/data/Settings.kt").readText()
        val fields = dataClassFields(settingsSource, "Settings")
        val crashSource = sourceFile("src/main/java/com/gameocr/app/data/CrashRecorder.kt").readText()
        val repositorySource = sourceFile("src/main/java/com/gameocr/app/data/SettingsRepository.kt").readText()

        data class Case(
            val name: String,
            val source: String,
            val patternForField: (String) -> Regex,
        )

        val cases = listOf(
            Case(
                name = "CrashRecorder.formatSettings",
                source = crashSource,
                patternForField = { field -> Regex("\"${Regex.escape(field)}\"") },
            ),
            Case(
                name = "SettingsRepository",
                source = repositorySource,
                patternForField = ::wordPattern,
            ),
        )

        cases.forEach { case ->
            val missing = fields.filterNot { field ->
                case.patternForField(field).containsMatchIn(case.source)
            }
            assertTrue("${case.name} missing Settings fields: $missing", missing.isEmpty())
        }
    }

    @Test
    fun translationPresetFields_areCoveredByApplyFromSettingsAndHash() {
        val settingsSource = sourceFile("src/main/java/com/gameocr/app/data/Settings.kt").readText()
        val presetFields = dataClassFields(settingsSource, "TranslationPreset")
            .filterNot { it in setOf("id", "name", "shortName", "settingsHash") }

        data class Case(
            val name: String,
            val source: String,
        )

        val cases = listOf(
            Case(
                name = "TranslationPreset.applyTo",
                source = slice(
                    settingsSource,
                    startMarker = "fun applyTo(settings: Settings): Settings",
                    endMarker = "object TranslationPresetCatalog",
                ),
            ),
            Case(
                name = "TranslationPresetCatalog.fromSettings",
                source = slice(
                    settingsSource,
                    startMarker = "fun fromSettings(",
                    endMarker = "fun matchesSettings",
                ),
            ),
            Case(
                name = "TranslationPresetCatalog.settingsHash",
                source = slice(
                    settingsSource,
                    startMarker = "private fun settingsHash",
                    endMarker = "private fun sha256",
                ),
            ),
        )

        cases.forEach { case ->
            val missing = presetFields.filterNot { field ->
                wordPattern(field).containsMatchIn(case.source)
            }
            assertTrue("${case.name} missing TranslationPreset fields: $missing", missing.isEmpty())
        }
    }

    @Test
    fun settingsScreenPresetSnapshot_includesImmediatePresetOnlySettings() {
        val settingsScreenSource = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val snapshotSource = slice(
            settingsScreenSource,
            startMarker = "fun buildTranslationPresetSnapshot()",
            endMarker = "fun currentTranslationPresetHash()",
        )

        val fields = listOf(
            "customBorderStyle",
            "overlayFontFileName",
            "overlayFontDisplayName",
            "dictionaryPrompt",
            "paddleModelVersion",
            "textOrientationAutoDetect",
            "manualTextOrientation",
            "dbnetProbThresh",
            "dbnetBoxScoreThresh",
            "dbnetUnclipRatio",
            "mangaOcrDbnetUnclipRatio",
            "bubbleClusterGap",
        )

        val missing = fields.filterNot { field ->
            Regex("""\b${Regex.escape(field)}\s=""").containsMatchIn(snapshotSource)
        }
        assertTrue("buildTranslationPresetSnapshot missing fields: $missing", missing.isEmpty())
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")

    private fun dataClassFields(source: String, className: String): List<String> {
        val marker = "data class $className"
        val classStart = source.indexOf(marker)
        require(classStart >= 0) { "Missing $marker" }
        val openParen = source.indexOf('(', classStart)
        require(openParen >= 0) { "Missing opening parenthesis for $marker" }
        val closeParen = matchingParenIndex(source, openParen)
        val constructor = source.substring(openParen + 1, closeParen)
        return Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")
            .findAll(constructor)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun matchingParenIndex(source: String, openParen: Int): Int {
        var depth = 0
        for (index in openParen until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        error("No matching parenthesis at $openParen")
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing end marker after $startMarker: $endMarker" }
        return source.substring(start, end)
    }

    private fun wordPattern(field: String): Regex =
        Regex("""\b${Regex.escape(field)}\b""")
}
