package com.gameocr.app.ocr

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTextMaskEquivalenceTest {
    private data class Case(
        val name: String,
        val background: Int,
        val foreground: Int,
        val noise: Boolean = false,
        val emptyProbability: Boolean = false,
        val polygons: List<MangaMaskDebugAnalyzer.Polygon> = listOf(rect(12f, 10f, 146f, 96f)),
    )

    @Test
    fun textMasks_matchPreOptimizationGoldenPixels() {
        val cases = listOf(
            Case("dark-on-white", 0xFFF4F4F4.toInt(), 0xFF141414.toInt()),
            Case("white-on-dark", 0xFF222222.toInt(), 0xFFF2F2F2.toInt()),
            Case("low-contrast", 0xFF888888.toInt(), 0xFF808080.toInt()),
            Case("busy-background", 0xFFAAAAAA.toInt(), 0xFF222222.toInt(), noise = true),
            Case("empty-probability", -1, 0xFF111111.toInt(), emptyProbability = true),
            Case("no-polygons", -1, 0xFF111111.toInt(), polygons = emptyList()),
            Case("overlapping", -1, 0xFF222222.toInt(), polygons = listOf(
                rect(5f, 4f, 94f, 85f), rect(72f, 31f, 158f, 104f),
            )),
            Case("clipped", -1, 0xFF222222.toInt(), polygons = listOf(rect(-20f, -30f, 105f, 132f))),
            Case("slanted", -1, 0xFF222222.toInt(), polygons = listOf(
                MangaMaskDebugAnalyzer.Polygon(listOf(
                    MangaMaskDebugAnalyzer.Point(24.5f, 4.5f),
                    MangaMaskDebugAnalyzer.Point(154.5f, 35.5f),
                    MangaMaskDebugAnalyzer.Point(131.5f, 103.5f),
                    MangaMaskDebugAnalyzer.Point(1.5f, 72.5f),
                )),
            )),
            Case("tiny", -1, 0xFF222222.toInt(), polygons = listOf(rect(4f, 4f, 7f, 7f))),
        )
        cases.forEach { case ->
            val analysis = analyze(case, 164, 112)
            val hash = hash(analysis.textEraseMask)
            println("MASK_GOLDEN ${case.name} $hash pixels=${analysis.textEraseMask.count { it }}")
            assertEquals(case.name, 164 * 112, analysis.textEraseMask.size)
            assertEquals(case.name, GOLDEN_HASHES.getValue(case.name), hash)
            assertTrue(case.name, analysis.bubbles.isEmpty())
        }
    }

    @Test
    fun maskAnalysis_boundedDiagnosticBenchmark() {
        val case = Case("large", 0xFFE8E8E8.toInt(), 0xFF232323.toInt(), polygons = listOf(
            rect(40f, 45f, 440f, 200f), rect(460f, 45f, 950f, 200f),
            rect(50f, 250f, 240f, 370f), rect(270f, 250f, 540f, 370f),
            rect(550f, 250f, 790f, 370f), rect(130f, 410f, 790f, 690f),
        ))
        repeat(3) { analyze(case, 1019, 765) }
        val hashes = mutableSetOf<String>()
        val samples = List(7) {
            measureNanoTime { hashes += hash(analyze(case, 1019, 765).textEraseMask) } / 1_000_000.0
        }.sorted()
        assertEquals(1, hashes.size)
        assertEquals("cbd37af0e1af4f7fc0e4407ccd815fd33b94b4b3018061b2c3bf8e0fa8f3c181", hashes.single())
        println("MASK_BENCH medianMs=${samples[samples.size / 2]} samples=$samples hash=${hashes.single()}")
    }

    private fun analyze(case: Case, width: Int, height: Int): MangaMaskDebugAnalyzer.Analysis {
        val random = Random(773)
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x % 23 in 5..12 && y % 29 in 6..24) || (x % 23 in 5..18 && y % 29 in 6..10)) {
                case.foreground
            } else if (case.noise) {
                val gray = random.nextInt(40, 236)
                0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
            } else case.background
        }
        val support = MangaMaskDebugAnalyzer.rasterizePolygons(width, height, case.polygons)
        val probability = BooleanArray(source.size) { index ->
            !case.emptyProbability && support[index] && index % 7 != 0
        }
        return MangaMaskDebugAnalyzer.analyze(width, height, source, probability, case.polygons, emptyList())
    }

    private fun hash(mask: BooleanArray): String = MessageDigest.getInstance("SHA-256")
        .digest(ByteArray(mask.size) { if (mask[it]) 1 else 0 })
        .joinToString("") { "%02x".format(it) }

    companion object {
        // Captured from the unmodified implementation, before changing traversal or statistics.
        private val GOLDEN_HASHES = mapOf(
            "dark-on-white" to "26d10d3bb1467c285cf6f0a8425c98f397aed33bde60fbd7228b01808d5c14a7",
            "white-on-dark" to "26d10d3bb1467c285cf6f0a8425c98f397aed33bde60fbd7228b01808d5c14a7",
            "low-contrast" to "74f6dee08c2eb737d8ed13922800233ccb52b5a864f6a691c41239ad79ede41e",
            "busy-background" to "74f6dee08c2eb737d8ed13922800233ccb52b5a864f6a691c41239ad79ede41e",
            "empty-probability" to "8593334d77736d9c2ac941780b3c1602a0b395f5bef40dd7841cba8e35b4ccf6",
            "no-polygons" to "8593334d77736d9c2ac941780b3c1602a0b395f5bef40dd7841cba8e35b4ccf6",
            "overlapping" to "074d8c2be5af9db7c30f25281584e48fd227a02cd0326b9b047b7f35ff966519",
            "clipped" to "a76ee342a31c536dc3ad13e53320432d50395c9496f7a2f4d9854e8221cfd370",
            "slanted" to "fd27d3b9318061d7bcf746ddc9b6025ab26f1ca88c4076b8021a175f2d7a8284",
            "tiny" to "d99e738e1d656e9f7de7971c6a3b1838d8da47ce568b327264669c3ef5507347",
        )
        private fun rect(l: Float, t: Float, r: Float, b: Float) = MangaMaskDebugAnalyzer.Polygon(listOf(
            MangaMaskDebugAnalyzer.Point(l, t), MangaMaskDebugAnalyzer.Point(r, t),
            MangaMaskDebugAnalyzer.Point(r, b), MangaMaskDebugAnalyzer.Point(l, b),
        ))
    }
}
