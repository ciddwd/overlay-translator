package com.gameocr.app.asr

import com.gameocr.app.data.Settings
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.R
import com.gameocr.app.translate.Translator
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSubtitleCoordinatorTest {
    @Test fun emptyFinalRetractsPartialWithoutTranslationOrAnEmptyLogEntry() = runBlocking {
        val translator = FakeTranslator()
        val h = Harness(translator)
        try {
            h.coordinator.onEvent(1, SpeechEvent(0, "temporary", false))
            h.awaitPairs(listOf("temporary" to "…"))
            h.coordinator.onEvent(1, SpeechEvent(0, "", true))
            h.awaitPairs(emptyList())
            assertTrue(translator.requests.isEmpty())
            assertTrue(h.logs.entries.value.isEmpty())
            h.coordinator.onEvent(1, SpeechEvent(1, "next", true))
            h.awaitPairs(listOf("next" to "T:next"))
            assertEquals(listOf("next"), translator.requests.toList())
        } finally { h.scope.cancel() }
    }

    @Test fun speechTranslationBypassesTextTranslatorAndRejectsResultsAfterPause() = runBlocking {
        val translator = FakeTranslator()
        val h = Harness(translator)
        try {
            h.coordinator.onEvent(1, SpeechEvent(0, "first", false, "第一"))
            h.coordinator.onEvent(1, SpeechEvent(1, "second", false, "第二"))
            h.coordinator.onEvent(1, SpeechEvent(0, "first!", true, "第一段"))
            h.awaitPairs(listOf("first!" to "第一段", "second" to "第二"))
            h.nextGeneration()
            h.coordinator.onEvent(1, SpeechEvent(1, "stale", true, "过期"))
            h.coordinator.onEvent(2, SpeechEvent(2, "new", true, "新段"))
            h.awaitPairs(listOf("first!" to "第一段", "second" to "第二", "new" to "新段"))
            assertTrue(translator.requests.isEmpty())
        } finally { h.scope.cancel() }
    }

    private class FakeTranslator(private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap()) : Translator {
        val requests = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<String>()
        val contexts = java.util.concurrent.ConcurrentHashMap<String, List<RuntimeDialogueTurn>>()
        override suspend fun translate(source: String, settings: Settings): String = "T:$source"
        override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
            requests += source
            contexts[source] = settings.runtimeTranslationPromptContext.previousFrame
            gates[source]?.await()
            if (source == "boom") error("network")
            emit("T:$source")
            completed += source
        }
    }

    private class Harness(translator: Translator, settings: Settings = Settings()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile var generation = 1L
        val metrics = SpeechMetrics()
        val renders = CopyOnWriteArrayList<List<SpeechDisplayRecord>>()
        val logs = LogRepository()
        val coordinator = SpeechSubtitleCoordinator(
            scope = scope,
            translator = translator,
            settings = { settings },
            currentGeneration = { generation },
            metrics = { metrics },
            render = { renders += it },
            clock = { System.nanoTime() / 1_000_000 },
            logRepository = logs,
            userMessage = { if (it == R.string.asr_log_result) "识别结果：" else "语音翻译失败，请稍后重试。" },
        )

        fun pairs(records: List<SpeechDisplayRecord>) = records.map { it.source to it.translation }

        suspend fun awaitPairs(expected: List<Pair<String, String>>) = withTimeout(5_000) {
            while (renders.none { pairs(it) == expected }) delay(10)
        }

        fun lastPairs() = renders.lastOrNull()?.let(::pairs).orEmpty()

        /** Mirrors the controller: a boundary bumps the generation, then notifies. */
        fun nextGeneration() {
            generation++
            coordinator.onGenerationChanged()
        }
    }

    @Test fun runtimeLogReceivesAsrAndTranslationResults_tableDriven() = runBlocking {
        listOf("hello", "boom").forEach { source ->
            val h = Harness(FakeTranslator())
            try {
                h.coordinator.onEvent(1, SpeechEvent(0, source, false))
                h.awaitPairs(listOf(source to "…"))
                assertTrue(h.logs.entries.value.isEmpty())
                h.coordinator.onEvent(1, SpeechEvent(0, source, true))
                withTimeout(5_000) {
                    while (h.logs.entries.value.none { it.category == LogRepository.Category.TRANSLATE }) delay(10)
                }
                val logs = h.logs.entries.value
                assertTrue(logs.any { it.category == LogRepository.Category.SPEECH && it.message == "识别结果：$source" })
                assertTrue(logs.none { it.category == LogRepository.Category.CAPTURE || it.message.contains("[asr") })
                val result = logs.single { it.category == LogRepository.Category.TRANSLATE }
                if (source == "boom") assertEquals(LogRepository.Level.ERROR, result.level)
                else {
                    assertEquals(source, result.source)
                    assertEquals("T:$source", result.translated)
                }
            } finally { h.scope.cancel() }
        }
    }

    @Test fun runtimeLogDeduplicatesS2ttAndRejectsStaleResults() = runBlocking {
        val h = Harness(FakeTranslator())
        try {
            val final = SpeechEvent(0, "hello", true, "你好")
            h.coordinator.onEvent(1, final)
            h.coordinator.onEvent(1, final)
            h.coordinator.onEvent(1, SpeechEvent(1, "pending", false))
            h.awaitPairs(listOf("hello" to "你好", "pending" to "…"))
            assertEquals(1, h.logs.entries.value.count { it.category == LogRepository.Category.TRANSLATE })
            h.nextGeneration()
            h.coordinator.onEvent(1, SpeechEvent(1, "stale", true, "过期"))
            h.coordinator.onEvent(2, final.copy(segmentId = 2, text = "new", providerTranslation = "新"))
            h.awaitPairs(listOf("hello" to "你好", "new" to "新"))
            withTimeout(5_000) {
                while (h.logs.entries.value.count { it.category == LogRepository.Category.TRANSLATE } != 2) delay(10)
            }
            assertTrue(h.logs.entries.value.none { it.message.contains("stale") })
        } finally { h.scope.cancel() }
    }

    @Test fun recordsAreChronologicalWithStableIdsAndOnlyFinalsAreTranslated() = runBlocking {
        val translator = FakeTranslator()
        val h = Harness(translator)
        h.coordinator.onEvent(1, SpeechEvent(0, "hel", isFinal = false))
        h.awaitPairs(listOf("hel" to "…"))
        val partialId = h.renders.last().single().id
        h.coordinator.onEvent(1, SpeechEvent(0, "hello", isFinal = true))
        h.awaitPairs(listOf("hello" to "T:hello"))
        h.coordinator.onEvent(1, SpeechEvent(1, "next", isFinal = false))
        h.awaitPairs(listOf("hello" to "T:hello", "next" to "…"))
        val records = h.renders.last()
        // The finished record keeps the id its partial had; the new utterance gets a new one.
        assertEquals(partialId, records[0].id)
        assertTrue(records[1].id > records[0].id)
        assertEquals(listOf("hello"), translator.requests.toList())
        h.scope.cancel()
    }

    @Test fun staleGenerationsAreDropped_tableDriven() = runBlocking {
        data class Case(val eventGeneration: Long, val current: Long, val shown: Boolean)
        listOf(Case(1, 1, true), Case(1, 2, false), Case(3, 2, false)).forEach { case ->
            val h = Harness(FakeTranslator())
            h.generation = case.current
            h.coordinator.onEvent(case.eventGeneration, SpeechEvent(0, "line", isFinal = false))
            delay(300)
            assertEquals(case.toString(), case.shown, h.renders.isNotEmpty())
            h.scope.cancel()
        }
    }

    @Test fun newSegmentsNeverCancelOlderTranslations() = runBlocking {
        val gates = (0..3).associate { "s$it" to CompletableDeferred<Unit>() }
        val translator = FakeTranslator(gates)
        val h = Harness(translator)
        (0..3).forEach { h.coordinator.onEvent(1, SpeechEvent(it.toLong(), "s$it", isFinal = true)) }
        // Two run at once; release out of order and every segment still gets its own translation.
        listOf("s1", "s2", "s3", "s0").forEach { source ->
            withTimeout(5_000) { while (source !in translator.requests) delay(10) }
            gates.getValue(source).complete(Unit)
        }
        h.awaitPairs((0..3).map { "s$it" to "T:s$it" })
        assertEquals(setOf("s0", "s1", "s2", "s3"), translator.completed.toSet())
        h.scope.cancel()
    }

    @Test fun lateTranslationLandsOnItsOwnRecordWithoutReordering() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(FakeTranslator(mapOf("first" to gate)))
        h.coordinator.onEvent(1, SpeechEvent(0, "first", isFinal = true))
        h.coordinator.onEvent(1, SpeechEvent(1, "second", isFinal = true))
        h.awaitPairs(listOf("first" to "…", "second" to "T:second"))
        gate.complete(Unit)
        h.awaitPairs(listOf("first" to "T:first", "second" to "T:second"))
        h.scope.cancel()
    }

    @Test fun pauseBoundaryCancelsUnfinishedWorkAndStartsFreshRecords() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val translator = FakeTranslator(mapOf("old" to gate))
        val h = Harness(translator)
        h.coordinator.onEvent(1, SpeechEvent(0, "done", isFinal = true))
        h.coordinator.onEvent(1, SpeechEvent(1, "old", isFinal = true))
        h.coordinator.onEvent(1, SpeechEvent(2, "unfinished", isFinal = false))
        h.awaitPairs(listOf("done" to "T:done", "old" to "…", "unfinished" to "…"))
        h.nextGeneration()
        // Finished history stays; the pending translation is canceled; partial text is dropped.
        h.awaitPairs(listOf("done" to "T:done", "old" to ""))
        gate.complete(Unit)
        // The engine restarts its segment numbering after a reset; ids must not merge records.
        h.coordinator.onEvent(2, SpeechEvent(2, "new", isFinal = false))
        h.awaitPairs(listOf("done" to "T:done", "old" to "", "new" to "…"))
        delay(200)
        assertTrue(h.renders.none { records -> records.any { it.translation == "T:old" } })
        assertTrue("old" !in translator.completed)
        h.scope.cancel()
    }

    @Test fun trimmingDisplayedRecordsNeverCancelsTheirTranslation() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val translator = FakeTranslator(mapOf("s0" to gate))
        val h = Harness(translator)
        h.coordinator.setMaxRecords(3)
        (0..4).forEach { h.coordinator.onEvent(1, SpeechEvent(it.toLong(), "s$it", isFinal = true)) }
        h.awaitPairs((2..4).map { "s$it" to "T:s$it" })
        gate.complete(Unit)
        withTimeout(5_000) { while ("s0" !in translator.completed) delay(10) }
        delay(200)
        assertEquals((2..4).map { "s$it" to "T:s$it" }, h.lastPairs())
        h.scope.cancel()
    }

    @Test fun contextModeSendsEarlierSegmentsAndResetForgetsThem_tableDriven() = runBlocking {
        listOf(TranslationContextMode.FAST_PER_SEGMENT, TranslationContextMode.CONTINUOUS_CONTEXT).forEach { mode ->
            val gate = CompletableDeferred<Unit>()
            val translator = FakeTranslator(mapOf("b" to gate))
            val h = Harness(translator, Settings(translatorEngine = TranslatorEngine.OPENAI, translationContextMode = mode))
            try {
                h.coordinator.onEvent(1, SpeechEvent(0, "a", isFinal = true))
                h.awaitPairs(listOf("a" to "T:a"))
                h.coordinator.onEvent(1, SpeechEvent(1, "b", isFinal = true))
                h.coordinator.onEvent(1, SpeechEvent(2, "c", isFinal = true))
                // "c" starts while "b" is still translating, so "b" is context without a translation.
                h.awaitPairs(listOf("a" to "T:a", "b" to "…", "c" to "T:c"))
                gate.complete(Unit)
                h.awaitPairs(listOf("a" to "T:a", "b" to "T:b", "c" to "T:c"))
                h.coordinator.reset()
                h.coordinator.onEvent(1, SpeechEvent(3, "d", isFinal = true))
                h.awaitPairs(listOf("d" to "T:d"))
                val expectedC = if (mode == TranslationContextMode.FAST_PER_SEGMENT) emptyList() else listOf(
                    RuntimeDialogueTurn("a", "T:a"),
                    RuntimeDialogueTurn("b"),
                )
                assertEquals(mode.name, expectedC, translator.contexts.getValue("c"))
                assertEquals(mode.name, emptyList<RuntimeDialogueTurn>(), translator.contexts.getValue("a"))
                assertEquals(mode.name, emptyList<RuntimeDialogueTurn>(), translator.contexts.getValue("d"))
            } finally { h.scope.cancel() }
        }
    }

    @Test fun failedTranslationLeavesSourceWithoutPlaceholderAndCountsInMetrics() = runBlocking {
        val h = Harness(FakeTranslator())
        h.coordinator.onEvent(1, SpeechEvent(0, "boom", isFinal = true))
        h.awaitPairs(listOf("boom" to ""))
        withTimeout(5_000) { while (h.metrics.snapshot().translationFailures == 0L) delay(10) }
        h.scope.cancel()
    }
}
