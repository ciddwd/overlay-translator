package com.gameocr.app.download

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val data = "model-content-v1".toByteArray()
    private val url = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main/decoder_model.onnx"

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun response(request: Request, code: Int = 200, bytes: ByteArray = data,
        total: Long = bytes.size.toLong(), etag: String? = "\"v1\"", hash: String? = null,
        range: String? = null, body: ResponseBody? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Content-Length", total.toString())
        .apply {
            etag?.let { header("ETag", it) }
            hash?.let { header("X-Linked-ETag", "\"$it\"") }
            range?.let { header("Content-Range", it) }
        }.body(body ?: object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = total
            override fun source() = Buffer().write(if (request.method == "HEAD") byteArrayOf() else bytes)
        }).build()

    private class Fixture(val requests: MutableList<Request>, val downloader: ModelFileDownloader)
    private fun fixture(handler: (Request) -> Response): Fixture {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            handler(chain.request())
        }.build()
        return Fixture(requests, ModelFileDownloader(client))
    }
    private suspend fun Fixture.download(dest: File, validate: (File) -> String? = { null }) =
        downloader.download(url, dest, validate) { _, _ -> }

    @Test fun completeLegacyTempIsVerifiedBeforeAnyGet_table() = runBlocking {
        for (valid in listOf(true, false)) {
            val dest = File(folder.newFolder(), "model.onnx")
            File(dest.path + ".tmp").writeBytes(if (valid) data else ByteArray(data.size))
            val f = fixture { response(it, hash = digest(data)) }
            f.download(dest)
            assertArrayEquals(data, dest.readBytes())
            assertEquals(if (valid) listOf("HEAD") else listOf("HEAD", "GET"), f.requests.map { it.method })
            assertFalse(File(dest.path + ".tmp").exists())
        }
    }

    @Test fun resumedResponses_table() = runBlocking {
        // A real partial transfer writes its checkpoint, then a new downloader resumes it.
        for (mode in listOf("206", "200", "changed", "offset", "416", "416-no-range")) {
            val dest = File(folder.newFolder(), "model")
            val first = fixture { response(it, bytes = data.copyOf(4), total = data.size.toLong()) }
            try { first.download(dest); fail("truncation must fail") } catch (_: IOException) { }
            assertEquals(4L, File(dest.path + ".tmp").length())
            var gets = 0
            val f = fixture { req ->
                val etag = if (mode == "changed") "\"v2\"" else "\"v1\""
                if (req.method == "HEAD") response(req, etag = etag)
                else {
                    gets++
                    if (gets > 1 || mode in listOf("200", "changed")) response(req, etag = etag)
                    else if (mode.startsWith("416")) response(req, 416, byteArrayOf(), 0,
                        range = if (mode == "416") "bytes */${data.size}" else null)
                    else response(req, 206, data.copyOfRange(4, data.size), (data.size - 4).toLong(),
                        range = "bytes ${if (mode == "offset") 3 else 4}-${data.size - 1}/${data.size}")
                }
            }
            f.download(dest)
            assertArrayEquals(mode, data, dest.readBytes())
            val getRequests = f.requests.filter { it.method == "GET" }
            assertEquals(mode, if (mode == "changed") null else "bytes=4-", getRequests.first().header("Range"))
            if (mode != "changed") assertEquals("\"v1\"", getRequests.first().header("If-Range"))
            assertEquals(mode, if (mode in listOf("offset", "416", "416-no-range")) 2 else 1, gets)
            if (gets == 2) assertNull(getRequests.last().header("Range"))
        }
    }

    @Test fun invalidRangeRetriesOnlyOnceAndPreservesInstalledFile() = runBlocking {
        for (code in listOf(206, 416)) {
            val dest = File(folder.newFolder(), "model")
            dest.writeText("installed")
            val f = fixture { response(it, code, range = "bytes 2-4/9") }
            try { f.download(dest); fail("invalid response") } catch (error: IOException) {
                assertFalse(ModelDownloadWorkPolicy.shouldRetry(0, error))
            }
            assertEquals("installed", dest.readText())
            assertTrue(f.requests.count { it.method == "GET" } <= 2)
        }
    }

    @Test fun complete416CanBeRecoveredUsingResponseDigest() = runBlocking {
        val dest = File(folder.newFolder(), "model")
        val first = fixture { response(it, bytes = data.copyOf(4), total = data.size.toLong()) }
        try { first.download(dest) } catch (_: IOException) { }
        val f = fixture { req ->
            if (req.method == "HEAD") response(req)
            else {
                // Simulate a fully-written temporary file discovered when the CDN answers 416.
                File(dest.path + ".tmp").writeBytes(data)
                response(req, 416, byteArrayOf(), 0, hash = digest(data), range = "bytes */${data.size}")
            }
        }
        f.download(dest)
        assertEquals(listOf("HEAD", "GET"), f.requests.map { it.method })
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun complete416WithoutHeadersUsesHeadForVerification() = runBlocking {
        val dest = File(folder.newFolder(), "model")
        val first = fixture { response(it, bytes = data.copyOf(4), total = data.size.toLong()) }
        try { first.download(dest) } catch (_: IOException) { }
        var heads = 0
        val f = fixture { req ->
            if (req.method == "HEAD") response(req, hash = if (++heads == 2) digest(data) else null)
            else {
                File(dest.path + ".tmp").writeBytes(data)
                response(req, 416, byteArrayOf(), 0)
            }
        }
        f.download(dest)
        assertEquals(listOf("HEAD", "GET", "HEAD"), f.requests.map { it.method })
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun cancellationKeepsPartialAndReleasesFileLock() = runBlocking {
        val dest = File(folder.newFolder(), "model")
        val entered = CountDownLatch(1)
        val f = fixture { req -> response(req, body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = data.size.toLong()
            override fun source() = object : Source {
                var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (first) { first = false; sink.write(data, 0, 4); return 4 }
                    entered.countDown()
                    Thread.sleep(30_000)
                    return -1
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
        }) }
        val job = launch(Dispatchers.Default) { f.download(dest) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertFalse(dest.exists())
        assertEquals(4L, File(dest.path + ".tmp").length())
        val retry = fixture { req ->
            if (req.method == "HEAD") response(req)
            else response(req, 206, data.copyOfRange(4, data.size), (data.size - 4).toLong(), range = "bytes 4-${data.size - 1}/${data.size}")
        }
        withTimeout(3_000) { retry.download(dest) }
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun httpAndLocalValidationFailuresKeepTheirActualCause() = runBlocking {
        for (code in listOf(401, 404, 429, 500)) {
            val f = fixture { response(it, code) }
            try { f.download(File(folder.newFolder(), "model")); fail("HTTP error") }
            catch (e: ModelDownloadHttpException) { assertEquals(code, e.code) }
        }
        val f = fixture { response(it) }
        try { f.download(File(folder.newFolder(), "model")) { "bad header" }; fail("validation error") }
        catch (e: ModelDownloadFileException) { assertTrue(e.message!!.contains("bad header")) }
    }

    @Test fun representationIdentity_table() {
        fun m(key: String = "a", tag: String? = "\"v1\"", hash: String? = null) =
            ModelFileDownloader.Metadata(key, tag, 10, hash)
        val hash = digest(data)
        listOf(
            Triple(m(), m(), true), Triple(m(), m(tag = "\"v2\""), false),
            Triple(m(), m(key = "b"), false), Triple(m(tag = null), m(tag = null), false),
            Triple(m(hash = hash), m(key = "b", hash = hash), true),
            Triple(m(hash = hash), m(hash = digest(byteArrayOf(1))), false),
        ).forEach { (a, b, expected) -> assertEquals(ModelFileDownloader.sameRepresentation(a, b), expected) }
        listOf(null, "W/\"v1\"", "v1", "\"\"").forEach { assertNull(ModelFileDownloader.strongEtag(it)) }
    }

    @Test fun redirectLinkedDigestIsNotConfusedWithOpaqueCdnEtag() = runBlocking {
        val dest = File(folder.newFolder(), "model")
        File(dest.path + ".tmp").writeBytes(data)
        val f = fixture { req ->
            val redirect = response(req, 302, byteArrayOf(), 0, hash = digest(data)).newBuilder().body(null).build()
            val cdn = req.newBuilder().url("https://example.com/cdn-model").build()
            response(cdn, etag = "\"${"a".repeat(64)}\"").newBuilder().priorResponse(redirect).build()
        }
        f.download(dest)
        assertEquals(listOf("HEAD"), f.requests.map { it.method })
        assertArrayEquals(data, dest.readBytes())
    }

    @Test fun cancellingActualBlockedSocketReadIsPrompt() = runBlocking {
        for (waitForBody in listOf(false, true)) {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val releaseServer = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nETag: \"v1\"\r\n\r\nbody".toByteArray())
                    flush()
                }
                accepted.countDown()
                releaseServer.await(10, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        try {
            val dest = File(folder.newFolder(), "model")
            val downloader = ModelFileDownloader(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build())
            val job = launch(Dispatchers.Default) {
                downloader.download("http://127.0.0.1:${server.localPort}/model", dest, { null }) { _, _ -> }
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            if (waitForBody) withTimeout(2_000) {
                while (File(dest.path + ".tmp").length() < 4) delay(10)
            }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertFalse(dest.exists())
        } finally {
            releaseServer.countDown()
            server.close()
            worker.join(2_000)
        }
        }
    }

    @Test fun cancellingAnotherRequestWaitingForSameFileCannotDamageActiveTransfer() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dest = File(folder.newFolder(), "model")
        val first = fixture { req ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(req)
        }
        val second = fixture { response(it) }
        val active = launch(Dispatchers.Default) { first.download(dest) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val queued = launch(Dispatchers.Default) { second.download(dest) }
            delay(100)
            assertTrue(second.requests.isEmpty())
            queued.cancelAndJoin()
        } finally {
            release.countDown()
        }
        withTimeout(3_000) { active.join() }
        assertArrayEquals(data, dest.readBytes())
        assertTrue(second.requests.isEmpty())
    }
}
