package com.gameocr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryImageSamplingTest {
    @Test
    fun screenCapturesDoNotLoseHalfTheirResolutionAt3072() {
        val budget = 3072L * 3072
        for ((width, height) in listOf(1440 to 3200, 3200 to 1440, 2160 to 3840, 1080 to 8000)) {
            assertEquals("${width}x$height", 1, galleryImageSampleSize(width, height, budget))
        }
        assertEquals(2, galleryImageSampleSize(6000, 4000, budget))
        assertEquals(4, galleryImageSampleSize(8000, 8000, budget))
    }

    @Test
    fun decodedAreaStaysWithinTheBudgetIncludingOddSizes() {
        for ((width, height) in listOf(6001 to 6001, 12000 to 2000, 10000 to 30000)) {
            val budget = 3072L * 3072
            val sample = galleryImageSampleSize(width, height, budget)
            val pixels = ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample)
            org.junit.Assert.assertTrue(pixels <= budget)
        }
    }
}
