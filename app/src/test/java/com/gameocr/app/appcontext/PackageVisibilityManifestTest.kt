package com.gameocr.app.appcontext

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageVisibilityManifestTest {
    @Test
    fun launcherQuery_isDeclaredWithoutBroadPackagePermission() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        data class Case(val name: String, val marker: String)

        listOf(
            Case("launcher main action", "android.intent.action.MAIN"),
            Case("launcher category", "android.intent.category.LAUNCHER"),
        ).forEach { case -> assertTrue(case.name, queries.contains(case.marker)) }

        assertFalse(
            "manual app selection must not request visibility of every installed package",
            manifest.contains("android.permission.QUERY_ALL_PACKAGES"),
        )
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
