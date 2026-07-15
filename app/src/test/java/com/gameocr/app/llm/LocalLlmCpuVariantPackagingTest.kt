package com.gameocr.app.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmCpuVariantPackagingTest {

    @Test
    fun androidCpuVariants_keepOnlyArmv82WithoutI8mm() {
        val source = listOf(
            File("../llama-android/build.gradle.kts"),
            File("llama-android/build.gradle.kts"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("llama-android build.gradle.kts not found")

        data class Case(
            val variant: String,
            val expectedExcluded: Boolean,
        )

        listOf(
            Case("android_armv8.0_1", true),
            Case("android_armv8.2_1", true),
            Case("android_armv8.2_2", false),
            Case("android_armv8.6_1", true),
            Case("android_armv9.0_1", true),
            Case("android_armv9.2_1", true),
            Case("android_armv9.2_2", true),
        ).forEach { case ->
            val exclusion = "**/libggml-cpu-${case.variant}.so"
            assertEquals(
                case.variant,
                case.expectedExcluded,
                source.contains(exclusion),
            )
        }

        assertTrue("variant rationale names the failing i8mm path", source.contains("i8mm path produced"))
    }
}
