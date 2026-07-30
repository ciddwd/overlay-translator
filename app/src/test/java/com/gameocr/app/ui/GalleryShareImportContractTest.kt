package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryShareImportContractTest {

    @Test
    fun `single and multiple image shares open the existing create task screen`() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()
        val activity = sourceFile(
            "src/main/java/com/gameocr/app/ui/MainActivity.kt"
        ).readText()

        data class Case(
            val name: String,
            val source: String,
            val marker: String,
        )

        listOf(
            Case(
                "manifest accepts one shared image",
                manifest,
                "android.intent.action.SEND",
            ),
            Case(
                "manifest accepts multiple shared images",
                manifest,
                "android.intent.action.SEND_MULTIPLE",
            ),
            Case(
                "manifest limits both share entries to images",
                manifest,
                "android:mimeType=\"image/*\"",
            ),
            Case(
                "activity reads shared streams with compatibility API",
                activity,
                "IntentCompat.getParcelableExtra(",
            ),
            Case(
                "activity reads multiple shared streams with compatibility API",
                activity,
                "IntentCompat.getParcelableArrayListExtra(",
            ),
            Case(
                "shared images reuse gallery selection policy",
                activity,
                "GalleryTranslationWorkPolicy.sharedImageSelection(",
            ),
            Case(
                "share request opens create task route",
                activity,
                "routeName = Route.GalleryConfirm.name",
            ),
            Case(
                "share request populates the create task selection",
                activity,
                "selectedGalleryUris = sharedUris",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }
    }

    @Test
    fun `create task reuses the home preset switcher and applies before settings update`() {
        val gallery = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val switcherStart = gallery.indexOf("private fun GalleryPresetSwitcher(")
        val switcherEnd = gallery.indexOf(
            "private fun GallerySelectedThumbnail(",
            switcherStart,
        )
        assertTrue("gallery preset switcher exists", switcherStart >= 0 && switcherEnd > switcherStart)
        val switcher = gallery.substring(switcherStart, switcherEnd)

        data class Case(
            val name: String,
            val marker: String,
        )

        listOf(
            Case("reuses the home preset carousel", "PresetCarousel("),
            Case("uses the shared preset plan", "presetCarouselPlans("),
            Case("checks model readiness", "viewModel.presetModelIssues("),
            Case("applies only after carousel selection", "viewModel.applyTranslationPreset("),
            Case("protects unsaved settings", "shouldConfirmUnsavedPresetSwitch("),
            Case("supports save then apply", "viewModel.saveTranslationPresetAndApply("),
        ).forEach { case ->
            assertTrue(case.name, switcher.contains(case.marker))
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
