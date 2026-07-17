package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLlmPrewarmPolicyTest {

    @Test
    fun decide_coversRoutingAndInstallationCases() {
        data class Case(
            val name: String,
            val routedToLocalModel: Boolean,
            val modelInstalled: Boolean,
            val expected: LocalLlmPrewarmDecision,
        )

        val cases = listOf(
            Case("cloud engine with no model", false, false, LocalLlmPrewarmDecision.SKIP_NON_LOCAL_OR_FALLBACK),
            Case("cloud engine ignores installed local model", false, true, LocalLlmPrewarmDecision.SKIP_NON_LOCAL_OR_FALLBACK),
            Case("local route without downloaded model", true, false, LocalLlmPrewarmDecision.SKIP_MODEL_NOT_INSTALLED),
            Case("local route with installed model", true, true, LocalLlmPrewarmDecision.PREWARM),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LocalLlmPrewarmPolicy.decide(
                    routedToLocalModel = case.routedToLocalModel,
                    modelInstalled = case.modelInstalled,
                ),
            )
        }
    }
}
