package com.gameocr.app.network

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test

class ScreenWakeConnectionCleanerTest {
    @Test
    fun defaultDispatcher_cleansOffCallerThreadAndDoesNotBlockCaller() {
        val caller = Thread.currentThread()
        val worker = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        val result = AtomicReference<Pair<Long?, Int>>()
        val cleaner = ScreenWakeConnectionCleaner(
            evictIdleConnections = {
                worker.set(Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                3
            },
            onRecovered = { duration, count -> result.set(duration to count); completed.countDown() },
            onFailure = { failure.set(it); completed.countDown() },
        )
        try {
            cleaner.start()
            cleaner.enqueue(800L)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, worker.get())
            assertEquals(1L, completed.count)
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(800L to 3, result.get())
        } finally {
            release.countDown()
            cleaner.stop()
        }
    }

    @Test
    fun lifecycle_tableDriven_dropsStoppedTasksAndAllowsRestart() {
        data class Case(val actions: String, val expected: Int)
        listOf(
            Case("enqueue,drain", 0),
            Case("start,enqueue,drain", 1),
            Case("start,start,enqueue,drain", 1),
            Case("start,enqueue,stop,drain", 0),
            Case("start,stop,stop,enqueue,drain", 0),
            Case("start,enqueue,stop,start,enqueue,drain", 1),
        ).forEach { case ->
            val dispatcher = QueuedDispatcher()
            var cleaned = 0
            var reported = 0
            val cleaner = ScreenWakeConnectionCleaner(
                { ++cleaned }, { _, _ -> reported++ }, { throw AssertionError(it) }, dispatcher,
            )
            try {
                case.actions.split(',').forEach {
                    when (it) {
                        "start" -> cleaner.start()
                        "stop" -> cleaner.stop()
                        "enqueue" -> cleaner.enqueue(null)
                        "drain" -> dispatcher.drain()
                    }
                }
                assertEquals(case.actions, case.expected, cleaned)
                assertEquals(case.actions, case.expected, reported)
            } finally {
                cleaner.stop()
                dispatcher.drain()
            }
        }
    }

    @Test
    fun cleanupFailure_isReportedWithoutBreakingNextRecovery() {
        val dispatcher = QueuedDispatcher()
        val failure = IOException("socket close failed")
        val errors = mutableListOf<Exception>()
        val recovered = mutableListOf<Pair<Long?, Int>>()
        var attempts = 0
        val cleaner = ScreenWakeConnectionCleaner(
            { if (++attempts == 1) throw failure else 0 },
            { duration, count -> recovered += duration to count },
            { errors += it }, dispatcher,
        )
        try {
            cleaner.start()
            cleaner.enqueue(10L)
            cleaner.enqueue(20L)
            dispatcher.drain()
            assertEquals(listOf(failure), errors)
            assertEquals(listOf(20L to 0), recovered)
        } finally {
            cleaner.stop()
        }
    }

    @Test
    fun stopDuringSocketClose_doesNotPublishStaleResult() {
        val dispatcher = QueuedDispatcher()
        var reported = 0
        lateinit var cleaner: ScreenWakeConnectionCleaner
        cleaner = ScreenWakeConnectionCleaner(
            { cleaner.stop(); cleaner.start(); 1 },
            { _, _ -> reported++ }, { throw AssertionError(it) }, dispatcher,
        )
        try {
            cleaner.start()
            cleaner.enqueue(10L)
            dispatcher.drain()
            assertEquals(0, reported)
        } finally {
            cleaner.stop()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }
        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
    }
}
