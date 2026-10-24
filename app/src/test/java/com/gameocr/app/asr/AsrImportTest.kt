package com.gameocr.app.asr

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrImportTest {
    private fun source(name: String, size: Long?, bytes: ByteArray = ByteArray(0)) =
        AsrImportSource(name, size) { ByteArrayInputStream(bytes) }

    @Test fun manifestMatchingByNameThenUniqueSize_tableDriven() {
        val model = AsrModelId.SENSE_VOICE
        val onnx = model.files[0]
        val tokens = model.files[1]
        data class Case(val name: String, val sources: List<AsrImportSource>, val expected: Any)
        listOf(
            Case("exact names", listOf(source(onnx.name, null), source(tokens.name, null)), listOf(onnx.name, tokens.name)),
            Case("renamed by size", listOf(source("model.int8 (1).onnx", onnx.bytes), source(tokens.name, null)),
                listOf("model.int8 (1).onnx", tokens.name)),
            Case("missing tokens", listOf(source(onnx.name, onnx.bytes)), tokens.name),
            Case("unknown size is not guessed", listOf(source(onnx.name, null), source("t.txt", null)), tokens.name),
            Case("ambiguous size is rejected", listOf(source(onnx.name, null), source("a", tokens.bytes), source("b", tokens.bytes)),
                tokens.name),
            // A named file is never taken by another entry's size match.
            Case("names win over sizes", listOf(source(tokens.name, onnx.bytes), source(onnx.name, tokens.bytes)),
                listOf(onnx.name, tokens.name)),
        ).forEach { case ->
            when (val match = AsrImportPolicy.match(model, case.sources)) {
                is AsrImportPolicy.Match.Matched -> assertEquals(case.name, case.expected, match.files.map { it.second.name })
                is AsrImportPolicy.Match.Missing -> assertEquals(case.name, case.expected, match.name)
            }
        }
    }

    private fun spec(name: String, bytes: ByteArray) = AsrModelFile(
        name, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    @Test fun stagingVerifiesSizeAndHash_tableDriven() = runBlocking {
        val good = "model-bytes".toByteArray()
        val expected = spec("model.onnx", good)
        data class Case(val name: String, val provided: ByteArray, val mismatch: String?)
        listOf(
            Case("verified", good, null),
            Case("same size, other bytes", "model-bytez".toByteArray(), "SHA-256 mismatch"),
            Case("truncated", good.copyOf(4), "size 4, expected ${good.size}"),
            Case("too large", good + good, "size ${good.size * 2}, expected ${good.size}"),
        ).forEach { case ->
            val staging = File(Files.createTempDirectory("asr-stage").toFile(), "staging")
            val result = AsrModelFiles.stageVerified(listOf(expected to source("x", null, case.provided)), staging)
            assertEquals(case.name, case.mismatch, result?.reason)
            if (case.mismatch == null) assertTrue(case.name, File(staging, "model.onnx").readBytes().contentEquals(good))
        }
    }

    @Test fun installSwapsAtomicallyAndKeepsOldFilesOnFailure() {
        val root = Files.createTempDirectory("asr-install").toFile()
        val target = File(root, "sense_voice/rev")
        target.mkdirs()
        File(target, "model.onnx").writeText("old")
        val staging = File(root, "staging").apply { mkdirs() }
        File(staging, "model.onnx").writeText("new")
        AsrModelFiles.installStaged(staging, target)
        assertEquals("new", File(target, "model.onnx").readText())
        assertFalse(staging.exists())
        assertTrue(root.walk().none { it.name.startsWith(".trash-") })

        // A staging dir that cannot be moved restores the installed copy.
        val missing = File(root, "never-created")
        val failure = runCatching { AsrModelFiles.installStaged(missing, target) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("new", File(target, "model.onnx").readText())
    }

    @Test fun installIntoEmptyTargetCreatesParents() {
        val root = Files.createTempDirectory("asr-install-new").toFile()
        val staging = File(root, "staging").apply { mkdirs() }
        File(staging, "tokens.txt").writeText("t")
        val target = File(root, "whisper_tiny/rev")
        AsrModelFiles.installStaged(staging, target)
        assertEquals("t", File(target, "tokens.txt").readText())
        assertNull(root.listFiles()?.firstOrNull { it.name.startsWith(".trash-") })
    }
}
