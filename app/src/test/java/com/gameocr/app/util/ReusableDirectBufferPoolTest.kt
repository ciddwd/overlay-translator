package com.gameocr.app.util

import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReusableDirectBufferPoolTest {

    @Test
    fun acquire_tableDrivenBestFitReuseAndNativeOrderCases() {
        data class Case(
            val name: String,
            val kind: Kind,
            val retainedElements: Int,
            val requestedElements: Int,
            val expectedReuse: Boolean,
            val expectedCapacity: Int,
        )

        listOf(
            Case(
                name = "float exact capacity reuses",
                kind = Kind.FLOAT,
                retainedElements = 16,
                requestedElements = 16,
                expectedReuse = true,
                expectedCapacity = 16,
            ),
            Case(
                name = "float larger retained capacity serves smaller request",
                kind = Kind.FLOAT,
                retainedElements = 32,
                requestedElements = 12,
                expectedReuse = true,
                expectedCapacity = 32,
            ),
            Case(
                name = "float insufficient retained capacity allocates",
                kind = Kind.FLOAT,
                retainedElements = 8,
                requestedElements = 20,
                expectedReuse = false,
                expectedCapacity = 20,
            ),
            Case(
                name = "long exact capacity reuses",
                kind = Kind.LONG,
                retainedElements = 49,
                requestedElements = 49,
                expectedReuse = true,
                expectedCapacity = 49,
            ),
            Case(
                name = "long larger retained capacity serves token prefix",
                kind = Kind.LONG,
                retainedElements = 49,
                requestedElements = 7,
                expectedReuse = true,
                expectedCapacity = 49,
            ),
            Case(
                name = "long insufficient retained capacity allocates",
                kind = Kind.LONG,
                retainedElements = 4,
                requestedElements = 5,
                expectedReuse = false,
                expectedCapacity = 5,
            ),
        ).forEach { case ->
            val pool = ReusableDirectBufferPool()
            when (case.kind) {
                Kind.FLOAT -> {
                    val retained = pool.acquireFloat(case.retainedElements)
                    val retainedBuffer = retained.buffer
                    retained.close()

                    pool.acquireFloat(case.requestedElements).use { acquired ->
                        assertEquals(case.name, case.expectedReuse, acquired.reused)
                        assertEquals(case.name, case.expectedCapacity, acquired.capacityElements)
                        assertEquals(case.name, 0, acquired.buffer.position())
                        assertEquals(case.name, case.requestedElements, acquired.buffer.limit())
                        assertTrue(case.name, acquired.buffer.isDirect)
                        assertEquals(case.name, ByteOrder.nativeOrder(), acquired.buffer.order())
                        if (case.expectedReuse) {
                            assertSame(case.name, retainedBuffer, acquired.buffer)
                        } else {
                            assertNotSame(case.name, retainedBuffer, acquired.buffer)
                        }
                    }
                }

                Kind.LONG -> {
                    val retained = pool.acquireLong(case.retainedElements)
                    val retainedBuffer = retained.buffer
                    retained.close()

                    pool.acquireLong(case.requestedElements).use { acquired ->
                        assertEquals(case.name, case.expectedReuse, acquired.reused)
                        assertEquals(case.name, case.expectedCapacity, acquired.capacityElements)
                        assertEquals(case.name, 0, acquired.buffer.position())
                        assertEquals(case.name, case.requestedElements, acquired.buffer.limit())
                        assertTrue(case.name, acquired.buffer.isDirect)
                        assertEquals(case.name, ByteOrder.nativeOrder(), acquired.buffer.order())
                        if (case.expectedReuse) {
                            assertSame(case.name, retainedBuffer, acquired.buffer)
                        } else {
                            assertNotSame(case.name, retainedBuffer, acquired.buffer)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun acquire_doesNotShareCheckedOutBufferAndCloseIsIdempotent() {
        val pool = ReusableDirectBufferPool()
        val first = pool.acquireFloat(32)
        val firstBuffer = first.buffer
        val overlapping = pool.acquireFloat(32)
        assertFalse(overlapping.reused)
        assertNotSame(firstBuffer, overlapping.buffer)
        overlapping.close()

        first.close()
        first.close()

        val reused = pool.acquireFloat(32)
        val simultaneous = pool.acquireFloat(32)
        assertTrue(reused.reused)
        assertTrue(simultaneous.reused)
        assertNotSame(reused.buffer, simultaneous.buffer)
        reused.close()
        simultaneous.close()
    }

    @Test
    fun zeroRetention_neverReusesReleasedBuffers() {
        val pool = ReusableDirectBufferPool(
            maxRetainedFloatBuffers = 0,
            maxRetainedLongBuffers = 0,
        )
        val firstFloat = pool.acquireFloat(4)
        val firstFloatBuffer = firstFloat.buffer
        firstFloat.close()
        val secondFloat = pool.acquireFloat(4)
        assertFalse(secondFloat.reused)
        assertNotSame(firstFloatBuffer, secondFloat.buffer)
        secondFloat.close()

        val firstLong = pool.acquireLong(4)
        val firstLongBuffer = firstLong.buffer
        firstLong.close()
        val secondLong = pool.acquireLong(4)
        assertFalse(secondLong.reused)
        assertNotSame(firstLongBuffer, secondLong.buffer)
        secondLong.close()
    }

    private enum class Kind {
        FLOAT,
        LONG,
    }
}
