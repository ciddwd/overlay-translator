package com.gameocr.app.tts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsManifestAuditTest {

    @Test
    fun manifestDeclaresNarrowTtsServiceVisibility() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()
        data class Case(val name: String, val marker: String)

        listOf(
            Case("queries block", "<queries>"),
            Case("TTS service action", "android.intent.action.TTS_SERVICE"),
        ).forEach { case ->
            assertTrue(case.name, manifest.contains(case.marker))
        }
        assertFalse(
            "TTS discovery must not request broad package visibility",
            manifest.contains("android.permission.QUERY_ALL_PACKAGES"),
        )
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
