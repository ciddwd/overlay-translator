package com.gameocr.app.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTranslationExportPolicyTest {

    @Test
    fun `export availability covers terminal active and empty tasks`() {
        data class Case(
            val name: String,
            val status: GalleryTaskStatus,
            val successCount: Int,
            val expected: Boolean,
        )

        listOf(
            Case("queued result is unstable", GalleryTaskStatus.QUEUED, 1, false),
            Case("running result is unstable", GalleryTaskStatus.RUNNING, 1, false),
            Case("waiting retry is unstable", GalleryTaskStatus.WAITING_RETRY, 1, false),
            Case("partial task exports successes", GalleryTaskStatus.PARTIAL, 2, true),
            Case("successful task exports", GalleryTaskStatus.SUCCEEDED, 2, true),
            Case("failed task can export an earlier success", GalleryTaskStatus.FAILED, 1, true),
            Case("canceled task can export completed items", GalleryTaskStatus.CANCELED, 1, true),
            Case("terminal task without successes is hidden", GalleryTaskStatus.FAILED, 0, false),
            Case("negative count is treated as empty", GalleryTaskStatus.SUCCEEDED, -1, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryCanExport(case.status, case.successCount),
            )
        }
    }

    @Test
    fun `translated filenames are ordered sanitized and always png`() {
        data class Case(
            val name: String,
            val position: Int,
            val displayName: String,
            val expected: String,
        )

        listOf(
            Case("first jpeg", 0, "scene.jpg", "scene_translated_001.png"),
            Case("position keeps task order", 11, "scene.webp", "scene_translated_012.png"),
            Case("unsafe characters", 1, "a/b:c?.jpg", "a_b_c__translated_002.png"),
            Case("blank stem falls back", 2, ".jpg", "image_translated_003.png"),
            Case("negative position is clamped", -3, "shot", "shot_translated_001.png"),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryTranslatedFileName(case.position, case.displayName),
            )
        }
        assertEquals(
            "long names are bounded",
            80,
            galleryTranslatedFileName(0, "a".repeat(120) + ".jpg")
                .removeSuffix("_translated_001.png")
                .length,
        )
    }

    @Test
    fun `segment rectangles scale outward and clip to the export bitmap`() {
        data class Case(
            val name: String,
            val rect: GalleryRect,
            val sourceWidth: Int,
            val sourceHeight: Int,
            val targetWidth: Int,
            val targetHeight: Int,
            val expected: GalleryRect?,
        )

        listOf(
            Case(
                "same dimensions",
                GalleryRect(10, 20, 30, 40),
                100,
                100,
                100,
                100,
                GalleryRect(10, 20, 30, 40),
            ),
            Case(
                "downscale",
                GalleryRect(20, 40, 80, 160),
                200,
                200,
                100,
                100,
                GalleryRect(10, 20, 40, 80),
            ),
            Case(
                "fractional coordinates expand outward",
                GalleryRect(1, 1, 2, 2),
                3,
                3,
                10,
                10,
                GalleryRect(3, 3, 7, 7),
            ),
            Case(
                "partly outside is clipped",
                GalleryRect(-20, -10, 120, 80),
                100,
                100,
                200,
                200,
                GalleryRect(0, 0, 200, 160),
            ),
            Case(
                "wholly outside is rejected",
                GalleryRect(120, 10, 140, 20),
                100,
                100,
                200,
                200,
                null,
            ),
            Case(
                "invalid source dimensions",
                GalleryRect(1, 1, 2, 2),
                0,
                100,
                200,
                200,
                null,
            ),
            Case(
                "reversed rectangle",
                GalleryRect(20, 10, 10, 30),
                100,
                100,
                200,
                200,
                null,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                scaledGalleryRect(
                    rect = case.rect,
                    sourceWidth = case.sourceWidth,
                    sourceHeight = case.sourceHeight,
                    targetWidth = case.targetWidth,
                    targetHeight = case.targetHeight,
                ),
            )
        }
    }

    @Test
    fun `erase rectangles add safe margins without leaving the bitmap`() {
        data class Case(
            val name: String,
            val rect: GalleryRect,
            val width: Int,
            val height: Int,
            val expected: GalleryRect,
        )

        listOf(
            Case(
                "small text gets minimum margin",
                GalleryRect(10, 10, 20, 20),
                100,
                100,
                GalleryRect(8, 8, 22, 22),
            ),
            Case(
                "large text margin is capped",
                GalleryRect(50, 50, 250, 250),
                400,
                400,
                GalleryRect(38, 38, 262, 262),
            ),
            Case(
                "edge expansion is clipped",
                GalleryRect(0, 1, 20, 21),
                100,
                100,
                GalleryRect(0, 0, 22, 23),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                expandedGalleryEraseRect(case.rect, case.width, case.height),
            )
        }
    }

    @Test
    fun `export text normalization preserves intentional lines`() {
        data class Case(
            val name: String,
            val input: String,
            val expected: String,
        )

        listOf(
            Case("blank", " \r\n ", ""),
            Case("crlf", "第一行\r\n第二行", "第一行\n第二行"),
            Case("legacy carriage return", "一\r二", "一\n二"),
            Case("spaces within lines collapse", "  one   two \n three\t four ", "one two\nthree four"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, normalizeGalleryExportText(case.input))
        }
    }

    @Test
    fun `text size search returns the largest fitting candidate`() {
        data class Case(
            val name: String,
            val minimum: Int,
            val maximum: Int,
            val largestFit: Int,
            val expected: Int,
        )

        listOf(
            Case("middle fit", 4, 20, 13, 13),
            Case("maximum fits", 4, 20, 20, 20),
            Case("only minimum fits", 4, 20, 4, 4),
            Case("nothing fits keeps safe minimum", 4, 20, 0, 4),
            Case("inverted range normalizes", 8, 4, 8, 8),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                largestFittingTextSize(case.minimum, case.maximum) {
                    it <= case.largestFit
                },
            )
        }
    }
}
