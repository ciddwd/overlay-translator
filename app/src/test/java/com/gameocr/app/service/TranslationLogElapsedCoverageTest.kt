package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLogElapsedCoverageTest {

    @Test
    fun translatePairLogs_includeElapsedTime() {
        // Every translation log in the app records its duration, wherever new calls are added.
        val files = File("src/main/java/com/gameocr/app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val calls = files.flatMap { file ->
            val source = file.readText()
            translatePairCallStarts(source).map { start -> file.name to source.substring(start, (start + 260).coerceAtMost(source.length)) }
        }
        assertTrue("translation pair logs exist", calls.isNotEmpty())
        calls.forEachIndexed { index, (name, snippet) ->
            assertTrue("$name translate pair #${index + 1} is missing elapsedMs", "elapsedMs =" in snippet)
        }
    }

    private fun translatePairCallStarts(source: String): List<Int> {
        val pattern = "logRepository.pair("
        val starts = mutableListOf<Int>()
        var index = source.indexOf(pattern)
        while (index >= 0) {
            val snippet = source.substring(index, (index + 160).coerceAtMost(source.length))
            if ("LogRepository.Category.TRANSLATE" in snippet) {
                starts += index
            }
            index = source.indexOf(pattern, index + pattern.length)
        }
        return starts
    }
}
