package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainCaptureGalleryCarouselTest {

    @Test
    fun `page mapping alternates forever in both directions`() {
        data class Case(
            val name: String,
            val page: Int,
            val expectedIndex: Int,
        )

        listOf(
            Case("first page is capture", 0, 0),
            Case("second page is gallery", 1, 1),
            Case("third page loops to capture", 2, 0),
            Case("fourth page loops to gallery", 3, 1),
            Case("large even page is capture", Int.MAX_VALUE - 1, 0),
            Case("large odd page is gallery", Int.MAX_VALUE, 1),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedIndex,
                captureGalleryCarouselPageIndex(case.page),
            )
        }
    }

    @Test
    fun `initial page restores the requested face with room to swipe both ways`() {
        data class Case(
            val name: String,
            val savedPageIndex: Int,
            val expectedPageIndex: Int,
        )

        listOf(
            Case("first visit starts on capture", 0, 0),
            Case("return from task list restores gallery", 1, 1),
            Case("even values normalize to capture", 2, 0),
            Case("negative odd values normalize to gallery", -1, 1),
        ).forEach { case ->
            val initialPage = captureGalleryCarouselInitialPage(case.savedPageIndex)

            assertEquals(
                case.name,
                case.expectedPageIndex,
                captureGalleryCarouselPageIndex(initialPage),
            )
            assertTrue("${case.name}: has pages before", initialPage > 1_000)
            assertTrue(
                "${case.name}: has pages after",
                Int.MAX_VALUE - initialPage > 1_000,
            )
        }
    }

    @Test
    fun `horizontal cube rotation is clamped and table driven`() {
        data class Case(
            val name: String,
            val offset: Float,
            val expectedDegrees: Float,
        )

        listOf(
            Case("far previous page clamps", -2f, -90f),
            Case("previous page is edge on", -1f, -90f),
            Case("previous half turn", -0.5f, -45f),
            Case("settled page faces forward", 0f, 0f),
            Case("next half turn", 0.5f, 45f),
            Case("next page is edge on", 1f, 90f),
            Case("far next page clamps", 2f, 90f),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedDegrees,
                captureGalleryCarouselRotation(case.offset),
                0.001f,
            )
        }
    }

    @Test
    fun `carousel uses the taller natural page height`() {
        data class Case(
            val name: String,
            val captureHeightPx: Int,
            val galleryHeightPx: Int,
            val expectedHeightPx: Int?,
        )

        listOf(
            Case("waits for capture measurement", 0, 196, null),
            Case("waits for gallery measurement", 360, 0, null),
            Case("rejects invalid measurements", -1, -1, null),
            Case("capture page is taller", 360, 196, 360),
            Case("gallery page is taller", 180, 240, 240),
            Case("equal pages keep their height", 220, 220, 220),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedHeightPx,
                captureGalleryCarouselCommonHeightPx(
                    captureHeightPx = case.captureHeightPx,
                    galleryHeightPx = case.galleryHeightPx,
                ),
            )
        }
    }

    @Test
    fun `carousel source contract is horizontal infinite with a bottom indicator`() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val start = source.indexOf("private fun CaptureGalleryCarousel(")
        val end = source.indexOf("private const val CAPTURE_GALLERY_PAGE_COUNT", start)
        assertTrue("carousel function exists", start >= 0 && end > start)
        val carousel = source.substring(start, end)

        data class Case(
            val name: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        listOf(
            Case("uses horizontal pager", "HorizontalPager(", true),
            Case("offers an effectively infinite page range", "{ Int.MAX_VALUE }", true),
            Case("starts from the hoisted page", "initialPageIndex", true),
            Case(
                "reports the settled page after scroll state changes",
                "pagerState.isScrollInProgress to pagerState.settledPage",
                true,
            ),
            Case("reports the logical page index", "currentOnPageChanged(", true),
            Case("rotates around the Y axis", "rotationY =", true),
            Case("measures both cards", ".onSizeChanged", true),
            Case("remeasures capture content changes", "LaunchedEffect(captureMeasurementKey)", true),
            Case("uses the larger measured height", "captureGalleryCarouselCommonHeightPx(", true),
            Case("stretches the shorter card", "Modifier.fillMaxHeight()", true),
            Case("does not use vertical pager", "VerticalPager(", false),
            Case("draws one marker for each page", "repeat(CAPTURE_GALLERY_PAGE_COUNT)", true),
            Case("uses a pill and dot indicator shape", "shape = CircleShape", true),
            Case("places the indicator below the cards", ".padding(top = 8.dp)", true),
            Case("does not keep a fixed carousel height", "CAPTURE_GALLERY_CAROUSEL_HEIGHT", false),
        ).forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.marker in carousel)
        }
    }

    @Test
    fun `horizontal discovery eligibility is table driven`() {
        data class Case(
            val name: String,
            val hintEnabled: Boolean,
            val hintAlreadyPlayed: Boolean,
            val hostVisible: Boolean,
            val isScrollInProgress: Boolean,
            val itemCount: Int,
            val expected: Boolean,
        )

        listOf(
            Case("unseen idle carousel with alternatives", true, false, true, false, 2, true),
            Case("persisted discovery disables the hint", false, false, true, false, 2, false),
            Case("same-session replay is suppressed", true, true, true, false, 2, false),
            Case("off-screen carousel cannot show a guide", true, false, false, false, 2, false),
            Case("active scrolling hides the guide", true, false, true, true, 2, false),
            Case("a single preset has nothing to discover", true, false, true, false, 1, false),
            Case("an empty carousel is rejected", true, false, true, false, 0, false),
            Case("a corrupt negative count is rejected", true, false, true, false, -1, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldRunHorizontalDiscoveryHint(
                    hintEnabled = case.hintEnabled,
                    hintAlreadyPlayed = case.hintAlreadyPlayed,
                    hostVisible = case.hostVisible,
                    isScrollInProgress = case.isScrollInProgress,
                    itemCount = case.itemCount,
                ),
            )
        }
    }

    @Test
    fun `main gesture guides wait until the floating bubble tour finishes`() {
        data class Case(
            val name: String,
            val floatingTourCompleted: Boolean,
            val autoChecking: Boolean,
            val sharePromptVisible: Boolean,
            val updateDialogVisible: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("bubble tour completed and main screen is idle", true, false, false, false, true),
            Case("bubble tour is still active", false, false, false, false, false),
            Case("update check blocks guides", true, true, false, false, false),
            Case("share prompt blocks guides", true, false, true, false, false),
            Case("update result dialog blocks guides", true, false, false, true, false),
            Case("all blockers are active", false, true, true, true, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldEnableMainGestureGuides(
                    floatingTourCompleted = case.floatingTourCompleted,
                    autoChecking = case.autoChecking,
                    sharePromptVisible = case.sharePromptVisible,
                    updateDialogVisible = case.updateDialogVisible,
                ),
            )
        }
    }

    @Test
    fun `horizontal guide placement keeps presets centered and lowers capture`() {
        data class Case(
            val name: String,
            val host: HorizontalDiscoveryGuideHost,
            val expectedYOffsetDp: Int,
        )

        listOf(
            Case("preset guide stays centered", HorizontalDiscoveryGuideHost.PRESET, 0),
            Case(
                "capture service guide moves slightly lower",
                HorizontalDiscoveryGuideHost.CAPTURE_SERVICE,
                24,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedYOffsetDp,
                horizontalDiscoveryGuideYOffsetDp(case.host),
            )
        }
    }

    @Test
    fun `preset and capture horizontal guides persist only after a real switch`() {
        val main = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val repository =
            sourceFile("src/main/java/com/gameocr/app/data/SettingsRepository.kt").readText()
        val floatingTourPrefs =
            sourceFile("src/main/java/com/gameocr/app/overlay/FloatingMenuTourPrefs.kt").readText()

        data class Case(val name: String, val content: String, val marker: String)

        listOf(
            Case("capture card draws a horizontal guide", main, "SwipeHorizontalGuide("),
            Case(
                "a settled page must differ from the scroll start",
                main,
                "settledPage != discoveryScrollStartPage",
            ),
            Case("preset switch is persisted", main, "viewModel.markMainPresetCarouselSeen()"),
            Case("capture switch is persisted", main, "viewModel.markMainCaptureGallerySeen()"),
            Case(
                "all main guides observe floating tour completion",
                main,
                "FloatingMenuTourPrefs.observeCompletion(context)",
            ),
            Case(
                "floating tour completion is observable without polling",
                floatingTourPrefs,
                "registerOnSharedPreferenceChangeListener(listener)",
            ),
            Case(
                "preset discovery owns a durable preference",
                repository,
                "booleanPreferencesKey(\"main_preset_carousel_seen\")",
            ),
            Case(
                "capture discovery owns a durable preference",
                repository,
                "booleanPreferencesKey(\"main_capture_gallery_seen\")",
            ),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", case.marker in case.content)
        }
    }

    @Test
    fun `main route hoists the carousel page across navigation`() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainActivity.kt").readText()

        data class Case(
            val name: String,
            val marker: String,
        )

        listOf(
            Case(
                "route container saves the logical page",
                "mainCarouselPageIndex by rememberSaveable",
            ),
            Case(
                "main screen restores the saved page",
                "initialCarouselPageIndex = mainCarouselPageIndex",
            ),
            Case(
                "settled page changes update the saved page",
                "onCarouselPageChanged = { mainCarouselPageIndex = it }",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.marker in source)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
