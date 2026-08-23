package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationVisualContext
import com.gameocr.app.data.RuntimeVisualTextItem
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationVisualDebugStoreTest {
    @Test
    fun save_tableDrivenWritesOnlyEnabledValidPayloads() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val base64: String,
            val expectedSaved: Boolean,
        )
        val bytes = "exact-request-image".toByteArray()

        listOf(
            Case("debug enabled", true, Base64.getEncoder().encodeToString(bytes), true),
            Case("release disabled", false, Base64.getEncoder().encodeToString(bytes), false),
            Case("invalid base64", true, "not base64!", false),
        ).forEach { case ->
            val root = Files.createTempDirectory("visual-debug-${case.name.hashCode()}").toFile()
            try {
                val artifact = TranslationVisualDebugStore(
                    directory = root,
                    enabled = case.enabled,
                    maxFiles = 3,
                    nowMs = { 123L },
                ).save(visual(case.base64, bytes.size), "word select")

                assertEquals(case.name, case.expectedSaved, artifact != null)
                assertEquals(case.name, if (case.expectedSaved) 1 else 0, root.listFiles().orEmpty().size)
                artifact?.let {
                    assertTrue(case.name, it.absolutePath.contains("word_select"))
                    assertArrayEquals(case.name, bytes, java.io.File(it.absolutePath).readBytes())
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun save_retentionTableKeepsNewestBoundedImages() {
        data class Case(val writes: Int, val limit: Int, val expected: Int)
        listOf(
            Case(1, 3, 1),
            Case(3, 3, 3),
            Case(5, 3, 3),
            Case(2, 0, 0),
        ).forEach { case ->
            val root = Files.createTempDirectory("visual-debug-retention").toFile()
            var timestamp = 0L
            try {
                val store = TranslationVisualDebugStore(
                    directory = root,
                    enabled = true,
                    maxFiles = case.limit,
                    nowMs = { ++timestamp },
                )
                repeat(case.writes) { index ->
                    val bytes = "image-$index".toByteArray()
                    store.save(
                        visual(Base64.getEncoder().encodeToString(bytes), bytes.size, "hash-$index"),
                        "gallery-$index",
                    )
                }
                val files = root.listFiles().orEmpty().sortedBy { it.name }
                assertEquals(case.toString(), case.expected, files.size)
                if (case.writes > case.limit && case.limit > 0) {
                    assertFalse(case.toString(), files.any { it.name.startsWith("0000000000001_") })
                    assertTrue(case.toString(), files.last().name.startsWith("0000000000005_") || case.writes < 5)
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun visual(
        base64: String,
        byteCount: Int,
        sha256: String = "hash",
    ) = RuntimeTranslationVisualContext(
        mimeType = "image/jpeg",
        base64Data = base64,
        width = 100,
        height = 200,
        byteCount = byteCount,
        sha256 = sha256,
        items = listOf(RuntimeVisualTextItem(1, "source", 0, 0, 1000, 1000)),
        combineIntoSingleOutput = false,
    )
}
