package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaEngineLifecycleWiringTest {

    @Test
    fun nativeStateAccesses_shareTheLifecycleSession() {
        val holder = source("app/src/main/java/com/gameocr/app/llm/LlamaEngineHolder.kt")
        val translator = source("app/src/main/java/com/gameocr/app/translate/LocalLlamaTranslator.kt")
        val sakura = source("app/src/main/java/com/gameocr/app/translate/SakuraGalTranslator.kt")
        val nativeBuild = source("llama-android/src/main/cpp/CMakeLists.txt")

        data class Case(val name: String, val source: String, val marker: String)
        val cases = listOf(
            Case("holder owns one lifecycle gate", holder, "private val lifecycleGate = LlamaEngineLifecycleGate()"),
            Case("session acquires the lifecycle gate", holder, "): T = lifecycleGate.withSession {"),
            Case("session loads while holding the gate", holder, "action(ensureLoaded(kind, systemPrompt))"),
            Case("unload acquires the lifecycle gate", holder, "suspend fun unload() = lifecycleGate.withSession {"),
            Case("normal local translation uses sessions", translator, "holder.withEngineSession(modelKind, systemPrompt)"),
            Case("Sakura token planning uses a session", sakura, "val groups = holder.withEngineSession(modelKind, systemPrompt)"),
            Case("native prompt metrics reject a missing template", nativeBuild, "g_chat_templates == nullptr"),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }
        assertFalse("translator must not split load from locking", translator.contains("holder.ensureLoaded("))
        assertFalse("legacy inference lock must not escape the holder", translator.contains("holder.inferenceMutex"))
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
