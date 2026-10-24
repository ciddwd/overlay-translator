package com.gameocr.app.ocr

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PaddleRecognitionPipelineTest {
    private val executor = Executors.newSingleThreadExecutor()

    @After fun shutDown() {
        executor.shutdownNow()
    }

    @Test fun consumesEveryBatchInOrderWithTheSameItemsAsASerialLoop() {
        val batches = listOf(listOf(3, 1), listOf(0), listOf(2, 4))
        val consumed = mutableListOf<Pair<Int, List<Int>>>()
        val prepared = mutableListOf<Int>()
        runPipelinedBatches(batches.size, executor, prepared, { batches[it] }) { index, items ->
            consumed += index to items
        }
        assertEquals(batches.mapIndexed { index, items -> index to items }, consumed)
        assertEquals(batches.flatten(), prepared)
    }

    @Test fun preparesTheNextBatchWhileTheCurrentOneIsConsumed() {
        val nextBatchStarted = CountDownLatch(1)
        val prepared = mutableListOf<Int>()
        runPipelinedBatches(2, executor, prepared, { index ->
            if (index == 1) nextBatchStarted.countDown()
            listOf(index)
        }) { index, _ ->
            // A serial loop would only prepare batch 1 after this returns, and time out here.
            if (index == 0) assertTrue(nextBatchStarted.await(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf(0, 1), prepared)
    }

    @Test fun batchPreparedAheadOfAConsumerFailureIsStillHandedBack() {
        val prepared = Collections.synchronizedList(mutableListOf<Int>())
        try {
            runPipelinedBatches(3, executor, prepared, { listOf(it * 10) }) { index, _ ->
                if (index == 0) error("recognition failed")
            }
            fail("expected the consumer failure")
        } catch (failure: IllegalStateException) {
            assertEquals("recognition failed", failure.message)
        }
        // Batch 0 was consumed, batch 1 was already being cut; batch 2 was never started.
        assertEquals(listOf(0, 10), prepared)
    }

    @Test fun prepareFailureSurfacesItsOwnCause() {
        val prepared = mutableListOf<Int>()
        try {
            runPipelinedBatches(2, executor, prepared, { index ->
                if (index == 1) throw IllegalArgumentException("warp failed") else listOf(index)
            }) { _, _ -> }
            fail("expected the prepare failure")
        } catch (failure: IllegalArgumentException) {
            assertEquals("warp failed", failure.message)
        }
        assertEquals(listOf(0), prepared)
    }

    @Test fun noBatchesDoNothing() {
        val prepared = mutableListOf<Int>()
        runPipelinedBatches(0, executor, prepared, { error("unused") }) { _, _ -> error("unused") }
        assertTrue(prepared.isEmpty())
    }

    @Test fun plannedWidthMatchesTheCropAfterVerticalRotation_tableDriven() {
        data class Case(val width: Int, val height: Int, val rotated: Boolean)
        listOf(
            Case(1126, 62, rotated = false),
            Case(50, 72, rotated = false),
            Case(60, 400, rotated = true),
            Case(385, 177, rotated = false),
        ).forEach { case ->
            val (w, h) = if (case.rotated) case.height to case.width else case.width to case.height
            assertEquals(case.toString(), PaddleRecognitionSizing.plan(w, h).targetWidth,
                paddlePlannedRecognitionWidth(case.width, case.height))
        }
    }

    @Test fun keptCropSizeDropsWhatTheWarpWouldDrop() {
        fun points(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
        assertEquals(listOf(100, 20), PaddleRotateCrop.keptCropSize(points(10f, 10f, 110f, 30f), 200, 200)!!.toList())
        assertNull(PaddleRotateCrop.keptCropSize(points(10f, 10f, 10.5f, 30f), 200, 200))
        assertNull(PaddleRotateCrop.keptCropSize(points(0f, 0f, 5000f, 30f), 6000, 200))
    }
}
