package com.gameocr.app.shizuku

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuManifestCompatibilityTest {

    @Test
    fun manifestQueries_everySupportedManagerPackage_tableDriven() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val cases = ShizukuBackendPackage.entries.map { backend ->
            backend.name to "<package android:name=\"${backend.packageName}\" />"
        }

        cases.forEach { (name, declaration) ->
            assertTrue(name, declaration in manifest)
        }
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
