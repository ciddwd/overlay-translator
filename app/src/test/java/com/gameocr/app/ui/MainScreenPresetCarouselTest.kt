package com.gameocr.app.ui

import com.gameocr.app.data.Settings as AppSettings
import com.gameocr.app.data.TranslationPresetCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenPresetCarouselTest {

    @Test
    fun carouselPlans_addUnsavedDraftOnlyWhenCurrentSettingsDoNotMatch() {
        val unsavedName = "未保存预设"
        val builtIn = TranslationPresetCatalog.builtIns().single()
        val customSnapshot = AppSettings(model = "custom-model")
        val custom = TranslationPresetCatalog.fromSettings(
            id = "custom-1",
            name = "Custom",
            shortName = "Custom",
            settings = customSnapshot,
        )
        val storedDraft = TranslationPresetCatalog.fromSettings(
            id = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
            name = "Old draft",
            shortName = "Old",
            settings = AppSettings(model = "old-draft-model"),
        )

        data class Case(
            val name: String,
            val settings: AppSettings,
            val expectedIds: List<String>,
            val expectedCurrentId: String,
            val expectsDraft: Boolean,
        )

        val cases = listOf(
            Case(
                name = "active built-in still matches",
                settings = builtIn.applyTo(AppSettings()).copy(
                    activeTranslationPresetId = builtIn.id,
                ),
                expectedIds = listOf(builtIn.id),
                expectedCurrentId = builtIn.id,
                expectsDraft = false,
            ),
            Case(
                name = "unmatched current settings become a draft",
                settings = AppSettings(model = "unmatched-model"),
                expectedIds = listOf(TranslationPresetCatalog.UNSAVED_DRAFT_ID, builtIn.id),
                expectedCurrentId = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                expectsDraft = true,
            ),
            Case(
                name = "matching custom preset does not duplicate a draft",
                settings = custom.applyTo(AppSettings()).copy(
                    translationPresets = listOf(custom),
                    activeTranslationPresetId = custom.id,
                ),
                expectedIds = listOf(builtIn.id, custom.id),
                expectedCurrentId = custom.id,
                expectsDraft = false,
            ),
            Case(
                name = "stale active id falls back to the matching custom preset",
                settings = custom.applyTo(AppSettings()).copy(
                    translationPresets = listOf(custom),
                    activeTranslationPresetId = "missing",
                ),
                expectedIds = listOf(builtIn.id, custom.id),
                expectedCurrentId = custom.id,
                expectsDraft = false,
            ),
            Case(
                name = "stored draft is replaced instead of duplicated",
                settings = AppSettings(
                    model = "new-draft-model",
                    translationPresets = listOf(storedDraft),
                    activeTranslationPresetId = storedDraft.id,
                ),
                expectedIds = listOf(TranslationPresetCatalog.UNSAVED_DRAFT_ID, builtIn.id),
                expectedCurrentId = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                expectsDraft = true,
            ),
        )

        cases.forEach { case ->
            val result = presetCarouselPlans(case.settings, unsavedName)

            assertEquals(case.name, case.expectedIds, result.presets.map { it.id })
            assertEquals("${case.name}: current", case.expectedCurrentId, result.currentPresetId)
            assertEquals(
                "${case.name}: exactly one draft when expected",
                if (case.expectsDraft) 1 else 0,
                result.presets.count { it.id == TranslationPresetCatalog.UNSAVED_DRAFT_ID },
            )
            if (case.expectsDraft) {
                assertEquals("${case.name}: localized name", unsavedName, result.presets.first().name)
                assertEquals(
                    "${case.name}: snapshots current settings",
                    case.settings.model,
                    result.presets.first().model,
                )
            }
        }
    }

    @Test
    fun readinessAndAppliedState_requireEveryPresetModel() {
        data class Case(
            val name: String,
            val presetId: String,
            val activeId: String,
            val issues: List<TranslationPresetModelIssue>?,
            val expectedCanApply: Boolean,
            val expectedApplied: Boolean,
        )

        val issueKinds = listOf(
            TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED,
            TranslationPresetModelIssueKind.LOCAL_LLM_MISSING,
            TranslationPresetModelIssueKind.PADDLE_MISSING,
            TranslationPresetModelIssueKind.MANGA_OCR_MISSING,
            TranslationPresetModelIssueKind.ORIENTATION_MISSING,
        )
        val cases = buildList {
            add(Case("readiness not checked", "a", "a", null, false, false))
            add(Case("all models ready and active", "a", "a", emptyList(), true, true))
            add(Case("all models ready but inactive", "a", "b", emptyList(), true, false))
            issueKinds.forEach { kind ->
                add(
                    Case(
                        name = "$kind blocks application",
                        presetId = "a",
                        activeId = "a",
                        issues = listOf(TranslationPresetModelIssue(kind)),
                        expectedCanApply = false,
                        expectedApplied = false,
                    )
                )
            }
            add(
                Case(
                    name = "multiple missing models block application",
                    presetId = "a",
                    activeId = "a",
                    issues = issueKinds.map(::TranslationPresetModelIssue),
                    expectedCanApply = false,
                    expectedApplied = false,
                )
            )
        }

        cases.forEach { case ->
            assertEquals(
                "${case.name}: can apply",
                case.expectedCanApply,
                presetCarouselCanApply(case.issues),
            )
            assertEquals(
                "${case.name}: applied",
                case.expectedApplied,
                presetCarouselIsApplied(case.presetId, case.activeId, case.issues),
            )
        }
    }

    @Test
    fun mainViewModel_rechecksModelsBeforePersistingAPreset() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val applyStart = source.indexOf("suspend fun applyTranslationPreset(id: String): Boolean")
        val applyEnd = source.indexOf("private fun modelIssuesFor(", applyStart)
        assertTrue("apply function exists", applyStart >= 0)
        assertTrue("model readiness helper follows apply", applyEnd > applyStart)
        val applyBlock = source.substring(applyStart, applyEnd)

        data class Case(val name: String, val marker: String)

        listOf(
            Case("loads the requested preset", "TranslationPresetCatalog.find("),
            Case("checks shared model issues", "translationPresetCanApply(modelIssuesFor(preset))"),
            Case("rejects missing models", "if (!canApply) return false"),
            Case("persists only after validation", "repo.update { current ->"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", applyBlock.contains(case.marker))
        }
        assertTrue(
            "model validation must precede persistence",
            applyBlock.indexOf("translationPresetCanApply") < applyBlock.indexOf("repo.update"),
        )
    }

    @Test
    fun mainScreen_showsStatusAndPresetAsTwoPagesInOneVerticalCarousel() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val carouselCall = source.indexOf("            StatusPresetCarousel(")
        val captureCard = source.indexOf(
            "            ActionCard(title = stringResource(R.string.main_section_capture))",
            carouselCall,
        )
        val carouselFunction = source.indexOf("private fun StatusPresetCarousel(")
        val carouselFunctionEnd = source.indexOf(
            "private const val STATUS_PRESET_PAGE_COUNT",
            carouselFunction,
        )

        data class Case(val name: String, val expected: Boolean)

        listOf(
            Case("combined carousel exists", carouselCall >= 0),
            Case("capture actions follow carousel", captureCard > carouselCall),
            Case("combined carousel function exists", carouselFunction >= 0),
            Case("combined carousel function has an end", carouselFunctionEnd > carouselFunction),
        ).forEach { case -> assertTrue(case.name, case.expected) }

        val mainLayout = source.substring(carouselCall, captureCard)
        assertFalse("status must not be a second visible card", mainLayout.contains("StatusCard("))
        assertFalse("preset must not be a second visible card", mainLayout.contains("PresetCarouselCard("))
        assertFalse(
            "preset carousel must not depend on capture service state",
            mainLayout.contains("if (serviceRunning)"),
        )

        val carousel = source.substring(carouselFunction, carouselFunctionEnd)
        listOf(
            Case("uses a vertical pager", carousel.contains("VerticalPager(")),
            Case("indicator overlays instead of taking width", carousel.contains("Box(")),
            Case(
                "pager keeps the cards inset from the edge-to-edge container",
                carousel.contains(".padding(horizontal = MainScreenHorizontalPadding)"),
            ),
            Case("layout has no width-taking weight", !carousel.contains(".weight(1f)")),
            Case("status is the first page", carousel.contains("STATUS_PAGE -> StatusCard(")),
            Case("preset is the second page", carousel.contains("PRESET_PAGE -> PresetCarouselCard(")),
            Case(
                "pager and both pages fill one viewport",
                carousel.split(".fillMaxSize()").size - 1 == 3,
            ),
            Case("has a two-page indicator", carousel.contains("repeat(STATUS_PRESET_PAGE_COUNT)")),
        ).forEach { case -> assertTrue(case.name, case.expected) }
    }

    @Test
    fun mainScreen_placesTheVerticalIndicatorOutsideTheCards() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val carouselCall = source.indexOf("            StatusPresetCarousel(")
        val captureCard = source.indexOf(
            "            ActionCard(title = stringResource(R.string.main_section_capture))",
            carouselCall,
        )
        val mainColumn = source.substring(
            source.lastIndexOf("        Column(", carouselCall),
            captureCard,
        )
        val carouselFunction = source.indexOf("private fun StatusPresetCarousel(")
        val carouselFunctionEnd = source.indexOf(
            "private const val STATUS_PRESET_PAGE_COUNT",
            carouselFunction,
        )
        val carousel = source.substring(carouselFunction, carouselFunctionEnd)
        val actionCardFunction = source.substring(
            source.indexOf("private fun ActionCard("),
            source.indexOf("private enum class StartMode"),
        )

        data class Case(val name: String, val expected: Boolean)

        listOf(
            Case(
                "main content has no horizontal outer padding",
                mainColumn.contains(".padding(vertical = 8.dp)") &&
                    !mainColumn.contains(".padding(horizontal = 16.dp, vertical = 8.dp)"),
            ),
            Case(
                "carousel cards keep the original 16dp horizontal position",
                carousel.contains(".padding(horizontal = MainScreenHorizontalPadding)"),
            ),
            Case(
                "indicator remains aligned to the edge-to-edge carousel container",
                carousel.contains(".align(Alignment.CenterEnd)") &&
                    carousel.contains(".padding(end = 8.dp)"),
            ),
            Case(
                "cards below the carousel own the same horizontal padding",
                actionCardFunction.contains(
                    ".padding(horizontal = MainScreenHorizontalPadding)"
                ),
            ),
            Case(
                "shared horizontal padding remains 16dp",
                source.contains("private val MainScreenHorizontalPadding = 16.dp"),
            ),
        ).forEach { case -> assertTrue(case.name, case.expected) }
    }

    @Test
    fun statusPresetDiscoveryHintEligibility_isTableDriven() {
        data class Case(
            val name: String,
            val presetPageSeen: Boolean,
            val hintAlreadyPlayed: Boolean,
            val settledPage: Int,
            val isScrollInProgress: Boolean,
            val pageCount: Int,
            val expected: Boolean,
        )

        listOf(
            Case("unseen idle status page", false, false, 0, false, 2, true),
            Case("preset was seen in an earlier session", true, false, 0, false, 2, false),
            Case("hint already played this session", false, true, 0, false, 2, false),
            Case("already settled on presets", false, false, 1, false, 2, false),
            Case("user is actively scrolling", false, false, 0, true, 2, false),
            Case("preset page is unavailable", false, false, 0, false, 1, false),
            Case("empty corrupted page count", false, false, 0, false, 0, false),
            Case("unexpected settled page", false, false, 3, false, 4, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldRunMainStatusPresetHint(
                    presetPageSeen = case.presetPageSeen,
                    hintAlreadyPlayed = case.hintAlreadyPlayed,
                    settledPage = case.settledPage,
                    isScrollInProgress = case.isScrollInProgress,
                    pageCount = case.pageCount,
                ),
            )
        }
    }

    @Test
    fun statusPresetDiscoverySeenDecision_isTableDriven() {
        data class Case(
            val name: String,
            val settledPage: Int,
            val pageCount: Int,
            val expectedSeen: Boolean,
        )

        listOf(
            Case("status page is not discovery", 0, 2, false),
            Case("preset page is discovery", 1, 2, true),
            Case("preset index without a preset page", 1, 1, false),
            Case("negative page is ignored", -1, 2, false),
            Case("later unexpected page is ignored", 2, 3, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedSeen,
                mainStatusPresetPageWasSeen(
                    settledPage = case.settledPage,
                    pageCount = case.pageCount,
                ),
            )
        }
    }

    @Test
    fun statusPresetDiscoveryHint_isPartiallyRevealedBouncyAndPersistent() {
        val main = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val repository =
            moduleFile("src/main/java/com/gameocr/app/data/SettingsRepository.kt").readText()
        val carouselStart = main.indexOf("private fun StatusPresetCarousel(")
        val carouselEnd = main.indexOf(
            "private const val STATUS_PRESET_PAGE_COUNT",
            carouselStart,
        )
        val carousel = main.substring(carouselStart, carouselEnd)

        data class Case(val name: String, val content: String, val marker: String)

        listOf(
            Case("waits before nudging", carousel, "STATUS_PRESET_DISCOVERY_HINT_DELAY_MS"),
            Case("observes the settled page", carousel, "snapshotFlow { pagerState.settledPage }"),
            Case("reveals only part of presets", carousel, "pageOffsetFraction ="),
            Case("returns with a spring", carousel, "animationSpec = spring("),
            Case("uses a bouncy damping ratio", carousel, "Spring.DampingRatioMediumBouncy"),
            Case("uses a slow spring", carousel, "Spring.StiffnessVeryLow"),
            Case("loads persisted discovery", main, "viewModel.hasSeenMainStatusPreset()"),
            Case("persists preset discovery", main, "viewModel.markMainStatusPresetSeen()"),
            Case(
                "repository owns a discovery preference",
                repository,
                "booleanPreferencesKey(\"main_status_preset_seen\")",
            ),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", case.content.contains(case.marker))
        }
        listOf(
            "hint waits one second before moving" to
                "STATUS_PRESET_DISCOVERY_HINT_DELAY_MS = 1_000L",
            "reveal motion is deliberately slow" to
                "STATUS_PRESET_DISCOVERY_HINT_REVEAL_MS = 650",
            "partial reveal remains visible before returning" to
                "STATUS_PRESET_DISCOVERY_HINT_HOLD_MS = 1_500L",
        ).forEach { (name, marker) ->
            assertTrue("$name: missing $marker", main.contains(marker))
        }
    }

    @Test
    fun horizontalPresetCards_reuseSettingsContentAndHaveNoIndicator() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val carouselStart = source.indexOf("private fun PresetCarousel(")
        val carouselEnd = source.indexOf("internal data class PresetCarouselPlans(", carouselStart)
        assertTrue("preset carousel exists", carouselStart >= 0)
        assertTrue("preset carousel has an end", carouselEnd > carouselStart)
        val carousel = source.substring(carouselStart, carouselEnd)

        data class Case(val name: String, val expected: Boolean)

        listOf(
            Case(
                "uses the same localized preset name as settings",
                carousel.contains("translationPresetDisplayName(preset)"),
            ),
            Case(
                "uses the same OCR, translator, language and TTS summary as settings",
                carousel.contains("translationPresetSummary(preset)"),
            ),
            Case("keeps the horizontal pager", carousel.contains("HorizontalPager(")),
            Case("removes horizontal dots", !carousel.contains("repeat(presets.size)")),
            Case("removes horizontal indicator state", !carousel.contains("indicatorIndex")),
        ).forEach { case -> assertTrue(case.name, case.expected) }
    }

    @Test
    fun pageCount_coversEmptySingleAndInfiniteCases() {
        data class Case(val name: String, val itemCount: Int, val expected: Int)

        listOf(
            Case("negative count", -1, 0),
            Case("empty", 0, 0),
            Case("single preset", 1, 1),
            Case("two presets", 2, Int.MAX_VALUE),
            Case("many presets", 12, Int.MAX_VALUE),
        ).forEach { case ->
            assertEquals(case.name, case.expected, presetCarouselPageCount(case.itemCount))
        }
    }

    @Test
    fun activeIndex_fallsBackSafelyWhenSelectionIsMissing() {
        data class Case(
            val name: String,
            val presetIds: List<String>,
            val activeId: String,
            val expected: Int,
        )

        listOf(
            Case("empty catalog", emptyList(), "missing", 0),
            Case("blank active id", listOf("a", "b"), "", 0),
            Case("missing active id", listOf("a", "b"), "c", 0),
            Case("first active", listOf("a", "b"), "a", 0),
            Case("later active", listOf("a", "b", "c"), "c", 2),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                presetCarouselActiveIndex(case.presetIds, case.activeId),
            )
        }
    }

    @Test
    fun itemIndexes_wrapBothDirections() {
        data class Case(
            val name: String,
            val page: Int,
            val itemCount: Int,
            val expected: Int?,
        )

        listOf(
            Case("empty catalog", 0, 0, null),
            Case("negative count", 0, -2, null),
            Case("first page", 0, 3, 0),
            Case("last direct page", 2, 3, 2),
            Case("forward wrap", 3, 3, 0),
            Case("many forward wraps", 10, 3, 1),
            Case("backward wrap", -1, 3, 2),
            Case("many backward wraps", -10, 3, 2),
        ).forEach { case ->
            val actual = presetCarouselItemIndex(case.page, case.itemCount)
            if (case.expected == null) {
                assertNull(case.name, actual)
            } else {
                assertEquals(case.name, case.expected, actual)
            }
        }
    }

    @Test
    fun initialPage_startsNearMiddleAndPreservesTheActivePreset() {
        data class Case(
            val name: String,
            val itemCount: Int,
            val activeIndex: Int,
            val expectedIndex: Int,
        )

        listOf(
            Case("empty", 0, 0, 0),
            Case("single", 1, 0, 0),
            Case("first of two", 2, 0, 0),
            Case("second of two", 2, 1, 1),
            Case("last of five", 5, 4, 4),
            Case("active index wraps forward", 3, 4, 1),
            Case("active index wraps backward", 3, -1, 2),
        ).forEach { case ->
            val page = presetCarouselInitialPage(case.itemCount, case.activeIndex)
            if (case.itemCount <= 1) {
                assertEquals(case.name, 0, page)
            } else {
                assertEquals(
                    case.name,
                    case.expectedIndex,
                    presetCarouselItemIndex(page, case.itemCount),
                )
                assertTrue(
                    "${case.name}: centered in the virtual range",
                    page in Int.MAX_VALUE / 3..(Int.MAX_VALUE / 3 * 2),
                )
            }
        }
    }

    @Test
    fun nearestPage_usesTheShortestInfiniteCarouselPath() {
        data class Case(
            val name: String,
            val currentPage: Int,
            val itemCount: Int,
            val targetIndex: Int,
            val expected: Int,
        )

        listOf(
            Case("single stays at zero", 99, 1, 0, 0),
            Case("already selected", 100, 5, 0, 100),
            Case("one step forward", 100, 5, 1, 101),
            Case("wrap one step backward", 100, 5, 4, 99),
            Case("shorter forward path", 100, 5, 2, 102),
            Case("shorter backward path", 100, 5, 3, 98),
            Case("tie prefers forward", 100, 4, 2, 102),
            Case("target index is normalized", 100, 5, 6, 101),
            Case("lower boundary is clamped", 0, 5, 4, 0),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                presetCarouselNearestPage(
                    currentPage = case.currentPage,
                    itemCount = case.itemCount,
                    targetIndex = case.targetIndex,
                ),
            )
        }
    }

    @Test
    fun outwardTransform_rotatesAwayFromCenterAndClampsDistantPages() {
        data class Case(
            val name: String,
            val offset: Float,
            val expectedRotation: Float,
            val expectedScale: Float,
        )

        listOf(
            Case("far left clamps", -2f, -18f, 0.92f),
            Case("left edge rotates outward", -1f, -18f, 0.92f),
            Case("left half", -0.5f, -9f, 0.96f),
            Case("center stays flat", 0f, 0f, 1f),
            Case("right half", 0.5f, 9f, 0.96f),
            Case("right edge rotates outward", 1f, 18f, 0.92f),
            Case("far right clamps", 2f, 18f, 0.92f),
        ).forEach { case ->
            assertEquals(
                "${case.name}: rotation",
                case.expectedRotation,
                presetCarouselOutwardRotation(case.offset),
                0.0001f,
            )
            assertEquals(
                "${case.name}: scale",
                case.expectedScale,
                presetCarouselScale(case.offset),
                0.0001f,
            )
        }
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
