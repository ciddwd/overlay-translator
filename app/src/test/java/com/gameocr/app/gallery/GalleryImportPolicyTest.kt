package com.gameocr.app.gallery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryImportPolicyTest {
    @Test
    fun partsRespectTheTaskLimitPerSource() {
        val uris = (1..148).map { "u$it" }
        data class Case(val kind: GalleryImportKind, val mode: GallerySplitMode, val input: List<String>, val sizes: List<Int>)
        listOf(
            Case(GalleryImportKind.FOLDER, GallerySplitMode.SPLIT, uris, listOf(100, 48)),
            Case(GalleryImportKind.ALBUM, GallerySplitMode.SPLIT, (1..300).map { "a$it" }, listOf(100, 100, 100)),
            Case(GalleryImportKind.FOLDER, GallerySplitMode.FIRST_ONLY, uris, listOf(100)),
            Case(GalleryImportKind.ALBUM, GallerySplitMode.SPLIT, uris.take(37), listOf(37)),
            Case(GalleryImportKind.PICKED, GallerySplitMode.SPLIT, uris, listOf(100)),
            Case(GalleryImportKind.FOLDER, GallerySplitMode.SPLIT, listOf("x", " x ", "", "y"), listOf(2)),
            Case(GalleryImportKind.FOLDER, GallerySplitMode.SPLIT, emptyList(), emptyList()),
        ).forEach { case ->
            assertEquals("${case.kind} ${case.mode} ${case.input.size}", case.sizes,
                GalleryImportPolicy.parts(case.input, case.kind, case.mode).map { it.size })
        }
        assertEquals("parts keep order", uris, GalleryImportPolicy.parts(uris, GalleryImportKind.FOLDER, GallerySplitMode.SPLIT).flatten())
    }

    @Test
    fun titlesNormaliseAndNumberParts() {
        assertEquals("Chapter 12", GalleryImportPolicy.normalizeTitle("  Chapter\n  12 "))
        assertEquals(GalleryImportPolicy.MAX_TITLE_LENGTH, GalleryImportPolicy.normalizeTitle("x".repeat(90)).length)
        assertEquals(listOf("Chapter 12"), GalleryImportPolicy.partTitles("Chapter 12", 1))
        assertEquals(listOf("Chapter 12 (1/2)", "Chapter 12 (2/2)"), GalleryImportPolicy.partTitles("Chapter 12", 2))
        assertEquals("blank stays blank so the date shows", listOf("", ""), GalleryImportPolicy.partTitles("  ", 2))
        GalleryImportPolicy.partTitles("y".repeat(60), 3).forEachIndexed { i, title ->
            assertTrue(title.length <= GalleryImportPolicy.MAX_TITLE_LENGTH)
            assertTrue(title.endsWith(" (${i + 1}/3)"))
        }
        assertEquals("", GalleryImportPolicy.defaultTitle(GalleryImportSource(GalleryImportKind.PICKED, "x")))
        assertEquals("Manga", GalleryImportPolicy.defaultTitle(GalleryImportSource(GalleryImportKind.ALBUM, " Manga ")))
        assertEquals("Fallback date", galleryTaskDisplayTitle("", "Fallback date"))
        assertEquals("Chapter", galleryTaskDisplayTitle("Chapter", "Fallback date"))
    }

    @Test
    fun sourceSurvivesSaveAndRestore() {
        val source = GalleryImportSource(
            GalleryImportKind.FOLDER, "Chapter 12",
            listOf(GalleryImportEntry("content://a", "p1.png", 5)), GallerySplitMode.FIRST_ONLY,
        )
        assertEquals(source, GalleryImportPolicy.decode(GalleryImportPolicy.encode(source)))
        assertEquals(GalleryImportSource(), GalleryImportPolicy.decode(""))
        assertEquals(GalleryImportSource(), GalleryImportPolicy.decode("{not json"))
    }

    @Test
    fun databaseAddsTitleWithAMigrationThatKeepsOldTasks() {
        val db = File("src/main/java/com/gameocr/app/gallery/GalleryTranslationDatabase.kt").readText()
        val module = File("src/main/java/com/gameocr/app/di/AppModule.kt").readText()
        assertTrue(db.contains("version = 2,"))
        assertTrue(db.contains("object : Migration(1, 2)"))
        assertTrue(db.contains("ADD COLUMN title TEXT NOT NULL DEFAULT ''"))
        assertTrue(db.contains("ADD COLUMN sourceKind TEXT NOT NULL DEFAULT 'PICKED'"))
        assertTrue(db.contains("UPDATE gallery_translation_tasks SET title = :title WHERE id = :taskId"))
        assertTrue("Room validates migrated defaults against the entity",
            db.contains("@ColumnInfo(defaultValue = \"\")\n    val title: String = \"\","))
        assertTrue(db.contains("@ColumnInfo(defaultValue = \"PICKED\")\n    val sourceKind: String"))
        assertTrue("never wipes user tasks", module.contains(".addMigrations(GALLERY_TRANSLATION_MIGRATION_1_2)"))
        assertFalse(module.contains("fallbackToDestructiveMigration"))
    }

    @Test
    fun foldersListOnlyDirectImagesAndAlbumsNeedThePhotosPermission() {
        val sources = File("src/main/java/com/gameocr/app/gallery/GalleryMediaSources.kt").readText()
        val folder = sources.substringAfter("suspend fun folder(")
        assertTrue(folder.contains("buildChildDocumentsUriUsingTree"))
        assertTrue("images only", folder.contains("mime.startsWith(\"image/\")"))
        assertFalse("no subfolder recursion", folder.contains("MIME_TYPE_DIR"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(manifest.contains("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        val storage = manifest.substringAfter("android.permission.READ_EXTERNAL_STORAGE\"").substringBefore("/>")
        assertTrue("legacy storage read only up to Android 12L", storage.contains("android:maxSdkVersion=\"32\""))
    }
}
