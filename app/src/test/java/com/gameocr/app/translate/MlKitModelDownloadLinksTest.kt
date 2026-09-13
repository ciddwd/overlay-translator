package com.gameocr.app.translate

import org.junit.Assert.*
import org.junit.Test

class MlKitModelDownloadLinksTest {
    // Independent oracle: all PKG_HIGH keys from Google's translate:17.0.3
    // res/raw/translate_models_metadata.json. Every one is v5/r29 and was HEAD-verified.
    private val sdkPackages = """
        af_en ar_en be_en bg_en bn_en ca_en cs_en cy_en da_en de_en el_en
        en_eo en_es en_et en_fa en_fi en_fr en_ga en_gl en_gu en_hi en_hr en_ht
        en_hu en_id en_is en_it en_iw en_ja en_ka en_kn en_ko en_lt en_lv en_mk
        en_mr en_ms en_mt en_nl en_no en_pl en_pt en_ro en_ru en_sk en_sl en_sq
        en_sv en_sw en_ta en_te en_th en_tl en_tr en_uk en_ur en_vi en_zh
    """.trim().split(Regex("\\s+"))
    private val urls = sdkPackages.associate { packageName ->
        val code = packageName.split('_').single { it != "en" }.let { if (it == "iw") "he" else it }
        code to "https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r29/$packageName.zip"
    }

    @Test
    fun catalog_tableDriven_everySupportedDownloadableLanguageHasItsOwnVerifiedLink() {
        assertEquals(58, urls.size)
        assertEquals(MlKitLanguagePolicy.supportedLanguageTags - "en", urls.keys)
        urls.forEach { (language, url) ->
            assertEquals("$language source", url, MlKitModelDownloadLinks.forPair(language to "en", setOf(language)))
            assertEquals("$language target", url, MlKitModelDownloadLinks.forPair("en" to language, setOf(language)))
        }
        assertNull("English has no separate downloadable package", MlKitModelDownloadLinks.forPair("en" to "en", setOf("en")))
    }

    @Test
    fun allDirections_tableDriven_missingSourceMissingTargetBothAndNeither() {
        val languages = MlKitLanguagePolicy.supportedLanguageTags
        for (source in languages) for (target in languages) {
            val required = if (source == target) emptyList() else listOf(source, target).filter { it != "en" }
            listOf(emptySet(), setOf(source), setOf(target), setOf(source, target)).forEach { missing ->
                val expected = required.firstOrNull { it in missing }?.let(urls::get)
                assertEquals("$source -> $target missing=$missing", expected,
                    MlKitModelDownloadLinks.forPair(source to target, missing))
            }
        }
    }

    @Test
    fun aliases_tableDriven_languageRegionsAndSdkLegacyCodes() {
        data class Case(val tag: String, val missing: String, val language: String)
        listOf(
            Case("be-BY", "be", "be"),
            Case("ar_SA", "ar", "ar"),
            Case("he-IL", "he", "he"),
            Case("iw", "iw", "he"),
            Case("fil", "tl", "tl"),
            Case("nb", "no", "no"),
            Case("nn", "no", "no"),
            Case("zh-Hant", "zh-TW", "zh"),
            Case(" en-US ", "en", "en"),
        ).forEach { case ->
            assertEquals(case.toString(), urls[case.language],
                MlKitModelDownloadLinks.forPair(case.tag to "en", setOf(case.missing)))
        }
    }

    @Test
    fun invalidInputs_tableDriven_neverManufactureAnUnrelatedModelLink() {
        listOf("", "auto", "xx", "../be", "https://example.invalid").forEach { tag ->
            assertNull(tag, MlKitModelDownloadLinks.forPair(tag to "be", setOf("be")))
            assertNull(tag, MlKitModelDownloadLinks.forPair("be" to tag, setOf("be")))
        }
        assertNull(MlKitModelDownloadLinks.forPair("ja" to "be", setOf("ar")))
        assertNull(MlKitModelDownloadLinks.forPair("ja" to "be", emptySet()))
    }
}
