package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmNativePerfPatchTest {

    @Test
    fun buildPatch_reportsTokenLevelPerformanceForEveryNormalExit() {
        val source = listOf(
            File("../llama-android/src/main/cpp/CMakeLists.txt"),
            File("llama-android/src/main/cpp/CMakeLists.txt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("llama-android CMakeLists.txt not found")

        data class Case(val name: String, val marker: String)
        val cases = listOf(
            Case("request preparation time is captured", "gameocr_request_init_ms"),
            Case("native counters reset before prefill", "llama_perf_context_reset(g_context)"),
            Case("native counters are read at completion", "llama_perf_context(g_context)"),
            Case("prefill duration is reported", "Prefill: %.1f ms"),
            Case("prefill token count and throughput are reported", "(%d tok, %.1f tok/s)"),
            Case("decode duration is reported", "Decode: %.1f ms"),
            Case("total wall time is reported", "Total: %.1f ms"),
            Case("end-of-generation logs stats", "gameocr_log_request_stats(\"eog\")"),
            Case("generation limit logs stats", "gameocr_log_request_stats(\"limit\")"),
            Case("prefill failure logs stats", "gameocr_log_request_stats(\"prefill_error\")"),
            Case("decode failure logs stats", "gameocr_log_request_stats(\"decode_error\")"),
            Case("performance state patch is guarded", "performance state patch no longer matches upstream"),
            Case("prefill patch is guarded", "prefill performance patch no longer matches upstream"),
            Case("limit patch is guarded", "generation-limit performance patch no longer matches upstream"),
            Case("decode error patch is guarded", "decode-error performance patch no longer matches upstream"),
            Case("EOG patch is guarded", "EOG performance patch no longer matches upstream"),
        )

        cases.forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }
}
