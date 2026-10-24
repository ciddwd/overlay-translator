package com.gameocr.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsBundleImportTransactionTest {
    private class Recorder(private val failAt: String? = null, private val cancelAt: String? = null) {
        val events = mutableListOf<String>()

        suspend fun step(name: String) {
            events += name
            if (name == cancelAt) {
                currentCoroutineContext().cancel()
                yield()
            }
            if (name == failAt) error("fail $name")
        }

        fun transaction() = SettingsBundleImportTransaction<String, String, String, String>(
            commitFont = { step("commit:$it"); "font:$it" },
            prepareSettings = { fonts -> step("prepare"); "merged${fonts.size}" },
            importGlossary = { step("glossary"); 3 },
            commitSettings = { prepared -> step("settings"); "saved:$prepared" },
            finishFont = { events += "finish:$it" },
            // Undo steps suspend, so they only complete if rollback runs outside cancellation.
            restoreSettings = { yield(); events += "restore:settings" },
            restoreGlossary = { yield(); events += "restore:glossary" },
            rollbackFont = { events += "rollback:$it" },
        )
    }

    @Test
    fun success_commitsFontsThenGlossaryThenSettingsThenFinalizesFonts() = runBlocking {
        val recorder = Recorder()
        val outcome = recorder.transaction().run(listOf("a", "b"))
        assertEquals(
            listOf("commit:a", "commit:b", "prepare", "glossary", "settings", "finish:font:a", "finish:font:b"),
            recorder.events,
        )
        assertEquals("saved:merged2", outcome.settings)
        assertEquals(3, outcome.glossaryCount)
        assertEquals(listOf("font:a", "font:b"), outcome.fonts)
    }

    @Test
    fun failure_undoesOnlyCompletedStepsNewestFirst_tableDriven() {
        data class Case(val failAt: String, val undo: List<String>)
        listOf(
            Case("commit:b", listOf("rollback:font:a")),
            Case("prepare", listOf("rollback:font:b", "rollback:font:a")),
            Case("glossary", listOf("rollback:font:b", "rollback:font:a")),
            Case("settings", listOf("restore:glossary", "rollback:font:b", "rollback:font:a")),
        ).forEach { case ->
            val recorder = Recorder(failAt = case.failAt)
            try {
                runBlocking { recorder.transaction().run(listOf("a", "b")) }
                fail("${case.failAt} should fail")
            } catch (expected: IllegalStateException) {
                assertEquals(case.failAt, "fail ${case.failAt}", expected.message)
            }
            val undo = recorder.events.dropWhile { it != case.failAt }.drop(1)
            assertEquals(case.failAt, case.undo, undo)
            assertTrue(case.failAt, recorder.events.none { it.startsWith("finish:") })
        }
    }

    @Test
    fun cancellation_stillRollsBackCompletedSteps() {
        val recorder = Recorder(cancelAt = "settings")
        try {
            runBlocking { recorder.transaction().run(listOf("a")) }
            fail("cancelled import should not complete")
        } catch (expected: CancellationException) {
            // Expected: the import itself is cancelled.
        }
        assertEquals(listOf("restore:glossary", "rollback:font:a"),
            recorder.events.dropWhile { it != "settings" }.drop(1))
    }
}
