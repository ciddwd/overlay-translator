package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GgufOutputLimitPolicyTest {
    @Test
    fun extendedBudget_doublesWithinRemainingContext_tableDriven() {
        data class Case(val max: Int, val context: Int, val prompt: Int, val expected: Int?)
        listOf(
            Case(256, 2048, 100, 512),
            Case(256, 2048, 1700, 340),
            Case(256, 2048, 1784, null),
            Case(256, 2048, 2000, null),
            Case(16, 512, 0, 32),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected,
                GgufOutputLimitPolicy.extendedBudget(case.max, case.context, case.prompt))
        }
    }

    @Test
    fun partialText_marksUnfinishedAnswersAndRejectsEmptyOutput_tableDriven() {
        listOf(
            "这是一段很长的译文" to "这是一段很长的译文…",
            "  trailing space  " to "trailing space…",
            "" to null,
            "   \n" to null,
        ).forEach { (input, expected) -> assertEquals(input, expected, GgufOutputLimitPolicy.partialText(input)) }
    }

    @Test
    fun truncatedOutputIsRetriedShownAndNeverCached() {
        val translator = source("app/src/main/java/com/gameocr/app/translate/IndexTranslateTranslator.kt")
        val loop = translator.substringAfter("var recovered = false").substringBefore("holder.touch()")
        listOf(
            "output.finish == \"output_limit\" && snapshot.retryFailedTranslation",
            "GgufOutputLimitPolicy.extendedBudget(",
            "GgufOutputLimitPolicy.partialText(output.text)",
            "if (text != null && !recovered && !truncated) translationCache.put(",
        ).forEach { assertTrue(it, loop.contains(it)) }
        val native = source("llama-android/src/main/cpp/llama_template_generation.inc")
        assertTrue(native.contains("common_chat_parse(s.output, partial, parser)"))
        assertTrue(native.contains("s.finish == \"complete\" || s.finish == \"output_limit\" ? s.content : \"\""))
        assertFalse(native.contains("if (s.finish != \"complete\") continue;"))
    }

    private fun source(path: String): String = listOf(File("../$path"), File(path))
        .firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
