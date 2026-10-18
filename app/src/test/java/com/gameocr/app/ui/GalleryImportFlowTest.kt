package com.gameocr.app.ui

import android.Manifest
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryImportFlowTest {
    private fun source(path: String) = File("src/main/java/com/gameocr/app/$path").readText()

    @Test
    fun photoPermissionsMatchTheAndroidVersion() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), galleryPhotoPermissions(32))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), galleryPhotoPermissions(33))
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            galleryPhotoPermissions(34),
        )
        data class Case(val sdk: Int, val granted: Set<String>, val expected: GalleryPhotoAccess)
        listOf(
            Case(32, setOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryPhotoAccess.FULL),
            Case(32, emptySet(), GalleryPhotoAccess.NONE),
            Case(33, setOf(Manifest.permission.READ_MEDIA_IMAGES), GalleryPhotoAccess.FULL),
            Case(33, setOf(Manifest.permission.READ_EXTERNAL_STORAGE), GalleryPhotoAccess.NONE),
            Case(34, setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), GalleryPhotoAccess.PARTIAL),
            Case(34, setOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                GalleryPhotoAccess.FULL),
            Case(35, emptySet(), GalleryPhotoAccess.NONE),
        ).forEach { case ->
            assertEquals("${case.sdk} ${case.granted}", case.expected, galleryPhotoAccess(case.sdk) { it in case.granted })
        }
    }

    @Test
    fun everyNewTaskEntryUsesTheSameSourceSheet() {
        val main = source("ui/MainScreen.kt")
        val screens = source("ui/GalleryTranslationScreens.kt")
        val activity = source("ui/MainActivity.kt")
        val import = source("ui/GalleryImportUi.kt")
        listOf(main, screens).forEach { file ->
            assertTrue(file.contains("GalleryImportHost("))
            assertFalse("no direct photo picker for new tasks", file.contains("galleryPicker.launch("))
        }
        assertTrue(main.contains("onClick = { galleryImport.open() }"))
        assertTrue(screens.contains("onClick = { importState.open() }"))
        assertTrue(import.contains("ActivityResultContracts.PickMultipleVisualMedia(GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK)"))
        assertTrue(import.contains("ActivityResultContracts.OpenDocumentTree()"))
        assertTrue("album permission only after the explainer",
            import.indexOf("state.accessExplainer = true") < import.indexOf("permissionRequest.launch("))
        assertTrue(activity.contains("Route.GalleryAlbums -> GalleryAlbumPickerScreen("))
        assertTrue(activity.contains("gallerySourceJson = GalleryImportPolicy.encode(source)"))
        assertTrue("shared images stay a picked source", activity.contains("gallerySourceJson = \"\"\n            selectedGalleryUris = sharedUris"))
    }

    @Test
    fun createScreenSplitsTitlesAndNeverTruncatesCollectionsOnRemove() {
        val screens = source("ui/GalleryTranslationScreens.kt")
        val create = screens.substringAfter("fun GalleryTranslationConfirmScreen(").substringBefore("private fun GalleryPresetSwitcher(")
        assertTrue(create.contains("GalleryImportPolicy.parts(selectedUris, source.kind, source.splitMode)"))
        assertTrue(create.contains("titles = GalleryImportPolicy.partTitles(title, parts.size)"))
        assertTrue(create.contains("GalleryTaskTitleField("))
        assertTrue(create.contains("GallerySourceCard("))
        assertTrue(create.contains("selectedUris.filterNot { it == uri }"))
        assertTrue("add more only for picked images", create.contains("!isCollection &&"))
        assertTrue(create.contains("R.string.gallery_confirm_create_parts"))
        val vm = screens.substringAfter("class GalleryTranslationViewModel")
        assertTrue(vm.contains("repository.createTask(uris, titles.getOrElse(index) { \"\" }, sourceKind)"))
        assertTrue(vm.contains("suspend fun rename(taskId: String, title: String)"))
    }

    @Test
    fun listAndResultsShowTheTitleAndResultsCanRename() {
        val screens = source("ui/GalleryTranslationScreens.kt")
        val card = screens.substringAfter("private fun GalleryTaskCard(").substringBefore("private fun GalleryTaskSettingsSummary(")
        assertTrue(card.contains("galleryTaskDisplayTitle(task.title, createdAtText)"))
        assertTrue("card shows where the images came from", card.contains("GalleryImportKind.FOLDER.name -> Icons.Outlined.Folder"))
        assertTrue("card status is a coloured chip", card.contains("GalleryStatusChip(task.status)"))
        val summary = screens.substringAfter("private fun GalleryTaskSummary(").substringBefore("private fun GalleryTaskProgressSummary(")
        assertTrue(summary.contains("onClick = onRename"))
        assertTrue(screens.contains("GalleryRenameDialog("))
        assertTrue(screens.contains("viewModel.rename(renameTarget.id, newTitle)"))
    }
}
