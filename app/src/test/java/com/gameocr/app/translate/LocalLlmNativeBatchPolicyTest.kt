package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmNativeBatchPolicyTest {

    @Test
    fun batchResultUpdates_tableDriven_restoreEveryOriginalIndex() {
        data class Case(
            val name: String,
            val indexes: List<Int>,
            val translated: String?,
            val expected: List<BatchTranslationUpdate>,
        )

        val cases = listOf(
            Case(
                "single result",
                listOf(2),
                "译文",
                listOf(BatchTranslationUpdate(2, "译文", elapsedMs = 321L)),
            ),
            Case(
                "deduplicated source restores duplicate positions",
                listOf(0, 3, 7),
                "相同译文",
                listOf(
                    BatchTranslationUpdate(0, "相同译文", elapsedMs = 321L),
                    BatchTranslationUpdate(3, "相同译文", elapsedMs = 321L),
                    BatchTranslationUpdate(7, "相同译文", elapsedMs = 321L),
                ),
            ),
            Case(
                "null failure is still emitted",
                listOf(1),
                null,
                listOf(BatchTranslationUpdate(1, null, elapsedMs = 321L)),
            ),
            Case("empty index list", emptyList(), "unused", emptyList()),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                localLlmBatchResultUpdates(case.indexes, case.translated, elapsedMs = 321L),
            )
        }
    }

    @Test
    fun selectedBatchSize_acceptsOnlyMeasuredVariantsAndNativeCapacity() {
        data class Case(
            val name: String,
            val requested: Int,
            val nativeCapacity: Int,
            val expected: Int,
        )

        listOf(
            Case("B1 baseline", 1, 8, 1),
            Case("B2", 2, 8, 2),
            Case("B4 default", 4, 8, 4),
            Case("B8", 8, 8, 8),
            Case("native capacity caps request", 8, 4, 4),
            Case("missing native capacity fails closed", 8, 0, 1),
            Case("unsupported value fails closed", 3, 8, 1),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LocalLlmNativeBatchPolicy.selectedBatchSize(
                    requested = case.requested,
                    nativeSequenceCapacity = case.nativeCapacity,
                ),
            )
        }
    }

    @Test
    fun plan_respectsSequencePromptAndUnifiedKvLimits() {
        data class Case(
            val name: String,
            val promptTokens: List<Int>,
            val requestedBatchSize: Int = 4,
            val configuredContext: Int = 2_048,
            val engineContext: Int = 8_192,
            val systemTokens: Int = 64,
            val nativePromptBatch: Int = 512,
            val nativeSequenceCapacity: Int = 8,
            val maxNewTokens: Int = 256,
            val expectedGroupSizes: List<Int>,
            val expectedNativeFlags: List<Boolean>,
        )

        val cases = listOf(
            Case(
                name = "empty page",
                promptTokens = emptyList(),
                expectedGroupSizes = emptyList(),
                expectedNativeFlags = emptyList(),
            ),
            Case(
                name = "fifteen manga bubbles use B4 waves",
                promptTokens = List(15) { 32 },
                expectedGroupSizes = listOf(4, 4, 4, 3),
                expectedNativeFlags = listOf(true, true, true, true),
            ),
            Case(
                name = "B1 keeps the serial baseline",
                promptTokens = List(3) { 32 },
                requestedBatchSize = 1,
                expectedGroupSizes = listOf(1, 1, 1),
                expectedNativeFlags = listOf(false, false, false),
            ),
            Case(
                name = "B2 comparison",
                promptTokens = List(5) { 32 },
                requestedBatchSize = 2,
                expectedGroupSizes = listOf(2, 2, 1),
                expectedNativeFlags = listOf(true, true, false),
            ),
            Case(
                name = "B8 comparison",
                promptTokens = List(15) { 32 },
                requestedBatchSize = 8,
                expectedGroupSizes = listOf(8, 7),
                expectedNativeFlags = listOf(true, true),
            ),
            Case(
                name = "logical prompt batch splits before llama decode",
                promptTokens = List(5) { 40 },
                nativePromptBatch = 100,
                expectedGroupSizes = listOf(2, 2, 1),
                expectedNativeFlags = listOf(true, true, false),
            ),
            Case(
                name = "unified KV capacity splits groups",
                promptTokens = List(6) { 50 },
                engineContext = 1_000,
                systemTokens = 100,
                maxNewTokens = 200,
                expectedGroupSizes = listOf(3, 3),
                expectedNativeFlags = listOf(true, true),
            ),
            Case(
                name = "per-sequence context rejects oversized prompts",
                promptTokens = List(3) { 40 },
                configuredContext = 300,
                systemTokens = 60,
                maxNewTokens = 200,
                expectedGroupSizes = listOf(1, 1, 1),
                expectedNativeFlags = listOf(false, false, false),
            ),
            Case(
                name = "missing token count fails only that sequence closed",
                promptTokens = listOf(20, -1, 20),
                expectedGroupSizes = listOf(1, 1, 1),
                expectedNativeFlags = listOf(false, false, false),
            ),
            Case(
                name = "native sequence capacity caps B8",
                promptTokens = List(5) { 32 },
                requestedBatchSize = 8,
                nativeSequenceCapacity = 2,
                expectedGroupSizes = listOf(2, 2, 1),
                expectedNativeFlags = listOf(true, true, false),
            ),
        )

        cases.forEach { case ->
            val plans = LocalLlmNativeBatchPolicy.plan(
                items = case.promptTokens,
                requestedBatchSize = case.requestedBatchSize,
                configuredContextTokens = case.configuredContext,
                engineContextTokens = case.engineContext,
                systemPromptTokens = case.systemTokens,
                nativePromptBatchTokens = case.nativePromptBatch,
                nativeSequenceCapacity = case.nativeSequenceCapacity,
                maxNewTokensPerItem = case.maxNewTokens,
                promptTokenCount = { it },
            )
            assertEquals(case.name, case.expectedGroupSizes, plans.map { it.items.size })
            assertEquals(case.name, case.expectedNativeFlags, plans.map { it.nativeBatch })
            plans.filter { it.nativeBatch }.forEach { plan ->
                assertTrue(case.name, plan.decodedPromptTokens <= case.nativePromptBatch)
                assertTrue(
                    case.name,
                    plan.requiredKvTokens + LocalLlmNativeBatchPolicy.CONTEXT_HEADROOM_TOKENS <=
                        case.engineContext,
                )
            }
        }
    }

    @Test
    fun plan_tableDriven_countsSharedPromptPrefixOnceWithoutRelaxingKvLimits() {
        data class PromptCost(
            val fullTokens: Int,
            val sharedPrefixTokens: Int,
        )
        data class Case(
            val name: String,
            val prompts: List<PromptCost>,
            val effectiveCounterAvailable: Boolean = true,
            val effectiveCounterValid: Boolean = true,
            val engineContext: Int = 8_192,
            val expectedGroupSizes: List<Int>,
            val expectedDecodedTokens: List<Int>,
            val expectedNativeFlags: List<Boolean>,
        )

        listOf(
            Case(
                name = "long page forms B4 waves when a large prefix is shared",
                prompts = List(19) { PromptCost(fullTokens = 350, sharedPrefixTokens = 300) },
                expectedGroupSizes = listOf(4, 4, 4, 4, 3),
                expectedDecodedTokens = listOf(500, 500, 500, 500, 450),
                expectedNativeFlags = listOf(true, true, true, true, true),
            ),
            Case(
                name = "no shared prefix remains serial under the same decode capacity",
                prompts = List(4) { PromptCost(fullTokens = 350, sharedPrefixTokens = 0) },
                expectedGroupSizes = listOf(1, 1, 1, 1),
                expectedDecodedTokens = listOf(350, 350, 350, 350),
                expectedNativeFlags = listOf(false, false, false, false),
            ),
            Case(
                name = "effective counter failure fails closed to serial",
                prompts = List(3) { PromptCost(fullTokens = 350, sharedPrefixTokens = 300) },
                effectiveCounterValid = false,
                expectedGroupSizes = listOf(1, 1, 1),
                expectedDecodedTokens = listOf(350, 350, 350),
                expectedNativeFlags = listOf(false, false, false),
            ),
            Case(
                name = "missing effective counter preserves the previous conservative policy",
                prompts = List(3) { PromptCost(fullTokens = 350, sharedPrefixTokens = 300) },
                effectiveCounterAvailable = false,
                expectedGroupSizes = listOf(1, 1, 1),
                expectedDecodedTokens = listOf(350, 350, 350),
                expectedNativeFlags = listOf(false, false, false),
            ),
            Case(
                name = "shared decode fit cannot bypass unified KV capacity",
                prompts = List(4) { PromptCost(fullTokens = 350, sharedPrefixTokens = 300) },
                engineContext = 1_200,
                expectedGroupSizes = listOf(1, 1, 1, 1),
                expectedDecodedTokens = listOf(350, 350, 350, 350),
                expectedNativeFlags = listOf(false, false, false, false),
            ),
        ).forEach { case ->
            val effectiveCounter = if (case.effectiveCounterAvailable) {
                { group: List<PromptCost> ->
                    if (!case.effectiveCounterValid) {
                        -1
                    } else {
                        val shared = group.minOf(PromptCost::sharedPrefixTokens)
                        group.sumOf(PromptCost::fullTokens) - shared * (group.size - 1)
                    }
                }
            } else {
                null
            }
            val plans = LocalLlmNativeBatchPolicy.plan(
                items = case.prompts,
                requestedBatchSize = 4,
                configuredContextTokens = 2_048,
                engineContextTokens = case.engineContext,
                systemPromptTokens = 0,
                nativePromptBatchTokens = 512,
                nativeSequenceCapacity = 8,
                maxNewTokensPerItem = 256,
                promptTokenCount = PromptCost::fullTokens,
                effectivePromptTokenCount = effectiveCounter,
            )

            assertEquals(case.name, case.expectedGroupSizes, plans.map { it.items.size })
            assertEquals(case.name, case.expectedDecodedTokens, plans.map { it.decodedPromptTokens })
            assertEquals(case.name, case.expectedNativeFlags, plans.map { it.nativeBatch })
        }
    }

    @Test
    fun nativeMultiSequenceWiring_usesIndependentSamplersSharedSystemKvAndB4Default() {
        val root = listOf(File(".."), File(".")).firstOrNull {
            File(it, "llama-android/src/main/cpp/llama_multi_sequence.inc").isFile
        } ?: error("repository root not found")
        val native = File(root, "llama-android/src/main/cpp/llama_multi_sequence.inc").readText()
        val cmake = File(root, "llama-android/src/main/cpp/CMakeLists.txt").readText()
        val contextPolicy = File(root, "llama-android/src/main/cpp/llama_thread_policy.cpp").readText()
        val kotlin = File(root, "app/src/main/java/com/gameocr/app/llm/LlamaMultiSequence.kt").readText()
        val promptMetrics = File(root, "app/src/main/java/com/gameocr/app/llm/LlamaPromptMetrics.kt").readText()
        val translator = File(root, "app/src/main/java/com/gameocr/app/translate/LocalLlamaTranslator.kt").readText()
        val gradle = File(root, "app/build.gradle.kts").readText()

        data class Case(val name: String, val marker: String, val source: String)
        listOf(
            Case("JNI entry point", "LlamaMultiSequence_generateNative", native),
            Case("system KV is copied", "llama_memory_seq_cp", native),
            Case("each sequence owns a sampler", "sequence.sampler = new_sampler", native),
            Case("generation steps share llama decode", "while (resources.batch.n_tokens > 0)", native),
            Case("native timing is observable", "MULTI STATS", native),
            Case("native failures are recoverable", "return nullptr", native),
            Case("overlay is appended without submodule edits", "llama_multi_sequence.inc", cmake),
            Case("overlay changes trigger CMake regeneration", "CMAKE_CONFIGURE_DEPENDS", cmake),
            Case("context reserves system plus eight clients", "params.n_seq_max = 9", contextPolicy),
            Case("unified KV keeps full sequence context", "params.kv_unified = true", contextPolicy),
            Case("Kotlin validates one through eight prompts", "1..MAX_SEQUENCE_COUNT", kotlin),
            Case("shared user prompt prefix is decoded once", "shared_prefix_tokens", native),
            Case("planner uses the same exact tokenizer cost", "effectiveUserPromptBatchTokens", promptMetrics),
            Case("native exposes exact shared-prefix batch cost", "gameocr_effective_user_prompt_batch_tokens", native),
            Case("main batches forward per-item line caps", "maxOutputLines = lineCaps", translator),
            Case("main batches opt into strict invalid markers", "markLimitAsInvalid = markNativeBatchLineLimitAsInvalid", translator),
            Case("shared prefix KV is copied to each sequence", "system_prompt_position + (int32_t) shared_prefix_tokens", native),
            Case("request prefix cannot leak into the next call", "llama_memory_seq_rm(memory, 0, system_prompt_position, -1)", native),
            Case("each sequence has an independent output budget", "sequence.predict_length", native),
            Case("Sakura can stop before an extra output line", "sequence.max_output_lines", native),
            Case("strict output limits are observable", "lineLimitStops=%d tokenLimitStops=%d", native),
            Case("B4 is the measured default", ".orElse(\"4\")", gradle),
            Case("all A/B sizes are accepted", "setOf(1, 2, 4, 8)", gradle),
        ).forEach { case -> assertTrue(case.name, case.source.contains(case.marker)) }
    }
}
