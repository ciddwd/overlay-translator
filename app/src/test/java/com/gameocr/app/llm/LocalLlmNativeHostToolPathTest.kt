package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmNativeHostToolPathTest {

    @Test
    fun ndkGlslcPath_usesCanonicalHostDirectoryForEverySupportedRunner() {
        val source = listOf(
            File("../llama-android/src/main/cpp/CMakeLists.txt"),
            File("llama-android/src/main/cpp/CMakeLists.txt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("llama-android CMakeLists.txt not found")

        data class Case(
            val cmakeHost: String,
            val ndkHostDirectory: String,
            val executable: String,
        )

        listOf(
            Case("Windows", "windows-x86_64", "glslc.exe"),
            Case("Darwin", "darwin-x86_64", "glslc"),
            Case("Linux", "linux-x86_64", "glslc"),
        ).forEach { case ->
            assertTrue(
                "${case.cmakeHost} must use the canonical Android NDK host directory",
                source.contains("CMAKE_HOST_SYSTEM_NAME STREQUAL \"${case.cmakeHost}\"") &&
                    source.contains("shader-tools/${case.ndkHostDirectory}/${case.executable}"),
            )
        }

        assertFalse(
            "CMake host names are title-cased and must not be interpolated into lowercase NDK paths",
            source.contains("shader-tools/\${CMAKE_HOST_SYSTEM_NAME}-\${CMAKE_HOST_SYSTEM_PROCESSOR}/glslc"),
        )
        assertTrue(
            "unknown hosts must fail with a diagnostic instead of a misleading missing-file error",
            source.contains("Unsupported Android NDK shader-tools host"),
        )
    }
}
