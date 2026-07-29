package com.gameocr.app.ui

import com.gameocr.app.gallery.GalleryItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryResultFilterTest {

    @Test
    fun `result tabs preserve all work and filter terminal outcomes`() {
        data class Case(
            val name: String,
            val filter: GalleryResultFilter,
            val status: GalleryItemStatus,
            val expected: Boolean,
        )

        val statuses = GalleryItemStatus.entries
        val cases = buildList {
            statuses.forEach { status ->
                add(Case("all includes ${status.name}", GalleryResultFilter.ALL, status, true))
                add(
                    Case(
                        "success filter ${status.name}",
                        GalleryResultFilter.SUCCEEDED,
                        status,
                        status == GalleryItemStatus.SUCCEEDED,
                    )
                )
                add(
                    Case(
                        "failure filter ${status.name}",
                        GalleryResultFilter.FAILED,
                        status,
                        status == GalleryItemStatus.FAILED,
                    )
                )
            }
        }

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryResultFilterMatches(case.filter, case.status),
            )
        }
    }
}
