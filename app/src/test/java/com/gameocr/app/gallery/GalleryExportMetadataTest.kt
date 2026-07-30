package com.gameocr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryExportMetadataTest {

    @Test
    fun `export metadata identifies the author and generating software`() {
        val metadata = galleryExportMetadata()

        data class Case(
            val field: String,
            val actual: String,
        )

        listOf(
            Case("artist", metadata.artist),
            Case("software", metadata.software),
        ).forEach { case ->
            assertEquals(
                case.field,
                "屏译 · Screen Translator",
                case.actual,
            )
        }
    }
}
