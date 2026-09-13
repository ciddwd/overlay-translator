package com.gameocr.app.translate

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitSourceModelDeletionTest {
    @Test
    fun deletion_tableDriven_preservesTargetAndUnrelatedModels() = runBlocking {
        data class Case(val source: String, val target: String, val installed: Set<String>, val deleted: String?)
        val cases = listOf(
            Case("sq", "zh-CN", setOf("sq", "zh", "ja"), "sq"),
            Case("ja-JP", "zh-TW", setOf("ja", "zh", "ko"), "ja"),
            Case("ko_KR", "en", setOf("ko", "ja"), "ko"),
            Case("iw", "zh", setOf("he", "zh"), "he"),
            Case("fil", "en", setOf("tl", "zh"), "tl"),
            Case("nb", "ja", setOf("no", "ja"), "no"),
            Case("en-US", "zh", setOf("zh", "ja"), null),
            Case("ja", "zh", setOf("zh", "ko"), null),
            Case("ja", "zh", setOf("ja"), "ja"),
            Case("ja", "zh", emptySet(), null),
            Case("zh-CN", "zh-TW", setOf("zh", "ja"), null),
            Case("auto", "zh", setOf("zh"), null),
            Case("", "zh", setOf("zh"), null),
            Case("xx", "zh", setOf("zh"), null),
            Case("ja", "auto", setOf("ja"), null),
        )
        cases.forEach { case ->
            val installed = case.installed.toMutableSet()
            val deleted = mutableListOf<String>()
            val translator = translator(
                provider = { installed.toSet() },
                delete = { deleted += it; installed.remove(it) },
            )
            assertEquals(case.toString(), case.deleted,
                MlKitSourceModelPolicy.deletableSource(case.source, case.target, installed))
            translator.deleteSourceLanguageModel(case.source, case.target)
            assertEquals(case.toString(), listOfNotNull(case.deleted), deleted)
            assertEquals(case.toString(), case.installed - listOfNotNull(case.deleted).toSet(), installed)
            // Repeating deletion after inventory changed is harmless and never falls back to target.
            translator.deleteSourceLanguageModel(case.source, case.target)
            assertEquals(case.toString(), listOfNotNull(case.deleted), deleted)
        }
    }

    @Test
    fun everySupportedSourceTargetPair_deletesOnlySource() = runBlocking {
        val installed = MlKitLanguagePolicy.supportedLanguageTags - "en"
        for (source in MlKitLanguagePolicy.supportedLanguageTags) {
            for (target in MlKitLanguagePolicy.supportedLanguageTags) {
                val calls = mutableListOf<String>()
                translator({ installed }, { calls += it }).deleteSourceLanguageModel(source, target)
                val expected = source.takeIf { it != "en" && it != target }
                assertEquals("$source -> $target", listOfNotNull(expected), calls)
                assertFalse("target retained: $source -> $target", target in calls)
            }
        }
    }

    @Test
    fun readinessAfterDeletion_requiresOnlyTheRemovedSourceOnNextDownload() = runBlocking {
        val installed = mutableSetOf("ja", "zh", "ko")
        val translator = translator({ installed.toSet() }, { installed.remove(it) })
        assertTrue(translator.areLanguagePairModelsDownloaded("ja", "zh-CN"))
        translator.deleteSourceLanguageModel("ja", "zh-CN")
        assertFalse(translator.areLanguagePairModelsDownloaded("ja", "zh-CN"))
        assertEquals(setOf("ja"), translator.getMissingLanguageModels("ja", "zh-CN"))
        assertTrue(translator.areLanguagePairModelsDownloaded("ko", "zh-CN"))
    }

    @Test
    fun failures_tableDriven_doNotRemoveModelsOrSwallowCancellation() = runBlocking {
        listOf(IOException("inventory"), IOException("delete"), CancellationException("cancel"))
            .forEachIndexed { index, failure ->
                val installed = setOf("ja", "zh")
                val calls = mutableListOf<String>()
                val translator = translator(
                    provider = { if (index == 0) throw failure else installed },
                    delete = { calls += it; throw failure },
                )
                val error = runCatching { translator.deleteSourceLanguageModel("ja", "zh") }.exceptionOrNull()
                assertSame(failure, error)
                assertEquals(if (index == 0) emptyList() else listOf("ja"), calls)
                assertEquals(setOf("ja", "zh"), installed)
            }
    }

    @Test
    fun pendingDeletion_keepsConfirmedSourceEvenIfCallerChangesSelection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val translator = translator({ setOf("ja", "ko", "zh") }) {
            calls += it
            started.complete(Unit)
            finish.await()
        }
        var selection = "ja" to "zh"
        val confirmedPair = selection
        val deletion = async { translator.deleteSourceLanguageModel(confirmedPair.first, confirmedPair.second) }
        started.await()
        selection = "ko" to "zh"
        finish.complete(Unit)
        deletion.await()
        assertEquals("ko", selection.first)
        assertEquals(listOf("ja"), calls)
    }

    private fun translator(
        provider: suspend () -> Set<String>,
        delete: suspend (String) -> Unit,
    ) = MlKitOnDeviceTranslator(
        clientFactory = MlKitTranslationClientFactory { _, _ -> error("Deletion must not create a translator/download") },
        downloadedLanguageProvider = MlKitDownloadedLanguageProvider { provider() },
        modelDeleter = MlKitLanguageModelDeleter { delete(it) },
        cache = TranslationCache(capacity = 4),
    )
}
