package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmPrewarmWiringTest {

    @Test
    fun prewarm_loadsAndRunsOneRealInferenceUnderTheGlobalLock() {
        val source = listOf(
            File("../app/src/main/java/com/gameocr/app/translate/LocalLlamaTranslator.kt"),
            File("app/src/main/java/com/gameocr/app/translate/LocalLlamaTranslator.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("LocalLlamaTranslator.kt not found")

        data class Case(val name: String, val marker: String)
        val cases = listOf(
            Case("model is loaded before inference", "holder.ensureLoaded(modelKind, systemPrompt)"),
            Case("real inference uses the shared engine lock", "holder.inferenceMutex.withLock"),
            Case("prewarm submits a user prompt", "engine.sendUserPrompt("),
            Case("prewarm consumes generated output", ").collect { outputPieces++ }"),
            Case("prewarm is limited to one generated token", "PREWARM_PREDICT_LENGTH = 1"),
            Case("idle timeout is refreshed after prewarm", "holder.touch()"),
            Case("prewarm timing is separated from normal generation", "prewarm completed kind=%s modelReadyMs=%d inferenceMs=%d"),
        )

        cases.forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }
}
