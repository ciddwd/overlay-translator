package com.gameocr.app.shizuku

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PermissionCommandRunnerTest {
    private class BlockingPipe : InputStream() {
        private val closed = CountDownLatch(1)
        override fun read(): Int { check(closed.await(3, TimeUnit.SECONDS)); return -1 }
        override fun close() { closed.countDown() }
    }

    private class RemoteStyleProcess(
        val stdout: InputStream = ByteArrayInputStream(" service/list \n".toByteArray()),
        val stderr: InputStream = ByteArrayInputStream(ByteArray(0)),
        val code: Int = 0,
    ) : Process() {
        @Volatile var finished = false
        var destroys = 0
        var remoteWaits = 0
        val stdin = ByteArrayOutputStream()
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun getOutputStream() = stdin
        override fun waitFor(): Int = error("unbounded wait must not be used")
        override fun exitValue(): Int {
            if (!finished) throw IllegalArgumentException("process hasn't exited")
            return code
        }
        override fun destroy() { destroys++; stdout.close(); stderr.close() }
        fun waitForTimeout(timeout: Long): Boolean {
            assertTrue(timeout in 1..100)
            remoteWaits++
            finished = true
            return true
        }
    }

    @Test fun reproducesInheritedWaitFailureAndUsesRemoteWaitInstead() = runBlocking {
        val process = RemoteStyleProcess()
        try {
            process.waitFor(1, TimeUnit.SECONDS)
            fail("the old inherited waiter must reproduce the device exception")
        } catch (expected: IllegalArgumentException) {
            assertEquals("process hasn't exited", expected.message)
        }
        val result = runPermissionCommand(process, waitForProcess = { _, timeout -> process.waitForTimeout(timeout) })
        assertEquals(PermissionCommandResult(0, "service/list"), result)
        assertEquals(1, process.remoteWaits)
        assertTrue(process.destroys > 0)
    }

    @Test fun exitCodesAndEmptyOutputArePreserved_tableDriven() = runBlocking {
        for (code in listOf(0, 1, 126)) for (text in listOf("", "null\n", "a/.One:b/.Two\n")) {
            val process = RemoteStyleProcess(stdout = ByteArrayInputStream(text.toByteArray()), code = code)
            assertEquals(PermissionCommandResult(code, text.trim()),
                runPermissionCommand(process, waitForProcess = { _, timeout -> process.waitForTimeout(timeout) }))
            assertTrue(process.destroys > 0)
        }
    }

    @Test(timeout = 5000) fun timeoutDestroysProcessBeforeJoiningBlockedReaders() = runBlocking {
        val process = RemoteStyleProcess(BlockingPipe(), BlockingPipe())
        val result = runPermissionCommand(process, timeoutMillis = 60, waitForProcess = { _, _ ->
            Thread.sleep(10)
            false
        })
        assertNull(result)
        assertTrue(process.destroys > 0)
    }

    @Test(timeout = 5000) fun cancellationPropagatesAndUnblocksReaders() = runBlocking {
        val process = RemoteStyleProcess(BlockingPipe(), BlockingPipe())
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            runPermissionCommand(process, waitForProcess = { _, _ ->
                started.complete(Unit)
                Thread.sleep(10)
                false
            })
            returned = true
        }
        withTimeout(2000) { started.await(); job.cancelAndJoin() }
        assertFalse(returned)
        assertTrue(process.destroys > 0)
    }

    @Test(timeout = 5000) fun waiterFailureUnblocksReadersWithoutLosingCause() = runBlocking {
        val process = RemoteStyleProcess(BlockingPipe(), BlockingPipe())
        val failure = IllegalArgumentException("process hasn't exited")
        try {
            runPermissionCommand(process, waitForProcess = { _, _ -> throw failure })
            fail("must preserve the actual failure")
        } catch (actual: IllegalArgumentException) {
            // Coroutine stack-trace recovery may copy the throwable across the dispatcher boundary.
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
        assertTrue(process.destroys > 0)
    }

    @Test fun outputLimitsRejectRatherThanTruncateSettings_tableDriven() = runBlocking {
        for (size in listOf(0, 65536, 65537)) for (errorPipe in listOf(false, true)) {
            val bytes = ByteArrayInputStream(ByteArray(size) { 'a'.code.toByte() })
            val process = if (errorPipe) RemoteStyleProcess(stderr = bytes) else RemoteStyleProcess(stdout = bytes)
            try {
                val result = runPermissionCommand(process, waitForProcess = { _, timeout -> process.waitForTimeout(timeout) })
                assertTrue("oversized output must fail", size <= 65536)
                if (!errorPipe) assertEquals(size, result!!.output.length)
            } catch (error: java.io.IOException) {
                assertEquals(65537, size)
                assertEquals("Permission command output exceeds limit", error.message)
            }
            assertTrue(process.destroys > 0)
        }
    }

    @Test fun bothPermissionCommandsUseSharedRunnerAndDedicatedShizukuApi() {
        val root = File("src/main/java/com/gameocr/app/shizuku")
        val manager = File(root, "ShizukuManager.kt").readText()
        val runner = File(root, "PermissionCommandRunner.kt").readText()
        assertTrue(manager.contains("executePermissionCommand(overlayPermissionAppOpsCommand(packageName, user))"))
        assertTrue(manager.contains("executePermissionCommand(command)"))
        assertTrue(manager.contains("runPermissionCommand(process)"))
        assertFalse(manager.contains("process.waitFor("))
        assertTrue(runner.contains("require(process is ShizukuRemoteProcess)"))
        assertTrue(runner.contains("process.waitForTimeout(timeoutMillis, TimeUnit.MILLISECONDS)"))
        assertFalse(runner.contains("process.waitFor("))
    }

    @Test(timeout = 15000) fun realProcessPipeSmoke_tableDriven() = runBlocking {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable").absolutePath
        for ((argument, succeeds) in listOf("-version" to true, "-invalid-permission-runner-test" to false)) {
            val process = ProcessBuilder(java, argument).start()
            // Local JVM processes support the standard timed wait; only this smoke test uses it.
            val result = runPermissionCommand(process, waitForProcess = { child, timeout ->
                child.waitFor(timeout, TimeUnit.MILLISECONDS)
            })
            assertNotNull(result)
            assertEquals(succeeds, result!!.exitCode == 0)
            assertFalse(process.isAlive)
        }
    }
}
