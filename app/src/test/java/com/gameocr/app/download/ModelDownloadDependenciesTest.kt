package com.gameocr.app.download

import com.gameocr.app.data.MangaOcrModelPolicy
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadDependenciesTest {
    private val manga = ModelDownloadSpec.mangaOcr()
    private val small = ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)
    private val korean = ModelDownloadSpec.paddle(PaddleModelVersion.V5_KOREAN)
    private val sakura = ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4)

    @Test
    fun dependencyExpansion_isOrderedDeduplicatedAndIdempotent() {
        data class Case(val input: List<ModelDownloadSpec>, val expected: List<ModelDownloadSpec>)
        listOf(
            Case(emptyList(), emptyList()),
            Case(listOf(korean), listOf(korean)),
            Case(listOf(manga), listOf(small, manga)),
            Case(listOf(small, manga), listOf(small, manga)),
            Case(listOf(manga, small, manga), listOf(small, manga)),
            Case(listOf(korean, manga), listOf(korean, small, manga)),
            Case(listOf(sakura, manga), listOf(sakura, small, manga)),
        ).forEach { case ->
            val result = ModelDownloadDependencies.expand(case.input)
            assertEquals(case.input.toString(), case.expected, result)
            assertEquals(result, ModelDownloadDependencies.expand(result))
            // Worker recovery from an older persisted Manga-only request also expands dependencies.
            val restored = ModelDownloadSpec.decodeAll(case.input.map { it.encode() }.toTypedArray()).orEmpty()
            assertEquals(result, ModelDownloadDependencies.expand(restored))
        }
        assertEquals(MangaOcrModelPolicy.recommendedDetectorVersion.name, small.variant)
    }

    @Test
    fun sharedDependencies_cannotBeScheduledAsSeparateConcurrentRequests() {
        listOf(
            listOf(manga),
            listOf(small, manga),
            listOf(manga, small),
            listOf(small, manga, small, manga),
        ).forEach { input ->
            assertEquals(input.toString(), listOf(listOf(small, manga)), splitModelDownloadRequests(input))
        }
        val requests = splitModelDownloadRequests(listOf(sakura, small, korean, manga))
        assertTrue(requests.contains(listOf(sakura)))
        assertTrue(requests.contains(listOf(korean)))
        assertTrue(requests.contains(listOf(small, manga)))
        assertEquals(4, requests.flatten().distinct().size)
        assertEquals(4, requests.flatten().size)
    }

    @Test
    fun readinessAndRepair_coverEveryInstalledCombinationWithoutRedownloadingValidFiles() {
        data class Case(
            val name: String,
            val installed: Set<ModelDownloadSpec>,
            val expectedMissing: List<ModelDownloadSpec>,
        )
        listOf(
            Case("fresh install", emptySet(), listOf(small, manga)),
            Case("Manga present but detector missing", setOf(manga), listOf(small)),
            Case("detector present but Manga missing", setOf(small), listOf(manga)),
            Case("both present", setOf(small, manga), emptyList()),
            Case("Korean Paddle is not the Manga detector", setOf(korean, manga), listOf(small)),
            Case("cancelled or failed detector remains missing", setOf(manga), listOf(small)),
        ).forEach { case ->
            fun artifact(spec: ModelDownloadSpec) = ModelReadiness(
                spec, spec in case.installed, supported = true,
                totalBytes = if (spec in case.installed) 100 else 0,
            )
            val readiness = modelReadinessWithDependencies(manga, ::artifact)
            assertEquals(case.name, case.expectedMissing.isEmpty(), readiness.ready)
            assertEquals(case.name, case.expectedMissing.isNotEmpty(), readiness.downloadable)
            val missing = ModelDownloadDependencies.expand(listOf(manga)).filterNot { artifact(it).installed }
            assertEquals(case.name, case.expectedMissing, missing)
            assertEquals(case.name, (2 - case.expectedMissing.size) * 100L, readiness.totalBytes)
        }
    }

    @Test
    fun unsupportedDependency_cannotReportReadyOrDownloadable() {
        val readiness = modelReadinessWithDependencies(manga) { spec ->
            ModelReadiness(spec, installed = true, supported = spec != small)
        }
        assertFalse(readiness.ready)
        assertFalse(readiness.downloadable)
    }
}
