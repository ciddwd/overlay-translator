package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReleaseLoggingWiringTest {

    @Test
    fun nativeLogging_tableDriven_debugIsVerboseAndReleaseIsErrorOnly() {
        data class Case(val name: String, val marker: String)

        val gradle = source("llama-android/build.gradle.kts")
        listOf(
            Case("debug native diagnostics", "-DGAMEOCR_NATIVE_LOG_MIN_LEVEL=2"),
            Case("release native diagnostics", "-DGAMEOCR_NATIVE_LOG_MIN_LEVEL=6"),
        ).forEach { case ->
            assertTrue(case.name, gradle.contains(case.marker))
        }

        val cmake = source("llama-android/src/main/cpp/CMakeLists.txt")
        assertTrue(
            "CMake forwards the variant log level to native sources",
            cmake.contains("LOG_MIN_LEVEL=\${GAMEOCR_NATIVE_LOG_MIN_LEVEL}"),
        )
    }

    @Test
    fun customNativeSources_useLevelAwareLoggingInsteadOfDirectLogcatWrites() {
        data class Case(val name: String, val path: String, val expectedMacro: String)

        listOf(
            Case(
                "multi sequence diagnostics",
                "llama-android/src/main/cpp/llama_multi_sequence.inc",
                "LOGi(",
            ),
            Case(
                "native policy diagnostics",
                "llama-android/src/main/cpp/llama_thread_policy.cpp",
                "LOGi(",
            ),
        ).forEach { case ->
            val text = source(case.path)
            assertTrue(case.name, text.contains(case.expectedMacro))
            assertFalse("${case.name} must not bypass the release level", text.contains("__android_log_print"))
        }
    }

    private fun source(path: String): String = sequenceOf(
        File(path),
        File("..", path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
