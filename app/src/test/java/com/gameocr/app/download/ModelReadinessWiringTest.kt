package com.gameocr.app.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReadinessWiringTest {
    @Test
    fun onboardingSettingsRuntimeAndWorker_shareReadinessBoundaries() {
        val onboarding = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingViewModel.kt"
        ).readText()
        val settings = sourceFile(
            "src/main/java/com/gameocr/app/ui/SettingsViewModel.kt"
        ).readText()
        val worker = sourceFile(
            "src/main/java/com/gameocr/app/download/ModelDownloadWorker.kt"
        ).readText()
        val runtime = sourceFile(
            "src/main/java/com/gameocr/app/llm/LlamaEngineHolder.kt"
        ).readText()

        listOf(onboarding, settings, worker).forEachIndexed { index, source ->
            assertTrue("consumer[$index] must use ModelReadinessChecker", source.contains("ModelReadinessChecker"))
        }
        assertFalse("onboarding must not call installers directly", onboarding.contains("checkInstalled("))
        assertFalse("settings readiness must not call installers directly", settings.contains("checkInstalled("))
        assertFalse("settings readiness must not call orientation installer directly", settings.contains("checkFullyInstalled("))
        assertTrue("worker must reject unsupported downloads", worker.contains("check(initialReadiness.supported)"))
        assertTrue("worker must verify the installed artifact", worker.contains("modelReadinessChecker.check(spec).installed"))
        assertTrue("runtime must share the device capability", runtime.contains("deviceCapability.isSupported()"))
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
