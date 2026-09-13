package com.gameocr.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLoggingWiringTest {

    @Test
    fun releaseLogging_tableDriven_disablesLogcatIndependentlyOfInAppLogs() {
        data class Case(val name: String, val path: String, val marker: String)

        listOf(
            Case(
                "release plants no Timber tree",
                "src/main/java/com/gameocr/app/GameOcrApp.kt",
                "if (BuildConfig.DEBUG)",
            ),
            Case(
                "settings keep the in-app log channel separate",
                "src/main/java/com/gameocr/app/GameOcrApp.kt",
                "logRepository.configureVerbose(settings.developerOptionsEnabled)",
            ),
            Case(
                "R8 strips Timber calls",
                "proguard-rules.pro",
                "-assumenosideeffects class timber.log.Timber",
            ),
            Case(
                "R8 strips tagged Timber forest calls",
                "proguard-rules.pro",
                "-assumenosideeffects class timber.log.Timber\$Forest",
            ),
            Case(
                "R8 strips tagged Timber tree calls",
                "proguard-rules.pro",
                "-assumenosideeffects class timber.log.Timber\$Tree",
            ),
            Case(
                "R8 strips direct Android log calls",
                "proguard-rules.pro",
                "-assumenosideeffects class android.util.Log",
            ),
        ).forEach { case ->
            assertTrue(case.name, source(case.path).contains(case.marker))
        }
    }

    @Test
    fun releaseOptimization_onlyStripsLogcatSinks() {
        val rules = source("proguard-rules.pro")
        val androidLog = rules.substringAfter("-assumenosideeffects class android.util.Log {").substringBefore("}")
        listOf("v", "d", "i", "w", "e", "wtf", "println").forEach { level ->
            assertTrue("Release strips $level", androidLog.contains("public static int $level(...);"))
        }
        assertFalse("Do not strip user-facing runtime logs", rules.contains("LogRepository"))
        assertFalse("Runtime logs do not depend on the build variant", source("src/main/java/com/gameocr/app/data/LogRepository.kt").contains("BuildConfig"))
        val module = source("src/main/java/com/gameocr/app/di/AppModule.kt")
        assertTrue(
            "HTTP bodies remain Debug-only",
            module.substringAfter("if (BuildConfig.DEBUG) {").substringBefore("}")
                .contains("addInterceptor(DebugHttpWireLoggingInterceptor())"),
        )
    }

    private fun source(path: String): String = listOf(
        File(path),
        File("app", path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
