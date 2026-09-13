package com.gameocr.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutoOcrLanguageListPolicyTest {
    private fun codes(settings: AutoOcrSettings) = AutoOcrLanguageListPolicy.visible(settings)
        .map { AutoOcrSettings.languageKey(it.code) }

    @Test fun fourDefaultsFollowTheSharedLanguageCatalog() {
        assertEquals(listOf("ko", "ja", "en", "zh"), codes(AutoOcrSettings()))
        val reversed = AutoOcrLanguageListPolicy.visible(AutoOcrSettings(), Languages.ALL.reversed())
            .map { AutoOcrSettings.languageKey(it.code) }
        assertEquals(listOf("zh", "en", "ja", "ko"), reversed)
    }

    @Test fun addLanguagesDeduplicatesAliasesAndPersistsUnconfiguredRows_tableDriven() {
        for (code in listOf("fr", "ar", "nb", "no")) {
            val added = AutoOcrLanguageListPolicy.add(AutoOcrSettings(), code)
            assertEquals(code, 5, codes(added).size)
            assertTrue(code, AutoOcrSettings.languageKey(code) in codes(added))
            assertEquals(added, AutoOcrLanguageListPolicy.add(added, code))
            val saved = Json.decodeFromString<AutoOcrSettings>(Json.encodeToString(AutoOcrSettings.serializer(), added))
            assertEquals(codes(added), codes(saved))
            assertTrue(added.routes.isEmpty())
        }
        for (code in listOf("auto", "und", "", "unknown-code", "zh-TW", "ja-JP", "en", "ko")) {
            assertEquals(code, AutoOcrSettings(), AutoOcrLanguageListPolicy.add(AutoOcrSettings(), code))
        }
    }

    @Test fun addPickerExcludesVisibleLanguagesAndPreservesCatalogOrder() {
        val configured = AutoOcrLanguageListPolicy.add(AutoOcrSettings(), "fr")
        val addable = AutoOcrLanguageListPolicy.addable(configured)
        assertFalse(addable.any { AutoOcrSettings.languageKey(it.code) in setOf("auto", "zh", "ja", "en", "ko", "fr") })
        val indices = addable.map { Languages.ALL.indexOf(it) }
        assertEquals(indices.sorted(), indices)
    }

    @Test fun routesNeverImplicitlyAddLanguageRows_tableDriven() {
        for (code in listOf("fr", "ar", "no")) {
            val settings = AutoOcrSettings(routes = mapOf(code to AutoOcrRoute(OcrEngineKind.LUNA_OCR)))
            assertEquals(code, listOf("ko", "ja", "en", "zh"), codes(settings))
            assertTrue(code, AutoOcrLanguageListPolicy.addable(settings).any {
                AutoOcrSettings.languageKey(it.code) == code
            })
            val added = AutoOcrLanguageListPolicy.add(settings, code)
            assertEquals(code, 5, codes(added).size)
            assertTrue(code, code in codes(added))
        }
    }

    @Test fun addingAndRemovingEveryAdditionalLanguageKeepsPickerAndRowsConsistent() {
        val initial = AutoOcrSettings()
        val candidates = AutoOcrLanguageListPolicy.addable(initial)
        var draft = initial
        for (language in candidates) {
            val beforeSize = codes(draft).size
            draft = AutoOcrLanguageListPolicy.add(draft, language.code)
            assertEquals(language.code, beforeSize + 1, codes(draft).size)
            assertFalse(language.code, AutoOcrLanguageListPolicy.addable(draft).any { it.code == language.code })
        }
        assertTrue(AutoOcrLanguageListPolicy.addable(draft).isEmpty())
        val saved = Json.decodeFromString<AutoOcrSettings>(Json.encodeToString(AutoOcrSettings.serializer(), draft))
        assertEquals(codes(draft), codes(saved))
        val catalogOrder = Languages.ALL.filterNot { it.code == "auto" }
            .map { AutoOcrSettings.languageKey(it.code) }.distinct()
        assertEquals(catalogOrder, codes(saved))
        for (language in candidates.reversed()) {
            draft = AutoOcrLanguageListPolicy.remove(draft, language.code)
            assertTrue(language.code, AutoOcrLanguageListPolicy.addable(draft).any { it.code == language.code })
        }
        assertEquals(initial, draft)
        assertEquals(codes(initial), codes(draft))
    }

    @Test fun onlyExplicitlyAddedRowsCanBeRemoved() {
        val route = AutoOcrRoute(OcrEngineKind.LUNA_OCR)
        val configured = AutoOcrSettings(routes = mapOf("fr" to route), additionalLanguages = listOf("fr"))
        assertEquals(listOf("fr", "ko", "ja", "en", "zh"), codes(configured))
        assertEquals(route, configured.routeFor("fr"))
        val removed = AutoOcrLanguageListPolicy.remove(configured, "fr-FR")
        assertEquals(4, codes(removed).size)
        assertFalse("fr" in removed.routes)
        assertTrue(removed.additionalLanguages.isEmpty())
        for (code in listOf("zh-TW", "ja", "en-US", "ko")) {
            assertEquals(configured, AutoOcrLanguageListPolicy.remove(configured, code))
        }
        assertEquals(AutoOcrSettings.defaultRoute("fr"), removed.routeFor("fr"))
    }

    @Test fun confirmedRemovalPreservesOtherLanguagesAndTheirLatestRoutes_tableDriven() {
        val route = AutoOcrRoute(OcrEngineKind.LUNA_OCR)
        val otherRoute = AutoOcrRoute(OcrEngineKind.UMI_OCR)
        val latest = AutoOcrSettings(
            routes = mapOf("az" to route, "fr" to otherRoute, "ja" to route),
            additionalLanguages = listOf("az", "fr"),
        ).normalized()
        val expected = latest.copy(routes = latest.routes - "az", additionalLanguages = listOf("fr"))
        data class Case(val code: String, val expected: AutoOcrSettings)
        listOf(
            Case("az", expected),
            Case("az-AZ", expected),
            Case("ja", latest),
            Case("en-US", latest),
            Case("zh-TW", latest),
            Case("ko", latest),
            Case("de", latest),
        ).forEach { case ->
            assertEquals(case.code, case.expected, AutoOcrLanguageListPolicy.remove(latest, case.code))
        }
        assertEquals("repeated removal is harmless", expected, AutoOcrLanguageListPolicy.remove(expected, "az"))
        assertEquals("removed language can be added again", latest.additionalLanguages.sorted(),
            AutoOcrLanguageListPolicy.add(expected, "az").additionalLanguages.sorted())
    }
}
