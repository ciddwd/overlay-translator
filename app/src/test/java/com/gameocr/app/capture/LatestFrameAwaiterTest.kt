package com.gameocr.app.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LatestFrameAwaiterTest {

    @Test
    fun awaitLatestFrame_tableDriven_returnsAvailableFrameAcrossOrderingCases() {
        data class Case(
            val name: String,
            val arrange: (FakeFrameSource) -> Unit,
            val trigger: suspend (FakeFrameSource) -> Unit,
            val expected: Int,
            val expectedListenerRegistered: Boolean
        )

        val cases = listOf(
            Case(
                name = "frame already queued before waiting",
                arrange = { it.enqueue(1) },
                trigger = {},
                expected = 1,
                expectedListenerRegistered = false
            ),
            Case(
                name = "frame arrives through listener callback",
                arrange = {},
                trigger = {
                    it.enqueue(2)
                    it.fireAvailable()
                },
                expected = 2,
                expectedListenerRegistered = true
            ),
            Case(
                name = "frame arrives during listener registration race window",
                arrange = { source ->
                    source.onRegisterListener = {
                        source.enqueue(3)
                    }
                },
                trigger = {},
                expected = 3,
                expectedListenerRegistered = true
            )
        )

        cases.forEach { case ->
            val source = FakeFrameSource()
            case.arrange(source)
            val actual = AtomicReference<Int?>()

            runBlocking {
                val job = launch(Dispatchers.Default) {
                    actual.set(awaitLatestFrame(source, timeoutMs = 500, closeUndelivered = source::close))
                }
                waitUntil("listener state for ${case.name}") {
                    actual.get() != null || source.listenerRegistered == case.expectedListenerRegistered
                }
                case.trigger(source)
                job.join()
            }

            assertEquals(case.name, case.expected, actual.get())
            assertNull("${case.name} should clear listener after delivery", source.listener)
            assertTrue("${case.name} should not close delivered frame", source.closed.isEmpty())
        }
    }

    @Test
    fun awaitLatestFrame_timeoutClearsListenerAndFinalDrainsRaceFrame() = runBlocking {
        data class Case(
            val name: String,
            val timeoutMs: Long,
            val onFinalAcquire: ((FakeFrameSource) -> Unit)?,
            val expected: Int?
        )

        val cases = listOf(
            Case(
                name = "timeout with no frame returns null",
                timeoutMs = 20,
                onFinalAcquire = null,
                expected = null
            ),
            Case(
                name = "frame queued at timeout boundary is still drained",
                timeoutMs = 20,
                onFinalAcquire = { it.enqueue(9) },
                expected = 9
            )
        )

        cases.forEach { case ->
            val source = FakeFrameSource()
            source.onAfterTimedOutAcquire = case.onFinalAcquire

            val actual = awaitLatestFrame(source, timeoutMs = case.timeoutMs, closeUndelivered = source::close)

            assertEquals(case.name, case.expected, actual)
            assertNull("${case.name} should clear listener", source.listener)
        }
    }

    private class FakeFrameSource : LatestFrameSource<Int> {
        private val frames = ArrayDeque<Int>()
        val closed = mutableListOf<Int>()
        var listener: (() -> Unit)? = null
            private set
        var listenerRegistered = false
            private set
        var onRegisterListener: (() -> Unit)? = null
        var onAfterTimedOutAcquire: ((FakeFrameSource) -> Unit)? = null
        private var sawListenerCleared = false

        @Synchronized
        override fun acquireLatest(): Int? {
            if (sawListenerCleared) {
                sawListenerCleared = false
                onAfterTimedOutAcquire?.invoke(this)
            }
            return frames.removeLastOrNull()
        }

        @Synchronized
        override fun setOnFrameAvailableListener(listener: (() -> Unit)?) {
            this.listener = listener
            if (listener == null) {
                sawListenerCleared = true
            } else {
                listenerRegistered = true
                onRegisterListener?.invoke()
            }
        }

        @Synchronized
        fun enqueue(frame: Int) {
            frames.addLast(frame)
        }

        @Synchronized
        fun fireAvailable() {
            listener?.invoke()
        }

        @Synchronized
        fun close(frame: Int) {
            closed += frame
        }
    }

    private fun waitUntil(name: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 1_000
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                error("Timed out waiting for $name")
            }
            Thread.sleep(5)
        }
    }
}
