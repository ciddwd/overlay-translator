package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiddleDotSeparatorAuditTest {
    @Test
    fun userVisibleSources_tableDriven_haveNoMiddleDotSeparators() {
        data class Case(val name: String, val path: String, val extensions: Set<String>)
        val cases = listOf(
            Case("Kotlin and Java", "src/main/java", setOf("kt", "java")),
            Case("resources", "src/main/res", setOf("xml")),
        )

        cases.forEach { case ->
            val hits = projectFile(case.path).walkTopDown()
                .filter { it.isFile && it.extension in case.extensions }
                .flatMap { file ->
                    file.readLines().asSequence().mapIndexedNotNull { index, line ->
                        if ('·' in line) "${file.name}:${index + 1}: ${line.trim()}" else null
                    }
                }
                .toList()

            if (case.name == "Kotlin and Java") {
                assertEquals(case.name, 2, hits.size)
                assertTrue(case.name, hits.all { "]·r" in it })
            } else {
                assertTrue("${case.name}: $hits", hits.isEmpty())
            }
        }
    }

    @Test
    fun removedUnusedStrings_tableDriven_doNotRemainInEitherLocale() {
        val removed = listOf(
            "gallery_main_recent",
            "dictionary_pack_source_format",
            "glossary_import_preview_summary",
        )
        listOf("src/main/res/values/strings.xml", "src/main/res/values-zh-rCN/strings.xml")
            .forEach { path ->
                val content = projectFile(path).readText()
                removed.forEach { name -> assertFalse("$path still contains $name", name in content) }
            }
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }
}
