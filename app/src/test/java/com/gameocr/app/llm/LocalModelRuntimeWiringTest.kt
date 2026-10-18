package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRuntimeWiringTest {
    private fun source(path: String) = listOf(File(path), File("../$path")).first(File::isFile)
        .readText().replace("\r\n", "\n")

    @Test
    fun settingsReachInitializationRuntimeAndIdleWithoutRacingInference() {
        val holder = source("app/src/main/java/com/gameocr/app/llm/LlamaEngineHolder.kt")
        val binding = source("llama-android/build.gradle.kts")
        val jni = source("app/src/main/java/com/gameocr/app/llm/LlamaTemplateGeneration.kt")
        data class Case(val label: String, val text: String, val required: String)
        listOf(
            Case("allocation fingerprint only", holder, "config?.normalized()"),
            Case("separate allocation key", holder, "value?.initializationFingerprint()"),
            Case("environment is wired", holder, "LocalModelInitializationPolicy.nativeEnvironment"),
            Case("runtime sampler and threads update", holder, "LlamaTemplateGeneration.configureRuntime()"),
            Case("effective policy diagnostics", holder, "LlamaTemplateGeneration.initializationInfo()"),
            Case("saving joins lifecycle", holder, "suspend fun applyManagedConfiguration(id: String) = lifecycleGate.withSession"),
            Case("disabled idle timer", holder, "if (timeout <= 0L || _currentModelId.value == null)"),
            Case("idle checks again after gate", holder, "idleTimeoutMs != expectedTimeout"),
            Case("idle starts when inference finishes", holder, "finally {\n            if (_currentModelId.value != null) touchInternal()"),
            Case("cancelled initialization keeps the cancellation", holder, "runCatching { e.cleanUp() }\n                    .onFailure { Timber.w(it, \"cleanup after cancelled initialization\") }"),
            Case("system prompt failures do not count as ready", holder, "e.setSystemPrompt(systemPrompt)"),
            Case("native memory reader has a loading guard", jni, "nativeLibrary.call { inspectMemoryFileNative(path) }"),
            Case("generated binding leaves submodule untouched", binding, "generated/llamaBinding/java"),
            Case("error cleanup actually releases native resources", binding, "_readyForSystemPrompt = false\n                    unload()"),
            Case("binding patch fails loudly on upstream changes", binding, "Upstream Error cleanup no longer matches"),
        ).forEach { assertTrue(it.label, it.text.contains(it.required)) }
    }
}
