package com.gameocr.app.ui

import com.gameocr.app.readSourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionsRedesignTest {
    private fun source(path: String) = File("src/main/java/com/gameocr/app/$path").readSourceText()

    @Test
    fun presetDetailLinesSplitIntoLabelAndValue() {
        data class Case(val line: String, val expected: Pair<String?, String>)
        listOf(
            Case("OCR: ML Kit Japanese", "OCR" to "ML Kit Japanese"),
            Case("译文：Sakura (JA→zh-CN)", "译文" to "Sakura (JA→zh-CN)"),
            Case("Languages: Japanese → Chinese: Simplified", "Languages" to "Japanese → Chinese: Simplified"),
            Case("No colon here", null to "No colon here"),
            Case(": leading colon", null to ": leading colon"),
        ).forEach { case -> assertEquals(case.line, case.expected, presetDetailLabelValue(case.line)) }
    }

    @Test
    fun presetsManageOpensSettingsAtThePresetSection() {
        val main = source("ui/MainScreen.kt")
        val activity = source("ui/MainActivity.kt")
        val settings = source("ui/SettingsScreen.kt")
        assertTrue(main.contains("onManage = onOpenPresetSettings"))
        assertTrue(activity.contains("settingsInitialSection = SETTINGS_SECTION_PRESETS"))
        assertTrue(activity.contains("initialSectionKey = settingsInitialSection"))
        assertTrue(activity.contains("onInitialSectionHandled = { settingsInitialSection = null }"))
        assertTrue(settings.contains("settingsSectionIndex(key)?.let { listState.scrollToItem(it) }"))
        assertTrue("the section key cannot drift", settings.contains("const val PRESETS = SETTINGS_SECTION_PRESETS"))
    }

    @Test
    fun compatibilityRowsShowDoneChipsOnlyForDetectableItems() {
        val main = source("ui/MainScreen.kt")
        val compat = main.substring(
            main.indexOf("ActionCard(title = stringResource(R.string.main_section_rom_guide), listStyle = true)"),
            main.indexOf("ActionCard(\n                title = stringResource(R.string.settings_section_about),"),
        )
        assertEquals("three rows", 3, Regex("""HomeListRow\(""").findAll(compat).count())
        val autoStart = compat.substringBefore("R.string.main_status_battery_whitelist")
        assertFalse("auto-start cannot be detected, so it never shows a done chip",
            autoStart.contains("HomeRowTrailing.Done"))
        assertTrue(compat.contains("HomeRowTrailing.Done(stringResource(R.string.main_chip_on))"))
        assertTrue(compat.contains("HomeRowTrailing.Done(stringResource(R.string.main_status_enabled))"))
        assertTrue("enabling shows a spinner", compat.contains("accessibilityEnabling -> HomeRowTrailing.Busy"))
        val row = source("ui/HomeRows.kt")
        assertTrue("done and busy rows are not tappable",
            row.contains("trailing !is HomeRowTrailing.Done && trailing != HomeRowTrailing.Busy"))
    }

    @Test
    fun aboutKeepsEveryItemInOrderAsRows() {
        val main = source("ui/MainScreen.kt")
        val about = main.substringAfter("private fun AboutContent(").substringBefore("private fun launchAppShare(")
        val order = listOf(
            "R.string.update_btn_check",
            "R.string.settings_about_github_label",
            "R.string.settings_about_user_guide",
            "R.string.settings_about_open_source_licenses",
            "R.string.settings_about_share_label",
            "R.string.settings_about_qq_group_label",
        ).map { about.indexOf(it) }
        assertTrue("every item is present", order.all { it >= 0 })
        assertEquals("items keep their order", order.sorted(), order)
        assertEquals("six rows", 6, Regex("""HomeListRow\(""").findAll(about).count())
        assertTrue("version moved into the card header",
            main.contains("titleTrailing = stringResource(R.string.settings_about_version_format, BuildConfig.VERSION_NAME)"))
        assertTrue("browser rows use the external icon", about.contains("HomeRowTrailing.External"))
    }
}
