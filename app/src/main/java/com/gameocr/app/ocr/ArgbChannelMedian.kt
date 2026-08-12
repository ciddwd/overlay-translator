package com.gameocr.app.ocr

/** Exact upper-median selection for each ARGB channel without sorting sample arrays. */
internal object ArgbChannelMedian {

    fun fromIndexedColors(
        sourceArgb: IntArray,
        sampleIndices: IntArray,
    ): Int {
        require(sampleIndices.isNotEmpty())
        val alpha = IntArray(CHANNEL_VALUES)
        val red = IntArray(CHANNEL_VALUES)
        val green = IntArray(CHANNEL_VALUES)
        val blue = IntArray(CHANNEL_VALUES)
        sampleIndices.forEach { index ->
            val color = sourceArgb[index]
            alpha[color ushr 24 and CHANNEL_MASK]++
            red[color ushr 16 and CHANNEL_MASK]++
            green[color ushr 8 and CHANNEL_MASK]++
            blue[color and CHANNEL_MASK]++
        }
        val rank = sampleIndices.size / 2
        return argb(
            alpha = valueAtRank(alpha, rank),
            red = valueAtRank(red, rank),
            green = valueAtRank(green, rank),
            blue = valueAtRank(blue, rank),
        )
    }

    private fun valueAtRank(histogram: IntArray, rank: Int): Int {
        var cumulative = 0
        histogram.forEachIndexed { value, count ->
            cumulative += count
            if (cumulative > rank) return value
        }
        error("rank exceeds histogram population")
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private const val CHANNEL_VALUES = 256
    private const val CHANNEL_MASK = 0xff
}
