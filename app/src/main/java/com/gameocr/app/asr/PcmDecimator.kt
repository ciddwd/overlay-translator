package com.gameocr.app.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Integer-factor downsampler with a windowed-sinc anti-alias filter. Filter history and the
 * decimation phase carry across blocks, so splitting a stream into reads never changes the output.
 */
internal class PcmDecimator(private val factor: Int, taps: Int = 63) {
    private val coefficients: FloatArray
    private var history: FloatArray
    private var phase = 0

    init {
        require(factor >= 1) { "factor must be positive" }
        require(taps % 2 == 1) { "taps must be odd" }
        // Pass band ends at 90% of the output Nyquist frequency (cycles per input sample).
        val cutoff = 0.45 / factor
        val middle = (taps - 1) / 2.0
        val raw = DoubleArray(taps) { n ->
            val x = n - middle
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val hamming = 0.54 - 0.46 * cos(2 * PI * n / (taps - 1))
            sinc * hamming
        }
        val sum = raw.sum()
        coefficients = FloatArray(taps) { (raw[it] / sum).toFloat() }
        history = FloatArray(taps - 1)
    }

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (factor == 1) return input.copyOf(count)
        val buffer = FloatArray(history.size + count)
        history.copyInto(buffer)
        input.copyInto(buffer, history.size, 0, count)
        val output = FloatArray((count - phase + factor - 1).coerceAtLeast(0) / factor)
        var produced = 0
        var index = phase
        while (index < count) {
            var acc = 0f
            val newest = index + history.size
            for (k in coefficients.indices) acc += coefficients[k] * buffer[newest - k]
            output[produced++] = acc
            index += factor
        }
        phase = index - count
        history = buffer.copyOfRange(buffer.size - history.size, buffer.size)
        return if (produced == output.size) output else output.copyOf(produced)
    }

    fun reset() {
        history.fill(0f)
        phase = 0
    }
}
