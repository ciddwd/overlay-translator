package com.gameocr.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadSpecTest {

    @Test
    fun `valid specs round trip`() {
        val cases = listOf(
            ModelDownloadSpec(ModelDownloadType.LLM, "SAKURA_1_5B_Q4"),
            ModelDownloadSpec(ModelDownloadType.LLM, "HY_MT2_1_8B_Q4_K_M"),
            ModelDownloadSpec(ModelDownloadType.PADDLE, "V5_MOBILE"),
            ModelDownloadSpec(ModelDownloadType.PADDLE, "V6_SMALL"),
            ModelDownloadSpec(ModelDownloadType.MANGA_OCR),
            ModelDownloadSpec(ModelDownloadType.ORIENTATION),
        )

        cases.forEach { spec ->
            assertEquals(spec.encode(), spec, ModelDownloadSpec.decode(spec.encode()))
        }
        assertEquals(cases, ModelDownloadSpec.decodeAll(cases.map { it.encode() }.toTypedArray()))
    }

    @Test
    fun `invalid encoded specs are rejected`() {
        val cases = listOf(
            "",
            "UNKNOWN|x",
            "LLM|",
            "PADDLE|",
            "LLM",
            "PADDLE",
        )

        cases.forEach { encoded ->
            assertNull(encoded, ModelDownloadSpec.decode(encoded))
        }
        assertNull(ModelDownloadSpec.decodeAll(emptyArray()))
    }

    @Test
    fun `retry policy covers all attempts`() {
        val cases = listOf(
            0 to true,
            1 to true,
            2 to false,
            3 to false,
        )
        cases.forEach { (attempt, expected) ->
            assertEquals("attempt=$attempt", expected, ModelDownloadWorkPolicy.shouldRetry(attempt))
        }
    }

    @Test
    fun `unique work name is stable and batch specific`() {
        val single = listOf(ModelDownloadSpec(ModelDownloadType.LLM, "SAKURA_1_5B_Q4"))
        val batch = single + ModelDownloadSpec(ModelDownloadType.MANGA_OCR)

        assertEquals(ModelDownloadWorkPolicy.uniqueWorkName(single), ModelDownloadWorkPolicy.uniqueWorkName(single))
        assertTrue(ModelDownloadWorkPolicy.uniqueWorkName(single) != ModelDownloadWorkPolicy.uniqueWorkName(batch))
    }

    @Test
    fun `owner preset tag round trip is table driven`() {
        data class Case(
            val name: String,
            val tags: Set<String>,
            val expected: String?,
        )

        val cases = listOf(
            Case("no owner tag", setOf(ModelDownloadWorkPolicy.WORK_TAG), null),
            Case(
                "built in preset owner",
                setOf(ModelDownloadWorkPolicy.ownerTag("builtin_manga_ja_zh")),
                "builtin_manga_ja_zh",
            ),
            Case(
                "custom preset owner",
                setOf("unrelated", ModelDownloadWorkPolicy.ownerTag("custom:42")),
                "custom:42",
            ),
            Case("blank owner is ignored", setOf(ModelDownloadWorkPolicy.ownerTag("")), null),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, ModelDownloadWorkPolicy.ownerPresetId(case.tags))
        }
    }
}
