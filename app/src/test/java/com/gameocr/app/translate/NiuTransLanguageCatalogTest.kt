package com.gameocr.app.translate

import com.gameocr.app.data.NiuTransMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NiuTransLanguageCatalogTest {
    @Test
    fun `official catalogs are complete and every canonical code round trips_tableDriven`() {
        data class Case(
            val mode: NiuTransMode,
            val expectedCount: Int,
            val codes: Set<String>,
        )

        listOf(
            Case(NiuTransMode.FLASH, 450, NiuTransLanguageCatalog.flashCodes),
            Case(NiuTransMode.PRO, 37, NiuTransLanguageCatalog.proCodes),
        ).forEach { case ->
            assertEquals(case.mode.name, case.expectedCount, case.codes.size)
            case.codes.forEach { code ->
                assertEquals("${case.mode}:$code source", code, NiuTransLanguageCatalog.mapSource(code, case.mode))
                assertEquals("${case.mode}:$code target", code, NiuTransLanguageCatalog.mapTarget(code, case.mode))
            }
        }
    }

    @Test
    fun `application language tags map to provider codes_tableDriven`() {
        data class Case(
            val name: String,
            val input: String,
            val mode: NiuTransMode,
            val source: String?,
            val target: String?,
        )

        listOf(
            Case("auto is source only", "auto", NiuTransMode.FLASH, "auto", null),
            Case("simplified Chinese", "zh-CN", NiuTransMode.FLASH, "zh", "zh"),
            Case("traditional Chinese", "zh-Hant", NiuTransMode.PRO, "cht", "cht"),
            Case("regional fallback", "fr-FR", NiuTransMode.PRO, "fr", "fr"),
            Case("Filipino alias", "tl", NiuTransMode.PRO, "fil", "fil"),
            Case("Japanese legacy alias", "jp", NiuTransMode.FLASH, "ja", "ja"),
            Case("Korean legacy alias", "kor", NiuTransMode.PRO, "ko", "ko"),
            Case("Flash preserves Brazilian Portuguese", "pt-BR", NiuTransMode.FLASH, "pt-BR", "pt-BR"),
            Case("Pro reduces Brazilian Portuguese", "pt-BR", NiuTransMode.PRO, "pt", "pt"),
            Case("unsupported by Pro", "sq", NiuTransMode.PRO, null, null),
            Case("blank", "  ", NiuTransMode.FLASH, null, null),
        ).forEach { case ->
            assertEquals(case.name, case.source, NiuTransLanguageCatalog.mapSource(case.input, case.mode))
            assertEquals(case.name, case.target, NiuTransLanguageCatalog.mapTarget(case.input, case.mode))
        }

        assertNull(NiuTransLanguageCatalog.mapTarget("auto", NiuTransMode.PRO))
    }
}
