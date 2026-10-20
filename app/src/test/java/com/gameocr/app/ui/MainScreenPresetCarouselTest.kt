package com.gameocr.app.ui

import com.gameocr.app.readSourceText
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
    fun carouselPlans_doesNotMatchADifferentKeyByConfigurationAlone() {
        val source = AppSettings(model = "same-model")
        val a = TranslationPresetCatalog.fromSettings("a", "a", "a", source)
        val b = a.copy(id = "b", name = "b")
        val settings = source.copy(translationPresets = listOf(a, b), activeTranslationPresetId = a.id)
        listOf(
            setOf("a") to "a", setOf("b") to "b",
            emptySet<String>() to TranslationPresetCatalog.UNSAVED_DRAFT_ID,
        ).forEach { (matching, expected) ->
            assertEquals(expected, presetCarouselPlans(settings, "unsaved", matching).currentPresetId)
        }
    }

    @Test
    fun presetHeading_isConciseInEveryLocale() {
        data class Case(
            val name: String,
            val path: String,
            val expected: String,
        )

        listOf(
            Case("English", "src/main/res/values/strings.xml", "Presets"),
            Case("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml", "预设"),
        ).forEach { case ->
            val resources = moduleFile(case.path).readSourceText()
            assertTrue(
                case.name,
                resources.contains(
                    """<string name="main_preset_title">${case.expected}</string>"""
                ),
            )
        }
    }

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
    fun readinessGatesApplicationButActiveIdentityRemainsVisible() {
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
            add(Case("readiness not checked", "a", "a", null, false, true))
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
                        expectedApplied = true,
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
                    expectedApplied = true,
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
                presetCarouselIsApplied(case.presetId, case.activeId),
            )
        }
    }

    @Test
    fun unsavedPresetSwitchConfirmation_isTableDriven() {
        data class Case(
            val name: String,
            val currentId: String,
            val targetId: String,
            val expected: Boolean,
        )

        listOf(
            Case(
                name = "unsaved draft switching to a saved preset asks first",
                currentId = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                targetId = "saved",
                expected = true,
            ),
            Case(
                name = "unsaved draft pointing to itself does nothing",
                currentId = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                targetId = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
                expected = false,
            ),
            Case(
                name = "saved preset switches directly",
                currentId = "current",
                targetId = "target",
                expected = false,
            ),
            Case(
                name = "blank current id is not treated as a draft",
                currentId = "",
                targetId = "target",
                expected = false,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldConfirmUnsavedPresetSwitch(case.currentId, case.targetId),
            )
        }
    }

    @Test
    fun mainViewModel_rechecksModelsBeforePersistingAPreset() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readSourceText()
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
            Case("persists only after validation", "repo.applyTranslationPreset(id)"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", applyBlock.contains(case.marker))
        }
        assertTrue(
            "model validation must precede persistence",
            applyBlock.indexOf("translationPresetCanApply") < applyBlock.indexOf("repo.applyTranslationPreset"),
        )
    }

    @Test
    fun saveThenApply_validatesBeforeTheAtomicSettingsUpdate() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readSourceText()
        val start = source.indexOf("suspend fun saveTranslationPresetAndApply(")
        val end = source.indexOf("private fun modelIssuesFor(", start)
        assertTrue("save-then-apply function exists", start >= 0)
        assertTrue("save-then-apply function has an end", end > start)
        val block = source.substring(start, end)

        data class Case(val name: String, val marker: String)

        listOf(
            Case("loads the target preset", "TranslationPresetCatalog.find("),
            Case("validates target model readiness", "translationPresetCanApply(modelIssuesFor(target))"),
            Case("rejects before persistence", "if (!canApply) return false"),
            Case("saves and switches through the atomic repository operation", "repo.applyTranslationPreset(targetId, presetToSave)"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", block.contains(case.marker))
        }
        assertTrue(
            "validation precedes the settings update",
            block.indexOf("translationPresetCanApply") < block.indexOf("repo.applyTranslationPreset"),
        )
    }

    @Test
    fun mainScreen_showsPresetsAsAStandaloneCardBelowTheCaptureCard() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readSourceText()
        val captureCard = source.indexOf("            CaptureGalleryCarousel(")
        val presetCard = source.indexOf("            PresetCarouselCard(", captureCard)
        val compatibility = source.indexOf(
            "ActionCard(title = stringResource(R.string.main_section_rom_guide), listStyle = true)",
            presetCard,
        )

        data class Case(val name: String, val expected: Boolean)

        listOf(
            Case("capture card exists", captureCard >= 0),
            Case("presets follow the capture card", presetCard > captureCard),
            Case("system compatibility follows the presets", compatibility > presetCard),
            Case("no vertical status pager remains", !source.contains("VerticalPager(")),
            Case("no status card remains", !source.contains("StatusCard(")),
        ).forEach { case -> assertTrue(case.name, case.expected) }

        val presetCall = source.substring(presetCard, source.indexOf("\n            )\n", presetCard))
        assertFalse(
            "preset carousel must not depend on capture service state",
            presetCall.contains("serviceRunning"),
        )
        assertTrue(
            "preset hint waits until the capture card swipe was discovered",
            presetCall.contains("captureGallerySeen == true"),
        )
    }

    @Test
    fun horizontalPresetCards_reuseSettingsContentAndHaveNoIndicator() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readSourceText()
        val carouselStart = source.indexOf("internal fun PresetCarousel(")
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
                carousel.contains(
                    "mainPresetDetailLines(translationPresetSummary(preset))"
                ),
            ),
            Case(
                "renders each of the four details on its own line",
                source.contains("MAIN_PRESET_DETAIL_COUNT = 4") &&
                    carousel.contains("maxLines = 1"),
            ),
            Case("keeps the horizontal pager", carousel.contains("HorizontalPager(")),
            Case(
                "settled swipes wait 600ms before applying",
                carousel.contains("snapshotFlow { pagerState.isScrollInProgress") &&
                    carousel.contains(".collectLatest") &&
                    carousel.contains("autoApplyProgress.animateTo(") &&
                    carousel.contains(
                        "durationMillis = PRESET_AUTO_APPLY_SETTLE_DELAY_MS.toInt()"
                    ) &&
                    source.contains("PRESET_AUTO_APPLY_SETTLE_DELAY_MS = 600L"),
            ),
            Case(
                "new scrolling cancels the pending visual cue and application",
                carousel.contains("pendingAutoApplyPage = null") &&
                    carousel.contains(".collectLatest"),
            ),
            Case(
                "the waiting card shows one primary flow-border lap",
                carousel.contains("Animatable(0f)") &&
                    carousel.contains("autoApplyProgress.animateTo(") &&
                    carousel.contains("presetAutoApplyFlowBorder(") &&
                    carousel.contains("color = MaterialTheme.colorScheme.primary"),
            ),
            Case(
                "side cards only animate into the center",
                carousel.contains(".clickable(enabled = !centered)") &&
                    carousel.contains("pagerState.animateScrollToPage(page)"),
            ),
            Case(
                "the final settled preset is applied automatically",
                carousel.contains("onPresetSelected(preset)"),
            ),
            Case(
                "unsaved current settings are intercepted by the confirmation path",
                source.contains("shouldConfirmUnsavedPresetSwitch(") &&
                    source.contains("pendingPresetSwitch = preset"),
            ),
            Case("removes horizontal dots", !carousel.contains("repeat(presets.size)")),
            Case("removes horizontal indicator state", !carousel.contains("indicatorIndex")),
        ).forEach { case -> assertTrue(case.name, case.expected) }
    }

    @Test
    fun mainPresetDetailLines_preserveFourRowsIncludingTts() {
        data class Case(
            val name: String,
            val summary: String,
            val expected: List<String>,
        )

        listOf(
            Case(
                "four standard details",
                "OCR\nTranslator\nLanguages\nTTS",
                listOf("OCR", "Translator", "Languages", "TTS"),
            ),
            Case(
                "Windows line endings",
                "OCR\r\nTranslator\r\nLanguages\r\nTTS",
                listOf("OCR", "Translator", "Languages", "TTS"),
            ),
            Case(
                "fifth line cannot displace TTS",
                "OCR\nTranslator\nLanguages\nTTS\nExtra",
                listOf("OCR", "Translator", "Languages", "TTS"),
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, mainPresetDetailLines(case.summary))
        }
    }

    @Test
    fun presetFlowSegments_accumulateUntilTheWholeBorderIsFilled() {
        data class Case(
            val name: String,
            val progress: Float,
            val pathLength: Float,
            val expected: List<PresetFlowSegment>,
        )

        listOf(
            Case("empty path", 0.5f, 0f, emptyList()),
            Case("lap start has not drawn yet", 0f, 100f, emptyList()),
            Case(
                "early progress keeps everything already drawn",
                0.1f,
                100f,
                listOf(PresetFlowSegment(0f, 10f)),
            ),
            Case(
                "half of the border remains filled",
                0.5f,
                100f,
                listOf(PresetFlowSegment(0f, 50f)),
            ),
            Case(
                "lap end fills the complete border",
                1f,
                100f,
                listOf(PresetFlowSegment(0f, 100f)),
            ),
            Case(
                "progress past the end is clamped",
                2f,
                100f,
                listOf(PresetFlowSegment(0f, 100f)),
            ),
            Case("negative progress is clamped to empty", -1f, 100f, emptyList()),
        ).forEach { case ->
            val actual = presetFlowSegments(case.progress, case.pathLength)
            assertEquals("${case.name}: segment count", case.expected.size, actual.size)
            case.expected.zip(actual).forEachIndexed { index, (expected, result) ->
                assertEquals(
                    "${case.name}: segment $index start",
                    expected.startDistance,
                    result.startDistance,
                    0.001f,
                )
                assertEquals(
                    "${case.name}: segment $index stop",
                    expected.stopDistance,
                    result.stopDistance,
                    0.001f,
                )
            }
        }
    }

    @Test
    fun mainRouteNoLongerHoistsAStatusPresetPage() {
        val activity = moduleFile("src/main/java/com/gameocr/app/ui/MainActivity.kt").readSourceText()
        assertFalse(activity.contains("mainStatusPresetPageIndex"))
        assertFalse(activity.contains("onStatusPresetPageChanged"))
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
