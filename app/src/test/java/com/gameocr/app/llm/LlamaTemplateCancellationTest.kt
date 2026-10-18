package com.gameocr.app.llm

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LlamaTemplateCancellationTest {
    @Test fun cancellationJoinsNativeBeforeReleasing_thenNextRequestWorks() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            withCancellableLlamaRequest({ 7L }, { cancelled.set(true) }, {
                assertTrue("native must finish before release", stopped.get())
                released.set(true)
            }) {
                started.countDown()
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!cancelled.get() && System.nanoTime() < until) Thread.sleep(1)
                stopped.set(true)
                "discarded"
            }
            fail("cancelled result must not be published")
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(cancelled.get())
        assertTrue(released.get())
        assertEquals("next", withCancellableLlamaRequest({ 8L }, {}, {}) { "next" })
    }

    @Test fun completedAndFailedRequestsAlwaysRelease_tableDriven() = runBlocking {
        for (fail in listOf(false, true)) {
            var releases = 0
            runCatching {
                withCancellableLlamaRequest({ 1L }, {}, { releases++ }) {
                    if (fail) error("native failure") else "ok"
                }
            }.also { assertEquals(fail, it.isFailure) }
            assertEquals(1, releases)
        }
    }
}
