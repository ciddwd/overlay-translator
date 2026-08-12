package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueTranslationContextPolicyTest {
    @Test
    fun effectiveMode_tableDriven_coversEveryTranslatorEngine() {
        val contextualEngines = setOf(
            TranslatorEngine.OPENAI,
            TranslatorEngine.ANTHROPIC,
            TranslatorEngine.LOCAL_SAKURA,
            TranslatorEngine.LOCAL_HY_MT2,
        )

        TranslatorEngine.values().forEach { engine ->
            val expected = if (engine in contextualEngines) {
                TranslationContextMode.PAGE_CONTEXT
            } else {
                TranslationContextMode.FAST_PER_SEGMENT
            }
            assertEquals(
                engine.name,
                expected,
                DialogueTranslationContextPolicy.effectiveMode(
                    Settings(
                        translatorEngine = engine,
                        translationContextMode = TranslationContextMode.PAGE_CONTEXT,
                    ),
                ),
            )
        }
    }

    @Test
    fun effectiveMode_tableDriven() {
        data class Case(
            val name: String,
            val requested: TranslationContextMode,
            val engine: TranslatorEngine,
            val expected: TranslationContextMode,
        )
        listOf(
            Case("default fast", TranslationContextMode.FAST_PER_SEGMENT, TranslatorEngine.OPENAI,
                TranslationContextMode.FAST_PER_SEGMENT),
            Case("page", TranslationContextMode.PAGE_CONTEXT, TranslatorEngine.ANTHROPIC,
                TranslationContextMode.PAGE_CONTEXT),
            Case("continuous is independent from capture scheduling", TranslationContextMode.CONTINUOUS_CONTEXT,
                TranslatorEngine.OPENAI,
                TranslationContextMode.CONTINUOUS_CONTEXT),
            Case("unsupported engine falls back", TranslationContextMode.PAGE_CONTEXT, TranslatorEngine.DEEPL,
                TranslationContextMode.FAST_PER_SEGMENT),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                DialogueTranslationContextPolicy.effectiveMode(
                    Settings(
                        translationContextMode = case.requested,
                        translatorEngine = case.engine,
                    ),
                ),
            )
        }
    }

    @Test
    fun contextualize_tableDriven_preservesGeometryIndependentSourceList() {
        data class Case(
            val name: String,
            val mode: TranslationContextMode,
            val expectContext: Boolean,
            val expectPrevious: Boolean,
        )
        listOf(
            Case("fast unchanged", TranslationContextMode.FAST_PER_SEGMENT, false, false),
            Case("page sees current", TranslationContextMode.PAGE_CONTEXT, true, false),
            Case("continuous sees previous after any prior capture", TranslationContextMode.CONTINUOUS_CONTEXT,
                true, true),
        ).forEach { case ->
            val settings = Settings(
                translatorEngine = TranslatorEngine.OPENAI,
                translationContextMode = case.mode,
                runtimeTranslationContext = "existing-context",
            )
            val history = DialogueHistorySession()
            history.commit(
                DialogueTranslationContextPolicy.contextKey(settings),
                listOf("前の原文"),
                mapOf(0 to "上一句"),
            )
            val actual = DialogueTranslationContextPolicy.contextualize(
                settings,
                listOf("今の一", "今の二"),
                history,
            ).runtimeTranslationContext
            assertTrue(case.name, actual.startsWith("existing-context"))
            assertEquals(case.name, case.expectContext, "今の一" in actual && "今の二" in actual)
            assertEquals(case.name, case.expectPrevious, "前の原文" in actual && "上一句" in actual)
        }
    }

    @Test
    fun contextualize_tableDriven_keepsGenericTextOutOfSpecializedModelPrompts() {
        data class Case(
            val name: String,
            val engine: TranslatorEngine,
            val expectGenericRuntimeText: Boolean,
        )
        listOf(
            Case("OpenAI owns generic structured context", TranslatorEngine.OPENAI, true),
            Case("Anthropic owns generic structured context", TranslatorEngine.ANTHROPIC, true),
            Case("Sakura owns official context", TranslatorEngine.LOCAL_SAKURA, false),
            Case("Hy-MT2 owns official context", TranslatorEngine.LOCAL_HY_MT2, false),
        ).forEach { case ->
            val actual = DialogueTranslationContextPolicy.contextualize(
                settings = Settings(
                    translatorEngine = case.engine,
                    translationContextMode = TranslationContextMode.PAGE_CONTEXT,
                    runtimeTranslationContext = "existing-generic-context",
                ),
                currentSources = listOf("一行目", "二行目"),
                historySession = DialogueHistorySession(),
            )

            assertEquals(case.name, case.expectGenericRuntimeText, actual.runtimeTranslationContext.isNotBlank())
            assertEquals(case.name, listOf("一行目", "二行目"), actual.runtimeTranslationPromptContext.currentPage)
            if (!case.expectGenericRuntimeText) {
                assertFalse(case.name, actual.runtimeTranslationContext.contains("dialogue_context_json"))
            }
        }
    }

    @Test
    fun historyCommit_tableDriven_keepsPartialAndFailedFramesWithoutFailureMarkers() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val translations: Map<Int, String>,
            val expected: Boolean,
            val expectedTranslations: List<String?>,
        )
        listOf(
            Case("complete", listOf("a", "b"), mapOf(0 to "甲", 1 to "乙"), true,
                listOf("甲", "乙")),
            Case("partial", listOf("a", "b"), mapOf(0 to "甲"), true,
                listOf("甲", null)),
            Case("all failed", listOf("a", "b"), emptyMap(), true,
                listOf(null, null)),
            Case("blank output is stored as missing", listOf("a"), mapOf(0 to " "), true,
                listOf(null)),
            Case("preserved source is not stored as translation", listOf("あいうえ"), mapOf(0 to "あいうえ"),
                true, listOf(null)),
            Case("trimmed preserved source is not stored as translation", listOf(" あいうえ "),
                mapOf(0 to "あいうえ"), true, listOf(null)),
            Case("empty frame", emptyList(), emptyMap(), false, emptyList()),
        ).forEach { case ->
            val history = DialogueHistorySession()
            assertEquals(case.name, case.expected, history.commit("key", case.sources, case.translations))
            val stored = history.historyFor("key")
            assertEquals(case.name, case.expected, stored != null)
            assertEquals(case.name, case.expectedTranslations, stored?.items.orEmpty().map { it.translation })
        }
    }

    @Test
    fun history_isClearedWhenTranslationContextKeyChanges() {
        val history = DialogueHistorySession()
        assertTrue(history.commit("first", listOf("a"), mapOf(0 to "甲")))
        assertNull(history.historyFor("second"))
    }

    @Test
    fun switchingModes_doesNotResurrectStaleContinuousHistory() {
        val history = DialogueHistorySession()
        val continuous = Settings(
            translatorEngine = TranslatorEngine.OPENAI,
            translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT,
        )
        history.commit(
            DialogueTranslationContextPolicy.contextKey(continuous),
            listOf("old"),
            mapOf(0 to "旧"),
        )
        DialogueTranslationContextPolicy.contextualize(
            continuous.copy(translationContextMode = TranslationContextMode.FAST_PER_SEGMENT),
            listOf("fast"),
            historySession = history,
        )
        val restored = DialogueTranslationContextPolicy.contextualize(
            continuous,
            listOf("new"),
            historySession = history,
        )
        assertFalse(restored.runtimeTranslationContext.contains("old"))
    }

    @Test
    fun shouldCommitHistory_acceptsPartialOrFailedFrames_tableDriven() {
        val settings = Settings(
            translatorEngine = TranslatorEngine.OPENAI,
            translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT,
        )
        data class Case(val name: String, val values: Map<Int, String>, val expected: Boolean)
        listOf(
            Case("complete", mapOf(0 to "甲", 1 to "乙"), true),
            Case("partial success", mapOf(0 to "甲"), true),
            Case("all failed", emptyMap(), true),
            Case("blank output is treated as failed", mapOf(0 to "甲", 1 to ""), true),
            Case("wrong index is rejected", mapOf(0 to "甲", 2 to "乙"), false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                DialogueTranslationContextPolicy.shouldCommitHistory(settings, 2, case.values),
            )
        }
    }

    @Test
    fun continuousReuse_tableDriven_reusesOnlyStableOverlappingRegions() {
        data class Case(
            val name: String,
            val mode: TranslationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT,
            val current: List<PageTranslationUnit>,
            val previous: List<DialogueContextItem>,
            val expected: Map<Int, String>,
        )
        fun unit(index: Int, source: String, left: Int, top: Int) = PageTranslationUnit(
            blockIndex = index,
            sourceText = source,
            geometry = DialogueGeometry(left, top, left + 100, top + 40),
        )
        fun old(id: Int, source: String, translation: String?, left: Int, top: Int) =
            DialogueContextItem(
                id = id,
                source = source,
                translation = translation,
                geometry = DialogueGeometry(left, top, left + 100, top + 40),
            )
        listOf(
            Case(
                name = "unchanged overlapping line",
                current = listOf(unit(0, "same", 10, 20)),
                previous = listOf(old(1, "same", "translated", 12, 22)),
                expected = mapOf(0 to "translated"),
            ),
            Case(
                name = "changed text is translated again",
                current = listOf(unit(0, "new", 10, 20)),
                previous = listOf(old(1, "old", "translated", 10, 20)),
                expected = emptyMap(),
            ),
            Case(
                name = "same text in another region is translated again",
                current = listOf(unit(0, "same", 400, 20)),
                previous = listOf(old(1, "same", "translated", 10, 20)),
                expected = emptyMap(),
            ),
            Case(
                name = "failed prior translation is never reused",
                current = listOf(unit(0, "same", 10, 20)),
                previous = listOf(old(1, "same", null, 10, 20)),
                expected = emptyMap(),
            ),
            Case(
                name = "one coincidental line on a different page is not reused",
                current = listOf(
                    unit(0, "same", 10, 20),
                    unit(1, "new-a", 150, 20),
                    unit(2, "new-b", 290, 20),
                ),
                previous = listOf(
                    old(1, "same", "old-translation", 10, 20),
                    old(2, "old-a", "A", 150, 20),
                    old(3, "old-b", "B", 290, 20),
                ),
                expected = emptyMap(),
            ),
            Case(
                name = "duplicate text follows geometry not list order",
                current = listOf(unit(0, "same", 300, 20), unit(1, "same", 10, 20)),
                previous = listOf(
                    old(1, "same", "left", 10, 20),
                    old(2, "same", "right", 300, 20),
                ),
                expected = mapOf(0 to "right", 1 to "left"),
            ),
            Case(
                name = "page mode does not reuse a previous frame",
                mode = TranslationContextMode.PAGE_CONTEXT,
                current = listOf(unit(0, "same", 10, 20)),
                previous = listOf(old(1, "same", "translated", 10, 20)),
                expected = emptyMap(),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ContinuousTranslationReusePolicy.plan(
                    mode = case.mode,
                    current = case.current,
                    previous = DialogueContextFrame(case.previous),
                ),
            )
        }
    }

    @Test
    fun continuousReuse_sourcePreservationIsReusableButNeverBecomesModelTranslation() {
        val geometry = DialogueGeometry(10, 20, 110, 60)
        val unit = PageTranslationUnit(
            blockIndex = 0,
            sourceText = "CASHIER",
            geometry = geometry,
        )
        val history = DialogueHistorySession()

        assertTrue(history.commitUnits("key", listOf(unit), mapOf(0 to "CASHIER")))
        val previous = history.historyFor("key")
        assertNull(previous?.items?.single()?.translation)
        assertEquals("CASHIER", previous?.items?.single()?.reusableOutput)
        assertEquals(
            mapOf(0 to "CASHIER"),
            ContinuousTranslationReusePolicy.plan(
                mode = TranslationContextMode.CONTINUOUS_CONTEXT,
                current = listOf(unit),
                previous = previous,
            ),
        )
    }

    @Test
    fun historyTokenBudget_tableDriven_keepsNewestWholeTurns() {
        val turns = listOf(
            com.gameocr.app.data.RuntimeDialogueTurn("old", "A"),
            com.gameocr.app.data.RuntimeDialogueTurn("middle", "BB"),
            com.gameocr.app.data.RuntimeDialogueTurn("new", "CCC"),
        )
        val cost: (com.gameocr.app.data.RuntimeDialogueTurn) -> Int = { it.translation?.length ?: 0 }
        data class Case(val name: String, val budget: Int, val expectedSources: List<String>)
        listOf(
            Case("zero", 0, emptyList()),
            Case("newest only", 3, listOf("new")),
            Case("newest two", 5, listOf("middle", "new")),
            Case("all", 6, listOf("old", "middle", "new")),
            Case("does not skip an oversized newest turn", 2, emptyList()),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedSources,
                DialogueHistoryTokenBudgetPolicy.selectNewest(turns, case.budget, cost)
                    .map { it.source },
            )
        }
    }
}
