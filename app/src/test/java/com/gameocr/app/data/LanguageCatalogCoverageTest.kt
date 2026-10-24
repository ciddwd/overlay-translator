package com.gameocr.app.data

import com.gameocr.app.R
import com.gameocr.app.translate.MlKitLanguagePolicy
import com.gameocr.app.ui.mlKitLanguagePickerCodes
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class LanguageCatalogCoverageTest {
    @Test fun everyMlKitLanguageIsInCatalog() {
        val catalog = Languages.ALL.map { canonical(it.code) }.toSet()
        MlKitLanguagePolicy.supportedLanguageTags.forEach { tag ->
            assertTrue("Missing ML Kit language: $tag", tag in catalog)
        }
        assertEquals(Languages.ALL.size, Languages.ALL.map { it.code.lowercase(Locale.ROOT) }.toSet().size)
    }

    @Test fun addedLanguages_tableDriven_codesResourcesAndOrder() {
        data class Case(val code: String, val resource: Int, val before: String, val after: String)
        val codes = Languages.ALL.map { it.code }
        listOf(
            Case("kn", R.string.lang_kn, "cs", "xh"),
            Case("th", R.string.lang_th, "ta", "tr"),
            Case("vi", R.string.lang_vi, "en", "yue"),
        ).forEach { case ->
            assertEquals(case.code, case.resource, Languages.byCode(case.code).nameRes)
            assertEquals(case.code, Languages.byCode(case.code.uppercase(Locale.ROOT)).code)
            assertEquals(case.code, codes.indexOf(case.before) + 1, codes.indexOf(case.code))
            assertEquals(case.code, codes.indexOf(case.code) + 1, codes.indexOf(case.after))
            assertTrue(case.code, MlKitLanguagePolicy.isSupportedLanguageTag(case.code))
        }
        listOf("", "unknown").forEach { assertEquals(Languages.AUTO, Languages.byCode(it)) }
    }

    @Test fun bilingualLabels_tableDriven_existExactlyOnce() {
        val expected = mapOf(
            "values" to mapOf("kn" to "Kannada", "th" to "Thai", "vi" to "Vietnamese"),
            "values-zh-rCN" to mapOf("kn" to "卡纳达语", "th" to "泰语", "vi" to "越南语"),
        )
        expected.forEach { (directory, labels) ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$directory/strings.xml"))
            val nodes = document.getElementsByTagName("string")
            labels.forEach { (code, label) ->
                val matches = (0 until nodes.length).map { nodes.item(it) }
                    .filter { it.attributes.getNamedItem("name").nodeValue == "lang_$code" }
                assertEquals("$directory/$code", 1, matches.size)
                assertEquals("$directory/$code", label, matches.single().textContent)
            }
        }
    }

    @Test fun pickerAndAutoOcrList_tableDriven_addedLanguagesAreSelectable() {
        listOf("vi", "th", "kn").forEach { code ->
            assertTrue(code, code in mlKitLanguagePickerCodes)
            val initial = AutoOcrSettings()
            assertTrue(code, AutoOcrLanguageListPolicy.addable(initial).any { it.code == code })
            assertFalse(code, AutoOcrLanguageListPolicy.visible(initial).any { it.code == code })
            val added = AutoOcrLanguageListPolicy.add(initial, code)
            assertEquals(code, 1, AutoOcrLanguageListPolicy.visible(added).count { it.code == code })
            assertEquals(added, AutoOcrLanguageListPolicy.add(added, code))
            assertFalse(code, AutoOcrLanguageListPolicy.visible(
                AutoOcrLanguageListPolicy.remove(added, code)
            ).any { it.code == code })
        }
    }

    // Independent oracle for catalog aliases; do not derive expectations from the policy under test.
    private fun canonical(code: String): String = when (val core = code.lowercase(Locale.ROOT).substringBefore('-')) {
        "nb", "nn" -> "no"
        "fil" -> "tl"
        "iw" -> "he"
        else -> core
    }
}
