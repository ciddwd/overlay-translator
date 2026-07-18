package com.gameocr.app.util

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small best-fit pool for native-order direct buffers used by ONNX Runtime.
 *
 * A leased buffer is never returned to the pool until [Closeable.close], so overlapping warmup and
 * real OCR calls cannot mutate memory that is still backing an active tensor.
 */
internal class ReusableDirectBufferPool(
    private val maxRetainedFloatBuffers: Int = 4,
    private val maxRetainedLongBuffers: Int = 2,
) {
    private val lock = Any()
    private val availableFloatBuffers = mutableListOf<FloatBuffer>()
    private val availableLongBuffers = mutableListOf<LongBuffer>()

    init {
        require(maxRetainedFloatBuffers >= 0) { "maxRetainedFloatBuffers must be >= 0" }
        require(maxRetainedLongBuffers >= 0) { "maxRetainedLongBuffers must be >= 0" }
    }

    fun acquireFloat(requiredElements: Int): FloatLease {
        requireElementCount(requiredElements, Float.SIZE_BYTES)
        val acquired = synchronized(lock) {
            val index = bestFitFloatIndex(requiredElements)
            if (index >= 0) {
                AcquiredFloat(availableFloatBuffers.removeAt(index), reused = true)
            } else {
                AcquiredFloat(allocateFloat(requiredElements), reused = false)
            }
        }
        acquired.buffer.clear()
        acquired.buffer.limit(requiredElements)
        return FloatLease(
            owner = this,
            buffer = acquired.buffer,
            reused = acquired.reused,
            requiredElements = requiredElements,
        )
    }

    fun acquireLong(requiredElements: Int): LongLease {
        requireElementCount(requiredElements, Long.SIZE_BYTES)
        val acquired = synchronized(lock) {
            val index = bestFitLongIndex(requiredElements)
            if (index >= 0) {
                AcquiredLong(availableLongBuffers.removeAt(index), reused = true)
            } else {
                AcquiredLong(allocateLong(requiredElements), reused = false)
            }
        }
        acquired.buffer.clear()
        acquired.buffer.limit(requiredElements)
        return LongLease(
            owner = this,
            buffer = acquired.buffer,
            reused = acquired.reused,
            requiredElements = requiredElements,
        )
    }

    fun clear() {
        synchronized(lock) {
            availableFloatBuffers.clear()
            availableLongBuffers.clear()
        }
    }

    private fun bestFitFloatIndex(requiredElements: Int): Int {
        var bestIndex = -1
        var bestCapacity = Int.MAX_VALUE
        availableFloatBuffers.forEachIndexed { index, buffer ->
            if (buffer.capacity() >= requiredElements && buffer.capacity() < bestCapacity) {
                bestIndex = index
                bestCapacity = buffer.capacity()
            }
        }
        return bestIndex
    }

    private fun bestFitLongIndex(requiredElements: Int): Int {
        var bestIndex = -1
        var bestCapacity = Int.MAX_VALUE
        availableLongBuffers.forEachIndexed { index, buffer ->
            if (buffer.capacity() >= requiredElements && buffer.capacity() < bestCapacity) {
                bestIndex = index
                bestCapacity = buffer.capacity()
            }
        }
        return bestIndex
    }

    private fun release(buffer: FloatBuffer) {
        buffer.clear()
        synchronized(lock) {
            if (availableFloatBuffers.size < maxRetainedFloatBuffers) {
                availableFloatBuffers += buffer
            }
        }
    }

    private fun release(buffer: LongBuffer) {
        buffer.clear()
        synchronized(lock) {
            if (availableLongBuffers.size < maxRetainedLongBuffers) {
                availableLongBuffers += buffer
            }
        }
    }

    internal class FloatLease internal constructor(
        private val owner: ReusableDirectBufferPool,
        val buffer: FloatBuffer,
        val reused: Boolean,
        val requiredElements: Int,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        val capacityElements: Int get() = buffer.capacity()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(buffer)
            }
        }
    }

    internal class LongLease internal constructor(
        private val owner: ReusableDirectBufferPool,
        val buffer: LongBuffer,
        val reused: Boolean,
        val requiredElements: Int,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        val capacityElements: Int get() = buffer.capacity()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(buffer)
            }
        }
    }

    private data class AcquiredFloat(
        val buffer: FloatBuffer,
        val reused: Boolean,
    )

    private data class AcquiredLong(
        val buffer: LongBuffer,
        val reused: Boolean,
    )

    private companion object {
        fun requireElementCount(requiredElements: Int, bytesPerElement: Int) {
            require(requiredElements > 0) { "requiredElements must be > 0" }
            require(requiredElements <= Int.MAX_VALUE / bytesPerElement) {
                "requiredElements is too large for a direct ByteBuffer"
            }
        }

        fun allocateFloat(elements: Int): FloatBuffer =
            ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        fun allocateLong(elements: Int): LongBuffer =
            ByteBuffer.allocateDirect(elements * Long.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asLongBuffer()
    }
}
