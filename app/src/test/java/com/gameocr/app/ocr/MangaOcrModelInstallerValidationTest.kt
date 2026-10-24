package com.gameocr.app.ocr

import com.gameocr.app.data.LlmMirrorChoice
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MangaOcrModelInstallerValidationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun validateModelFile_coversMangaOcrFileTypes() {
        data class Case(
            val name: String,
            val fileName: String,
            val write: (File) -> Unit,
            val expectedError: String?,
        )

        val cases = listOf(
            Case(
                name = "onnx binary",
                fileName = MangaOcrModelInstaller.FILE_ENCODER,
                write = { it.writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), 11 * 1024 * 1024L) },
                expectedError = null,
            ),
            Case(
                name = "onnx html response",
                fileName = MangaOcrModelInstaller.FILE_ENCODER,
                write = { it.writeSparse("<html>bad</html>".toByteArray(), 11 * 1024 * 1024L) },
                expectedError = "error response",
            ),
            Case(
                name = "onnx too small",
                fileName = MangaOcrModelInstaller.FILE_ENCODER,
                write = { it.writeBytes(byteArrayOf(0x08, 0x01, 0x12, 0x00)) },
                expectedError = "too small",
            ),
            Case(
                name = "vocab valid",
                fileName = MangaOcrModelInstaller.FILE_VOCAB,
                write = { it.writeText(validVocab()) },
                expectedError = null,
            ),
            Case(
                name = "vocab wrong specials",
                fileName = MangaOcrModelInstaller.FILE_VOCAB,
                write = { it.writeText("bad\n[UNK]\n[CLS]\n[SEP]\n" + "tok\n".repeat(300)) },
                expectedError = "special tokens",
            ),
            Case(
                name = "json valid",
                fileName = MangaOcrModelInstaller.FILE_CONFIG,
                write = { it.writeText("""{"model_type":"vision-encoder-decoder"}""") },
                expectedError = null,
            ),
            Case(
                name = "json html response",
                fileName = MangaOcrModelInstaller.FILE_GEN_CONFIG,
                write = { it.writeText("<!doctype html><html></html>") },
                expectedError = "json object",
            ),
            Case(
                name = "unexpected file",
                fileName = "weights.bin",
                write = { it.writeBytes(byteArrayOf(1, 2, 3, 4)) },
                expectedError = "unexpected",
            ),
        )

        cases.forEach { case ->
            val file = File(temp.root, case.name.replace(' ', '_'))
            case.write(file)

            val error = MangaOcrModelInstaller.validateModelFile(case.fileName, file)

            if (case.expectedError == null) {
                assertNull(case.name, error)
            } else {
                assertTrue(
                    "${case.name}: expected ${case.expectedError}, got $error",
                    error?.contains(case.expectedError, ignoreCase = true) == true
                )
            }
        }
    }

    @Test
    fun urlsFor_ordersNetworkDownloadSourceAndFallbacks() {
        val file = MangaOcrModelInstaller.FILE_VOCAB
        val official = "https://huggingface.co/${MangaOcrModelInstaller.HF_REPO}/resolve/main/$file"
        val hfMirror = "https://hf-mirror.com/${MangaOcrModelInstaller.HF_REPO}/resolve/main/$file"

        data class Case(
            val name: String,
            val userMirror: String?,
            val choice: LlmMirrorChoice,
            val expected: List<String>,
        )

        val cases = listOf(
            Case("official first", null, LlmMirrorChoice.HF_OFFICIAL, listOf(official, hfMirror)),
            Case("hf mirror first", null, LlmMirrorChoice.HF_MIRROR, listOf(hfMirror, official)),
            Case(
                name = "network custom base first",
                userMirror = "https://cdn.example/manga",
                choice = LlmMirrorChoice.CUSTOM,
                expected = listOf("https://cdn.example/manga/$file", official),
            ),
            Case(
                name = "legacy per-model mirror still wins",
                userMirror = "https://legacy.example/manga/",
                choice = LlmMirrorChoice.HF_MIRROR,
                expected = listOf("https://legacy.example/manga/$file", hfMirror, official),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MangaOcrModelInstaller.urlsFor(case.userMirror, file, case.choice)
            )
        }
    }

    @Test
    fun draftDecoderUrls_arePinnedToTheVerifiedCommitAndSkipCustomMirrors() {
        val file = MangaOcrModelInstaller.FILE_DRAFT_DECODER
        val path = "${MangaOcrModelInstaller.DRAFT_HF_REPO}/resolve/${MangaOcrModelInstaller.DRAFT_REVISION}/$file"
        val official = "https://huggingface.co/$path"
        val hfMirror = "https://hf-mirror.com/$path"

        assertEquals(listOf(official, hfMirror), MangaOcrModelInstaller.urlsFor(null, file, LlmMirrorChoice.HF_OFFICIAL))
        assertEquals(listOf(hfMirror, official), MangaOcrModelInstaller.urlsFor(null, file, LlmMirrorChoice.HF_MIRROR))
        assertEquals(
            listOf(official, hfMirror),
            MangaOcrModelInstaller.urlsFor("https://cdn.example/manga", file, LlmMirrorChoice.CUSTOM),
        )
    }

    @Test
    fun draftDecoder_needsTheExactSizeAndOnlyInstalledContentNeedsTheHash() {
        val file = MangaOcrModelInstaller.FILE_DRAFT_DECODER
        val exact = temp.newFile("exact.onnx").apply {
            writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), MangaOcrModelInstaller.DRAFT_BYTES)
        }
        val short = temp.newFile("short.onnx").apply {
            writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), MangaOcrModelInstaller.DRAFT_BYTES - 1)
        }
        val long = temp.newFile("long.onnx").apply {
            writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), MangaOcrModelInstaller.DRAFT_BYTES + 1)
        }

        // The cheap check accepts the right size; the zero-filled content cannot match the pinned hash.
        assertNull(MangaOcrModelInstaller.validateModelFile(file, exact))
        assertTrue(MangaOcrModelInstaller.validateInstalledContent(file, exact)!!.contains("SHA-256 mismatch"))
        assertTrue(MangaOcrModelInstaller.validateModelFile(file, short)!!.contains("too small"))
        assertTrue(MangaOcrModelInstaller.validateModelFile(file, long)!!.contains("unexpected size"))
        // Required files keep their old checks: no hash is pinned for them.
        val encoder = temp.newFile("encoder.onnx").apply {
            writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), 11 * 1024 * 1024L)
        }
        assertNull(MangaOcrModelInstaller.validateInstalledContent(MangaOcrModelInstaller.FILE_ENCODER, encoder))
    }

    private fun validVocab(): String = buildString {
        append("[PAD]\n")
        append("[UNK]\n")
        append("[CLS]\n")
        append("[SEP]\n")
        repeat(300) { append("tok$it\n") }
    }

    private fun File.writeSparse(prefix: ByteArray, totalLength: Long) {
        outputStream().use { it.write(prefix) }
        RandomAccessFile(this, "rw").use { it.setLength(totalLength) }
    }
}
