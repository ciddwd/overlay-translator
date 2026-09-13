package com.gameocr.app.download

import com.gameocr.app.network.DebugHttpWireLoggingInterceptor
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadLowMemorySmokeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun downloadAndVerify256MiBWithDebugLoggingUnderSmallHeap() = runBlocking {
        val heap = Runtime.getRuntime().maxMemory()
        if (System.getProperty("modelDownload.lowMemorySmoke") == "true") assertTrue(heap <= 64L * 1024 * 1024)
        val size = 256L * 1024 * 1024
        var read = 0L
        val logs = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(DebugHttpWireLoggingInterceptor(logs::add))
            .addInterceptor { chain -> Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", size.toString())
                .body(object : ResponseBody() {
                    // Deliberately mislabel model bytes: the download tag must override MIME sniffing.
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = size
                    override fun source() = object : Source {
                        private val block = ByteArray(8192) { 8 }
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (read >= size) return -1L
                            val count = minOf(block.size.toLong(), byteCount, size - read).toInt()
                            sink.write(block, 0, count)
                            read += count
                            return count.toLong()
                        }
                        override fun close() = Unit
                        override fun timeout() = Timeout.NONE
                    }.buffer()
                }).build() }.build()
        val dest = File(temp.root, "model.onnx")
        var finalProgress = 0L
        ModelFileDownloader(client).download("https://example.com/model", dest,
            { PaddleModelInstaller.validateDownloadedFile(dest.name, it) }) { downloaded, _ -> finalProgress = downloaded }
        assertEquals(size, dest.length())
        assertEquals(size, finalProgress)
        assertFalse(File(dest.path + ".tmp").exists())
        assertTrue(logs.sumOf { it.length } < 4000)
        println("Download smoke: heapMax=$heap modelBytes=${dest.length()} wireLogChars=${logs.sumOf { it.length }}")
    }

    @Test fun oversizedConfigurationAndUnboundedVocabLinesDoNotAllocateWholeFile() {
        val file = File(temp.root, "config")
        // A sparse 96 MiB malformed config would exceed the smoke test heap with readText().
        RandomAccessFile(file, "rw").use { it.setLength(96L * 1024 * 1024) }
        assertNotNull(MangaOcrModelInstaller.validateModelFile(MangaOcrModelInstaller.FILE_CONFIG, file))
        assertNotNull(MangaOcrModelInstaller.validateModelFile(MangaOcrModelInstaller.FILE_VOCAB, file))
        for ((name, text) in listOf("det.onnx" to "<html>error", "rec.onnx" to "{\"error\":1}",
            "keys.yml" to "<!doctype html>", "det.onnx" to "version https://git-lfs.github.com/spec/v1")) {
            file.writeText(text)
            assertNotNull(PaddleModelInstaller.validateDownloadedFile(name, file))
        }
    }
}
