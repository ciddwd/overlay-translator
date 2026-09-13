package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeReleaseLoggingWiringTest {

    @Test
    fun nativeLogging_tableDriven_debugIsUnchangedAndReleaseIsSilent() {
        data class Case(val name: String, val marker: String)

        val gradle = source("llama-android/build.gradle.kts")
        listOf(
            Case("debug native diagnostics", "-DGAMEOCR_NATIVE_LOGCAT=ON"),
            Case("release native diagnostics", "-DGAMEOCR_NATIVE_LOGCAT=OFF"),
        ).forEach { case ->
            assertTrue(case.name, gradle.contains(case.marker))
        }

        val cmake = source("llama-android/src/main/cpp/CMakeLists.txt")
        assertTrue(
            "CMake forwards the variant switch to native sources",
            cmake.contains("GAMEOCR_NATIVE_LOGCAT=$<BOOL:\${GAMEOCR_NATIVE_LOGCAT}>"),
        )
        assertTrue("Debug keeps its original level", cmake.contains("LOG_MIN_LEVEL=2"))
        assertTrue("The generated binding uses the wrapper", cmake.contains("gameocr_logging.h"))
        assertTrue(
            "The thread adapter uses the same wrapper",
            source("llama-android/src/main/cpp/llama_thread_policy.cpp").contains("#include \"gameocr_logging.h\""),
        )
    }

    @Test
    fun releaseNativeLogger_doesNotEvaluateArgumentsOrRestoreDefaultLogger() {
        val header = source("llama-android/src/main/cpp/gameocr_logging.h")
        assertTrue(header.contains("#define GAMEOCR_NATIVE_LOGCAT 0"))
        assertTrue(header.contains("#if GAMEOCR_NATIVE_LOGCAT\n#include \"logging.h\""))
        listOf("v", "d", "i", "w", "e").forEach { level ->
            assertTrue("Release $level is compiled out", header.contains("#define LOG$level(...) ((void)0)"))
        }
        val releaseBranch = header.substringAfter("#else")
        assertFalse(releaseBranch.contains("__android_log_"))
        assertTrue(releaseBranch.contains("aichat_android_log_callback("))
        assertTrue(
            "Upstream still installs the callback before loading backends",
            source("third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp")
                .contains("llama_log_set(aichat_android_log_callback, nullptr)"),
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
