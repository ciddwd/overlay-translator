package com.gameocr.app.download

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `preset downloads split into distinct single model requests`() {
        data class Case(
            val name: String,
            val input: List<ModelDownloadSpec>,
            val expected: List<List<ModelDownloadSpec>>,
        )

        val sakura = ModelDownloadSpec(ModelDownloadType.LLM, "SAKURA_1_5B_Q4")
        val paddle = ModelDownloadSpec(ModelDownloadType.PADDLE, "V5_MOBILE")
        val manga = ModelDownloadSpec(ModelDownloadType.MANGA_OCR)
        listOf(
            Case("empty request", emptyList(), emptyList()),
            Case("single model", listOf(sakura), listOf(listOf(sakura))),
            Case(
                "three models become independent requests",
                listOf(sakura, paddle, manga),
                listOf(listOf(sakura), listOf(paddle), listOf(manga)),
            ),
            Case(
                "duplicate model is downloaded once",
                listOf(sakura, paddle, sakura),
                listOf(listOf(sakura), listOf(paddle)),
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, splitModelDownloadRequests(case.input))
        }
    }

    @Test
    fun `independent downloads keep running after one model fails`() = runBlocking {
        data class Case(
            val name: String,
            val failing: Set<String>,
            val expectedFailures: List<String>,
        )

        val items = listOf("sakura", "paddle", "manga")
        listOf(
            Case("all succeed", emptySet(), emptyList()),
            Case("Sakura failure does not block OCR models", setOf("sakura"), listOf("sakura")),
            Case("multiple failures are collected", setOf("sakura", "manga"), listOf("sakura", "manga")),
        ).forEach { case ->
            val attempted = mutableListOf<String>()
            val failures = runModelDownloadsIndependently(items) { item ->
                attempted += item
                if (item in case.failing) error("$item failed")
            }
            assertEquals(case.name, items, attempted)
            assertEquals(case.name, case.expectedFailures, failures.map { it.item })
        }
    }

    @Test
    fun `latest unresolved terminal failure is selected per model request`() {
        val sakura = ModelDownloadSpec(ModelDownloadType.LLM, "SAKURA_1_5B_Q4")
        val paddle = ModelDownloadSpec(ModelDownloadType.PADDLE, "V5_MOBILE")
        val manga = ModelDownloadSpec(ModelDownloadType.MANGA_OCR)
        fun record(
            specs: List<ModelDownloadSpec>,
            succeeded: Boolean,
            finishedAt: Long,
        ) = ModelDownloadTerminalRecord(
            specs = specs,
            succeeded = succeeded,
            status = if (succeeded) "done" else "failed",
            error = if (succeeded) "" else "HTTP 504",
            file = "",
            downloaded = 0,
            total = -1,
            finishedAt = finishedAt,
            ownerPresetId = null,
        )

        data class Case(
            val name: String,
            val records: List<ModelDownloadTerminalRecord>,
            val expectedSpecs: List<ModelDownloadSpec>?,
            val activeRequestKeys: Set<String> = emptySet(),
        )
        listOf(
            Case("no terminal state", emptyList(), null),
            Case("single failure remains visible", listOf(record(listOf(sakura), false, 10)), listOf(sakura)),
            Case(
                "later success resolves the same failure",
                listOf(record(listOf(sakura), false, 10), record(listOf(sakura), true, 20)),
                null,
            ),
            Case(
                "later failure replaces an earlier success",
                listOf(record(listOf(sakura), true, 10), record(listOf(sakura), false, 20)),
                listOf(sakura),
            ),
            Case(
                "success of another model does not hide Sakura failure",
                listOf(record(listOf(sakura), false, 10), record(listOf(paddle), true, 20)),
                listOf(sakura),
            ),
            Case(
                "most recent unresolved failure is shown",
                listOf(record(listOf(sakura), false, 10), record(listOf(paddle), false, 20)),
                listOf(paddle),
            ),
            Case(
                name = "individual successes resolve an older batch failure",
                records = listOf(
                    record(listOf(sakura, paddle, manga), false, 10),
                    record(listOf(sakura), true, 20),
                    record(listOf(paddle), true, 21),
                    record(listOf(manga), true, 22),
                ),
                expectedSpecs = null,
            ),
            Case(
                name = "partially resolved batch failure only lists missing models",
                records = listOf(
                    record(listOf(sakura, paddle, manga), false, 10),
                    record(listOf(paddle), true, 20),
                ),
                expectedSpecs = listOf(sakura, manga),
            ),
            Case(
                name = "later batch success resolves an older individual failure",
                records = listOf(
                    record(listOf(sakura), false, 10),
                    record(listOf(sakura, paddle, manga), true, 20),
                ),
                expectedSpecs = null,
            ),
            Case(
                name = "active retry hides the stale failure for the same model",
                records = listOf(
                    record(listOf(sakura), false, 10),
                    record(listOf(paddle), false, 20),
                ),
                expectedSpecs = listOf(sakura),
                activeRequestKeys = setOf(ModelDownloadWorkPolicy.requestKey(listOf(paddle))),
            ),
            Case(
                name = "active retries hide all matching stale failures",
                records = listOf(
                    record(listOf(sakura), false, 10),
                    record(listOf(paddle), false, 20),
                ),
                expectedSpecs = null,
                activeRequestKeys = setOf(
                    ModelDownloadWorkPolicy.requestKey(listOf(sakura)),
                    ModelDownloadWorkPolicy.requestKey(listOf(paddle)),
                ),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedSpecs,
                latestUnresolvedModelDownloadFailure(
                    records = case.records,
                    activeRequestKeys = case.activeRequestKeys,
                )?.specs,
            )
        }
    }
}
