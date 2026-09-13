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
        assertTrue(
            "worker must probe the selected source before invoking an installer",
            worker.indexOf("networkTester.requireReachable(spec)") in 1 until worker.indexOf("when (spec.type)"),
        )
        assertFalse(
            "successful source probes must not add a redundant notification step",
            worker.contains("model_download_source_ready_format"),
        )
        assertTrue("worker must verify the installed artifact", worker.contains("modelReadinessChecker.checkArtifact(spec).installed"))
        assertTrue("worker must verify the complete dependency set", worker.contains("specs.all { modelReadinessChecker.check(it).ready }"))
        assertTrue("runtime must share the device capability", runtime.contains("deviceCapability.isSupported()"))
    }

    @Test
    fun mangaDependencies_areOwnedByPersistentWorkNotSettingsCallbacks() {
        val manager = sourceFile("src/main/java/com/gameocr/app/download/ModelDownloadManager.kt").readText()
        val worker = sourceFile("src/main/java/com/gameocr/app/download/ModelDownloadWorker.kt").readText()
        val checker = sourceFile("src/main/java/com/gameocr/app/download/ModelReadinessChecker.kt").readText()
        val settings = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        assertTrue(manager.contains("val requiredSpecs = ModelDownloadDependencies.expand(specs)"))
        assertTrue(manager.contains("KEY_SPECS, requiredSpecs.map"))
        assertTrue("old persisted work must also repair dependencies", worker.contains("?.let(ModelDownloadDependencies::expand)"))
        assertTrue(checker.contains("modelReadinessWithDependencies(spec, ::checkArtifact)"))
        assertTrue(checker.contains("fun mangaOcr(): ModelReadiness = check(ModelDownloadSpec.mangaOcr())"))
        val mangaDownload = settings.substringAfter("viewModel.downloadMangaOcrModels")
            .substringBefore("onImport =")
        assertFalse("no UI-owned dependent download", mangaDownload.contains("downloadPaddleModels"))
        assertFalse("no swallowed dependent failure", settings.contains("cascade Paddle download after manga-ocr failed"))
        assertTrue("dependency progress is associated with Manga", worker.contains("KEY_SPECS to requiredSpecs.map"))
        assertTrue(settings.contains("item.specs.ifEmpty { listOfNotNull(item.spec) }"))
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
