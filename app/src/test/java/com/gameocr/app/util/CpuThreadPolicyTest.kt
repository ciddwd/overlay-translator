package com.gameocr.app.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuThreadPolicyTest {

    @Test
    fun select_coversPhoneCoreCountCases() {
        data class Case(
            val name: String,
            val availableProcessors: Int,
            val expectedThreads: Int,
        )

        val cases = listOf(
            Case("invalid negative", -1, 1),
            Case("invalid zero", 0, 1),
            Case("single core", 1, 1),
            Case("dual core", 2, 2),
            Case("three cores", 3, 2),
            Case("four cores", 4, 2),
            Case("five cores", 5, 4),
            Case("six cores", 6, 4),
            Case("seven cores use maximum inference threads", 7, 6),
            Case("typical eight core phone uses maximum inference threads", 8, 6),
            Case("many cores capped", 12, 6),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedThreads,
                CpuThreadPolicy.select(case.availableProcessors),
            )
        }
    }

    @Test
    fun nativePolicy_matchesJvmThreadBuckets() {
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
            Case("invalid core count is clamped", "std::max(1, available_processors)"),
            Case("one core selects one thread", "if (processors == 1) return 1"),
            Case("up to four cores selects two threads", "if (processors <= 4) return 2"),
            Case("up to six cores selects four threads", "if (processors <= 6) return 4"),
            Case("seven or more cores selects six threads", "return 6"),
            Case("runtime log reports six-thread cap", "selected=%d max=6"),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }
}
