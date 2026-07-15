package com.gameocr.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmAccelerationPolicyTest {

    @Test
    fun selection_coversEveryLocalModelKind() {
        data class Case(
            val name: String,
            val kind: LlmModelKind,
            val experimentalVulkanEnabled: Boolean,
            val expectedVulkan: Boolean,
            val expectedNativeFlag: String,
        )

        val cases = listOf(
            Case(
                name = "Sakura defaults to CPU after Adreno correctness failure",
                kind = LlmModelKind.SAKURA_1_5B_Q4,
                experimentalVulkanEnabled = false,
                expectedVulkan = false,
                expectedNativeFlag = "0",
            ),
            Case(
                name = "Hy-MT defaults to CPU",
                kind = LlmModelKind.HY_MT2_1_8B_Q4_K_M,
                experimentalVulkanEnabled = false,
                expectedVulkan = false,
                expectedNativeFlag = "0",
            ),
            Case(
                name = "Sakura can still opt into experimental Vulkan",
                kind = LlmModelKind.SAKURA_1_5B_Q4,
                experimentalVulkanEnabled = true,
                expectedVulkan = true,
                expectedNativeFlag = "1",
            ),
            Case(
                name = "Hy-MT ignores the experimental Vulkan opt-in",
                kind = LlmModelKind.HY_MT2_1_8B_Q4_K_M,
                experimentalVulkanEnabled = true,
                expectedVulkan = false,
                expectedNativeFlag = "0",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedVulkan,
                LocalLlmAccelerationPolicy.requestsVulkan(
                    case.kind,
                    case.experimentalVulkanEnabled,
                ),
            )
            assertEquals(
                case.name,
                case.expectedNativeFlag,
                LocalLlmAccelerationPolicy.nativeFlag(
                    case.kind,
                    case.experimentalVulkanEnabled,
                ),
            )
        }
    }
}
