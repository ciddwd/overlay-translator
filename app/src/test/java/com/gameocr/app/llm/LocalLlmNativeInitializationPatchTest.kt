package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-contract tests, not a claim that a particular GPU/KV configuration ran on-device. */
class LocalLlmNativeInitializationPatchTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n") ?: error("Missing source: $path")

    private val cmake get() = source("llama-android/src/main/cpp/CMakeLists.txt")
    private val adapter get() = source("llama-android/src/main/cpp/llama_thread_policy.cpp")
    private val template get() = source("llama-android/src/main/cpp/llama_template_generation.inc")

    private fun block(source: String, name: String): String =
        Regex("(?s)set\\(${Regex.escape(name)} \\[=\\[(.*?)\\]=\\]\\)")
            .find(source)?.groupValues?.get(1) ?: error("Missing guarded patch $name")

    @Test
    fun nativeOwnershipPatches_matchPinnedUpstreamAndCoverPartialFailureStates() {
        val build = cmake
        val upstream = source("third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp")
        listOf("LOAD", "PREPARE", "UNLOAD").forEach { name ->
            assertTrue("$name original must match pinned source", upstream.contains(block(build, "GAMEOCR_${name}_ORIGINAL")))
            assertTrue("$name must fail closed on upstream changes", build.contains("GAMEOCR_${name}_INDEX EQUAL -1"))
        }
        data class Case(val name: String, val marker: String)
        listOf(
            Case("load begins by freeing any previous resources", "gameocr_release_loaded_resources();\n    if (jmodel_path"),
            Case("old model ownership transferred before CPU retry", "g_model = nullptr; // retry helper takes ownership"),
            Case("context failure retries the entire CPU/F16 model", "g_model = gameocr_retry_cpu_f16_model(previous)"),
            Case("second context failure releases weights", "if (g_context == nullptr) { gameocr_release_loaded_resources(); return 1; }"),
            Case("chat template failure reaches catch cleanup", "native chat template initialization failed"),
            Case("sampler failure reaches catch cleanup", "native sampler initialization failed"),
            Case("batch uses automatic ownership", "g_batch = common_batch(g_context);"),
            Case("batch allocation is validated", "if (!g_batch.batch)"),
            Case("double unload cannot double free sampler", "g_sampler = nullptr;"),
            Case("double unload cannot double free context", "g_context = nullptr;"),
            Case("double unload cannot double free model", "g_model = nullptr;"),
            Case("unload does not clear KV through a null context", "reset_long_term_states(false)"),
            Case("unknown C++ exception stays inside JNI", "prepare initialization failed with unknown native exception"),
        ).forEach { assertTrue(it.name, build.contains(it.marker)) }
    }

    @Test
    fun initializationEnvironment_hasControlledDefaultsAndNativeBounds() {
        val native = adapter
        data class Case(val name: String, val marker: String)
        listOf(
            Case("positive logical batch bounds", "\"GAMEOCR_BATCH_TOKENS\", 512, 32, 2048"),
            Case("positive physical microbatch bounds", "\"GAMEOCR_MICRO_BATCH_TOKENS\", 512, 32, 2048"),
            Case("microbatch cannot exceed logical batch", "params.n_ubatch = std::min(params.n_batch"),
            Case("default mmap on; nonboolean rejected", "\"GAMEOCR_USE_MMAP\", 1, 0, 1"),
            Case("default mlock off; nonboolean rejected", "\"GAMEOCR_USE_MLOCK\", 0, 0, 1"),
            Case("GPU allows CPU, partial and all layers", "\"GAMEOCR_GPU_LAYERS\", requested ? -1 : 0, -1, 999"),
            Case("threads limited to actual CPU count", "std::min(processors, 64)"),
            Case("invalid cache type falls back to f16", "invalid cache environment %s=%s; keeping f16"),
            Case("FA invalid option keeps auto", "invalid Flash Attention environment %s; keeping auto"),
            Case("safe fallback restores F16 K", "initialization_safe_fallback ? GGML_TYPE_F16 : cache_type_from_environment(\"GAMEOCR_CACHE_TYPE_K\")"),
            Case("safe fallback restores F16 V", "initialization_safe_fallback ? GGML_TYPE_F16 : cache_type_from_environment(\"GAMEOCR_CACHE_TYPE_V\")"),
            Case("safe fallback disables Flash Attention", "initialization_safe_fallback ? LLAMA_FLASH_ATTN_TYPE_DISABLED"),
            Case("shared KV pipeline stays enabled", "params.kv_unified = true"),
            Case("eight translations plus system slot remain", "params.n_seq_max = 9"),
            Case("Vulkan must have a real GPU device", "ggml_backend_reg_dev_count(backend)"),
        ).forEach { assertTrue(it.name, native.contains(it.marker)) }
        listOf("f16", "q8_0", "q4_0").forEach { type ->
            assertTrue("allowed cache $type", native.contains("std::strcmp(value, \"$type\")"))
        }
        listOf("auto", "on", "off").forEach { mode ->
            assertTrue("allowed FA $mode", native.contains("std::strcmp(value, \"$mode\")"))
        }
    }

    @Test
    fun legacyDecodeBudgets_followActualContextAndBatchInsteadOfFixedConstants() {
        val build = cmake
        assertTrue(build.contains("i += (int) llama_n_batch(context)"))
        assertTrue(build.contains("tokens.size() - i, (int) llama_n_batch(context)"))
        assertTrue(build.contains("(int) llama_n_ctx(g_context) - OVERFLOW_HEADROOM"))
        assertTrue(block(build, "GAMEOCR_PREPARE_PATCHED").contains("common_batch(g_context)"))
        assertFalse(block(build, "GAMEOCR_PREPARE_PATCHED").contains("llama_batch_init"))
    }

    @Test
    fun lightweightRuntimeUpdate_doesNotReloadWeightsAndReportsActualValues() {
        assertTrue(cmake.contains("gameocr_native_runtime_info(llama_context * context);"))
        assertTrue(adapter.contains("gameocr_native_runtime_info(llama_context * context) {"))
        val bridge = template.substringAfter("LlamaTemplateGeneration_configureRuntimeNative")
            .substringBefore("LlamaTemplateGeneration_initializationInfoNative")
        assertTrue(bridge.contains("gameocr_configure_runtime_threads(g_context)"))
        assertTrue(bridge.contains("g_sampler = replacement.release()"))
        assertFalse(bridge.contains("llama_model_load"))
        assertFalse(bridge.contains("llama_init_from_model"))
        assertTrue(adapter.contains("llama_set_n_threads(context, params.n_threads, params.n_threads_batch)"))
        listOf("actualGenerationThreads", "actualPromptThreads", "actualTemperature", "actualTopP", "actualTopK",
            "actualRepeatPenalty", "configuredGpuLayers", "mmapRequested", "mlockRequested", "safeFallback")
            .forEach { assertTrue("runtime diagnostics $it", adapter.contains(it)) }
    }

    @Test
    fun metadataProtocol_isHeaderOnlyAndIndependentOfExistingThreeFieldInspection() {
        val code = template
        val memory = code.substringAfter("LlamaTemplateGeneration_inspectMemoryFileNative")
            .substringBefore("LlamaTemplateGeneration_inspectFileNative")
        assertTrue(memory.contains("options.no_alloc = true"))
        assertTrue(memory.contains("options.ctx = nullptr"))
        assertTrue(memory.contains("std::unique_ptr<gguf_context"))
        assertFalse(memory.contains("llama_model_load"))
        val fields = listOf(".context_length", ".embedding_length", ".block_count", ".attention.head_count",
            ".attention.head_count_kv", ".attention.key_length", ".attention.value_length", ".attention.sliding_window",
            ".ssm.state_size", ".ssm.inner_size", ".ssm.conv_kernel", ".full_attention_interval")
        fields.forEach { assertTrue("metadata $it", memory.contains("number(\"$it\")")) }
        assertEquals(12, fields.size) // 12 numeric fields + architecture + vocabulary = 14.
        assertTrue(memory.contains("tokenizer.ggml.tokens"))
        assertTrue(code.contains("gguf_get_arr_type(header, index)"))
        val existing = code.substringAfter("LlamaTemplateGeneration_inspectFileNative")
            .substringBefore("LlamaTemplateGeneration_metadataNative")
        assertTrue(existing.contains("get_string(\"general.architecture\"), quantization,"))
        assertTrue(existing.contains("get_string(\"tokenizer.chat_template\")"))
    }

    @Test
    fun cancellationAndExceptionCleanup_synchronizesBeforeFreeingAsyncResources() {
        val code = template
        assertTrue(code.contains("gameocr_multi_resources resources;\n    // Destruction is reverse declaration order"))
        assertTrue(cmake.contains("if (g_context) llama_synchronize(g_context)"))
        assertTrue(code.contains("samplers.push_back(sampler.get())"))
        assertTrue(code.contains("s.sampler = sampler.release()"))
        assertTrue(code.contains("llama_set_abort_callback(g_context, nullptr, nullptr)"))
        val gradle = source("llama-android/build.gradle.kts")
        val mirror = gradle.substringAfter("val cleanedError =").substringBefore(".trimIndent()")
        assertTrue("Error in generated Kotlin binding releases native resources", mirror.contains("unload()"))
        assertTrue("only project build mirror is written", gradle.contains("generated/llamaBinding/java"))
    }
}
