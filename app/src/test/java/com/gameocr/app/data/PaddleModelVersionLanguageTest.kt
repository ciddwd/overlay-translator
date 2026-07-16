package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleModelVersionLanguageTest {

    @Test
    fun languageMetadata_tableDriven_matchesOfficialModelVariants() {
        data class Case(
            val version: PaddleModelVersion,
            val expectedCount: Int,
            val expectedJapanese: Boolean,
        )

        val cases = listOf(
            Case(PaddleModelVersion.V5_MOBILE, 4, true),
            Case(PaddleModelVersion.V6_TINY, 49, false),
            Case(PaddleModelVersion.V6_SMALL, 50, true),
            Case(PaddleModelVersion.V6_MEDIUM, 50, true),
        )

        cases.forEach { case ->
            assertEquals(case.version.name, case.expectedCount, case.version.languageCount)
            assertEquals(case.version.name, case.expectedJapanese, case.version.supportsJapanese)
        }
    }
}
