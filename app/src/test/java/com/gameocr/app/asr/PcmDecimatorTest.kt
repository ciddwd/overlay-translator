package com.gameocr.app.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PcmDecimatorTest {
    private fun tone(hz: Double, rate: Int, count: Int) =
        FloatArray(count) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(values: FloatArray, skip: Int = 0): Double {
        val tail = values.drop(skip)
        return sqrt(tail.sumOf { it.toDouble() * it } / tail.size)
    }

    @Test fun blockSplitsNeverChangeOutput_tableDriven() {
        val input = tone(440.0, 48_000, 4_800)
        val whole = PcmDecimator(3).process(input)
        for (blocks in listOf(listOf(4_800), listOf(1, 4_799), listOf(1_600, 1_600, 1_600), listOf(7, 11, 13, 4_769), List(4_800) { 1 })) {
            val decimator = PcmDecimator(3)
            var offset = 0
            val pieces = blocks.map { size ->
                decimator.process(input.copyOfRange(offset, offset + size)).also { offset += size }
            }
            val joined = pieces.fold(FloatArray(0)) { acc, piece -> acc + piece }
            assertArrayEquals("blocks=${blocks.take(4)}", whole, joined, 1e-6f)
        }
        assertEquals(1_600, whole.size)
    }

    @Test fun passesSpeechBandAndRejectsAliasingBand_tableDriven() {
        data class Case(val hz: Double, val minGain: Double, val maxGain: Double)
        listOf(
            Case(0.0, 0.99, 1.01),
            Case(1_000.0, 0.95, 1.05),
            Case(3_000.0, 0.9, 1.1),
            // Above the 8 kHz output Nyquist: must not fold back into the speech band.
            Case(12_000.0, 0.0, 0.02),
            Case(20_000.0, 0.0, 0.02),
        ).forEach { case ->
            val input = if (case.hz == 0.0) FloatArray(9_600) { 0.5f } else tone(case.hz, 48_000, 9_600)
            val output = PcmDecimator(3).process(input)
            val gain = rms(output, skip = 64) / rms(input)
            assertTrue("${case.hz} Hz gain=$gain", gain in case.minGain..case.maxGain)
        }
    }

    @Test fun factorOneAndResetAreIdentityAndRestart() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertArrayEquals(input, PcmDecimator(1).process(input), 0f)
        val decimator = PcmDecimator(3)
        val first = decimator.process(tone(500.0, 48_000, 300))
        decimator.reset()
        val again = decimator.process(tone(500.0, 48_000, 300))
        assertArrayEquals(first, again, 0f)
        assertEquals(0, PcmDecimator(3).process(FloatArray(0)).size)
        assertTrue(abs(PcmDecimator(3).process(FloatArray(30)).sum()) < 1e-9f)
    }
}
