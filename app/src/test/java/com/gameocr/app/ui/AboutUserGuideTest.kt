package com.gameocr.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutUserGuideTest {
    @Test fun labelsMatchApprovedCopy_tableDriven() {
        for ((directory, expected) in listOf("values" to "User Guide", "values-zh-rCN" to "使用手册")) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$directory/strings.xml"))
            val strings = document.getElementsByTagName("string")
            val matches = (0 until strings.length).map { strings.item(it) }
                .filter { it.attributes.getNamedItem("name").nodeValue == "settings_about_user_guide" }
            assertEquals(directory, 1, matches.size)
            assertEquals(directory, expected, matches.single().textContent)
        }
    }

    @Test fun guideUsesBrowserAndSitsBelowGitHub() {
        assertEquals("https://github.com/ciddwd/overlay-translator/wiki", USER_GUIDE_URL)
        val source = File("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        assertTrue(source.contains("Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_URL))"))
        val github = source.indexOf("R.string.settings_about_open_github")
        val guide = source.indexOf("R.string.settings_about_user_guide")
        val licenses = source.indexOf("R.string.settings_about_open_source_licenses")
        assertTrue(github < guide && guide < licenses)
    }
}
