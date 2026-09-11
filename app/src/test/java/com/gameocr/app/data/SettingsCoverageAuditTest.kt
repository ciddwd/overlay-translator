package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsCoverageAuditTest {

    @Test
    fun settingsFields_areClassifiedByPolicyAndRepositoryPersistence() {
        val settingsSource = sourceFile("src/main/java/com/gameocr/app/data/Settings.kt").readText()
        val fields = dataClassFields(settingsSource, "Settings")
        val repositorySource = sourceFile("src/main/java/com/gameocr/app/data/SettingsRepository.kt").readText()
        assertEquals(
            "SettingsFieldPolicy must classify every constructor field exactly once and in order",
            fields,
            SettingsFieldPolicy.rules.map(SettingsFieldRule::name),
        )
        val missingPersistence = fields.filterNot { field -> wordPattern(field).containsMatchIn(repositorySource) }
        assertTrue("SettingsRepository missing Settings fields: $missingPersistence", missingPersistence.isEmpty())
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
            "paddleDetectionProfile",
            "textOrientationAutoDetect",
            "manualTextOrientation",
            "translationOutputFollowRecognition",
            "translationOutputLayout",
            "translationOutputDirection",
            "translationGlossaryEnabled",
            "sendAppNameToTranslator",
            "dbnetProbThresh",
            "dbnetBoxScoreThresh",
            "dbnetUnclipRatio",
            "mangaOcrDbnetUnclipRatio",
            "bubbleClusterGap",
            "mangaOcrCropPaddingPx",
        )

        val missing = fields.filterNot { field ->
            Regex("""\b${Regex.escape(field)}\s=""").containsMatchIn(snapshotSource)
        }
        assertTrue("buildTranslationPresetSnapshot missing fields: $missing", missing.isEmpty())
    }

    @Test
    fun settingsScreenTransferSnapshot_includesImmediatePortableSettings() {
        val settingsScreenSource = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val snapshotSource = slice(
            settingsScreenSource,
            startMarker = "fun buildSettingsTransferSnapshot()",
            endMarker = "fun currentTranslationPresetHash()",
        )
        val exportSource = slice(
            settingsScreenSource,
            startMarker = "onExport = {",
            endMarker = "onImport = {",
        )

        val fields = listOf("foregroundAppDetectionMode")
        val missing = fields.filterNot { field ->
            Regex("""\b${Regex.escape(field)}\s*=""").containsMatchIn(snapshotSource)
        }
        assertTrue("buildSettingsTransferSnapshot missing fields: $missing", missing.isEmpty())
        assertTrue(
            "buildSnapshot must overlay UI drafts on the complete persisted Settings value",
            settingsScreenSource.contains("fun buildSnapshot(): Settings = (initialSettings ?: Settings()).copy("),
        )
        assertTrue(
            "buildSnapshot must not start from Settings defaults",
            !settingsScreenSource.contains("fun buildSnapshot(): Settings = Settings().copy("),
        )
        assertTrue(
            "initial snapshot must retain the complete repository Settings value",
            settingsScreenSource.contains("initialSettings = s"),
        )
        assertTrue(
            "Settings export must use buildSettingsTransferSnapshot",
            exportSource.contains("pendingSettingsExport = buildSettingsTransferSnapshot()"),
        )
    }

    /**
     * `buildSnapshot()` 只覆盖 UI 上真正编辑的表单字段，`save()` 只写入同一批字段。两份清单必须
     * 逐字段对齐：快照里有、`save()` 里没有 ⇒ 编辑被静默丢弃（dirty 由快照重置，UI 报告"已保存"，
     * 而持久值从未改变）；反过来则说明有字段带着陈旧值落盘。别用"某个字段名在文件里出现过"这种
     * 弱断言 —— 两侧都各自列了一百多个字段，只有逐字段集合比对才能发现单边漏改。
     */
    @Test
    fun buildSnapshotFields_andSaveFields_coverEachOther() {
        val settingsScreenSource = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val viewModelSource = sourceFile("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()

        val snapshotFields = copyArgumentNames(
            settingsScreenSource,
            "fun buildSnapshot(): Settings = (initialSettings ?: Settings()).copy(",
        )
        // 锚点必须落在 `save()` 这个整表单函数上。ViewModel 里另有一批单字段 setter 也写
        // `repo.update { it.copy(x = ...) }`，若锚点漂到它们上面，比对会退化成"两个小集合相等"而假绿。
        val savedFields = copyArgumentNames(viewModelSource, "suspend fun save(")

        // 两个集合都必须确实是**整表单**；否则下面两条断言比的是无关字段集，会静默通过。
        listOf("snapshot" to snapshotFields, "save" to savedFields).forEach { (label, fields) ->
            assertTrue(
                "the $label anchor resolved the wrong `.copy(` — this test would then compare two " +
                    "unrelated field sets and pass: ${fields.size} fields",
                fields.contains(WHOLE_FORM_SENTINEL),
            )
        }

        val overlaidButNotSaved = snapshotFields - savedFields
        assertTrue(
            "buildSnapshot overlays fields that save() never writes, so the edit is silently dropped: " +
                overlaidButNotSaved,
            overlaidButNotSaved.isEmpty(),
        )

        val savedButNotOverlaid = savedFields - snapshotFields
        assertEquals(
            "save() writes fields the snapshot never overlays; add each to SAVE_ONLY_FIELDS with a reason",
            SAVE_ONLY_FIELDS,
            savedButNotOverlaid,
        )
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

    /** 取出 `marker` 之后第一个 `.copy(...)` 的顶层具名实参名。按括号深度切分，跨行与 CRLF 都不影响。 */
    private fun copyArgumentNames(source: String, marker: String): Set<String> {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "Missing marker: $marker" }
        val copyIndex = source.indexOf(".copy(", markerIndex)
        require(copyIndex >= 0) { "Missing .copy( after: $marker" }
        val openParen = copyIndex + ".copy".length
        val body = source.substring(openParen + 1, matchingParenIndex(source, openParen))
        return topLevelArgumentNames(body)
    }

    private fun topLevelArgumentNames(body: String): Set<String> {
        val names = linkedSetOf<String>()
        val argument = StringBuilder()
        var depth = 0
        var index = 0
        while (index < body.length) {
            val char = body[index]
            // 注释里的括号不该参与配对。
            if (char == '/' && index + 1 < body.length && body[index + 1] == '/') {
                while (index < body.length && body[index] != '\n') index++
                continue
            }
            when (char) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    argumentName(argument.toString())?.let(names::add)
                    argument.clear()
                    index++
                    continue
                }
            }
            argument.append(char)
            index++
        }
        argumentName(argument.toString())?.let(names::add)
        return names
    }

    private fun argumentName(argument: String): String? =
        Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=""")
            .find(argument)
            ?.groupValues
            ?.get(1)

    private companion object {
        /**
         * 只被 `save()` 写入、快照有意不覆盖的字段。`activeTranslationPresetId` 在保存时由
         * `currentMatchingTranslationPresetId()` 现算（见 SettingsScreen 的 doSave），不是表单草稿，
         * 所以快照不覆盖它是对的。
         */
        val SAVE_ONLY_FIELDS = setOf("activeTranslationPresetId")

        /** 整表单 copy 必然包含的字段，用来证明锚点没有落到某个单字段 setter 上。 */
        const val WHOLE_FORM_SENTINEL = "baseUrl"
    }
}
