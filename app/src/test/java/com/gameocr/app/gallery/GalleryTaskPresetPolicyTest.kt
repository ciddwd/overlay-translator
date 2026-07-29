package com.gameocr.app.gallery

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationPresetCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTaskPresetPolicyTest {

    @Test
    fun `task preset is recorded only when current settings match`() {
        val builtIn = TranslationPresetCatalog.builtIns().single()
        val customSettings = Settings(model = "custom-model")
        val custom = TranslationPresetCatalog.fromSettings(
            id = "custom-1",
            name = "Custom",
            shortName = "Custom",
            settings = customSettings,
        )
        val storedDraft = TranslationPresetCatalog.fromSettings(
            id = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
            name = "Old draft",
            shortName = "Draft",
            settings = Settings(model = "draft-model"),
        )

        data class Case(
            val name: String,
            val settings: Settings,
            val expectedPresetId: String?,
        )

        listOf(
            Case(
                name = "matching active built in is recorded",
                settings = builtIn.applyTo(Settings()).copy(
                    activeTranslationPresetId = builtIn.id,
                ),
                expectedPresetId = builtIn.id,
            ),
            Case(
                name = "modified active preset becomes unsaved",
                settings = builtIn.applyTo(Settings()).copy(
                    activeTranslationPresetId = builtIn.id,
                    model = "modified-after-applying",
                ),
                expectedPresetId = null,
            ),
            Case(
                name = "matching custom is found even if active id is stale",
                settings = custom.applyTo(Settings()).copy(
                    translationPresets = listOf(custom),
                    activeTranslationPresetId = "missing",
                ),
                expectedPresetId = custom.id,
            ),
            Case(
                name = "stored draft is never treated as saved",
                settings = storedDraft.applyTo(Settings()).copy(
                    translationPresets = listOf(storedDraft),
                    activeTranslationPresetId = storedDraft.id,
                ),
                expectedPresetId = null,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedPresetId,
                galleryTaskMatchingPreset(case.settings)?.id,
            )
        }
    }
}
