package com.gameocr.app.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseLoggingWiringTest {

    @Test
    fun releaseLogging_tableDriven_hasNoNormalHotPathOutput() {
        data class Case(val name: String, val path: String, val marker: String)

        listOf(
            Case(
                "release plants no Timber tree",
                "src/main/java/com/gameocr/app/GameOcrApp.kt",
                "if (BuildConfig.DEBUG)",
            ),
            Case(
                "settings configure opt-in verbose memory logs",
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

    private fun source(path: String): String = listOf(
        File(path),
        File("app", path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
