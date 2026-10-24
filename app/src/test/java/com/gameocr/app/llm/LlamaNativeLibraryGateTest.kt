package com.gameocr.app.llm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaNativeLibraryGateTest {
    @Test
    fun firstCallAndRetry_loadBeforeNativeAndReuseSuccessfulLoad() {
        data class Case(val name: String, val failures: Int, val callsAfterSuccess: Int)
        listOf(
            Case("cold start", 0, 1),
            Case("repeated calls", 0, 6),
            Case("one failed load", 1, 2),
            Case("multiple failed loads", 2, 3),
        ).forEach { case ->
            var loads = 0
            var nativeCalls = 0
            var libraryReady = false
            val gate = LlamaNativeLibraryGate {
                loads++
                if (loads <= case.failures) throw UnsatisfiedLinkError("test load failure")
                libraryReady = true
            }
            repeat(case.failures) {
                val failure = runCatching { gate.call { nativeCalls++ } }.exceptionOrNull()
                assertTrue(case.name, failure is UnsatisfiedLinkError)
                assertEquals(case.name, 0, nativeCalls)
            }
            repeat(case.callsAfterSuccess) {
                val value = gate.call {
                    assertTrue(case.name, libraryReady)
                    nativeCalls++
                    "native result"
                }
                assertEquals(case.name, "native result", value)
            }
            assertEquals(case.name, case.failures + 1, loads)
            assertEquals(case.name, case.callsAfterSuccess, nativeCalls)
        }
    }

    @Test
    fun concurrentCalls_waitForSameLoadBeforeEnteringNative() {
        val loadStarted = CountDownLatch(1)
        val allowLoad = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val loads = AtomicInteger()
        val nativeCalls = AtomicInteger()
        val gate = LlamaNativeLibraryGate {
            loads.incrementAndGet()
            loadStarted.countDown()
            check(allowLoad.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> { gate.call { nativeCalls.incrementAndGet() } }
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Int> {
                secondStarted.countDown()
                gate.call { nativeCalls.incrementAndGet() }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertEquals(0, nativeCalls.get())
            allowLoad.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(1, loads.get())
            assertEquals(2, nativeCalls.get())
        } finally {
            allowLoad.countDown()
            executor.shutdownNow()
        }
    }
}
