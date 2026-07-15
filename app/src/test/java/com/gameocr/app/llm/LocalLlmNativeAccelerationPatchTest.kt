package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmNativeAccelerationPatchTest {

    @Test
    fun nativeRouting_distinguishesCpuVulkanAndFallbackCases() {
        val source = listOf(
            File("../llama-android/src/main/cpp/llama_thread_policy.cpp"),
            File("llama-android/src/main/cpp/llama_thread_policy.cpp"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("llama_thread_policy.cpp not found")

        data class Case(
            val name: String,
            val marker: String,
        )

        listOf(
            Case(
                "CPU mode supplies an explicit empty device list",
                "ggml_backend_dev_t no_offload_devices[] = {nullptr}",
            ),
            Case(
                "Vulkan mode retains llama.cpp default device discovery",
                "params.devices = use_vulkan ? nullptr : no_offload_devices",
            ),
            Case(
                "requested acceleration is configured before model load",
                "configure_model_acceleration(params, use_vulkan)",
            ),
            Case(
                "failed Vulkan load explicitly falls back to CPU devices",
                "configure_model_acceleration(params, false)",
            ),
            Case(
                "runtime log exposes effective device selection",
                "nGpuLayers=%d devices=%s",
            ),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }

        assertEquals(
            "acceleration configuration must cover initial load and CPU fallback only",
            2,
            Regex("configure_model_acceleration\\(params, ").findAll(source).count(),
        )
    }
}
