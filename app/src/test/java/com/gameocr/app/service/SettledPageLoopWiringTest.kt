package com.gameocr.app.service

import com.gameocr.app.capture.ShizukuCaptureRoute
import com.gameocr.app.capture.resolveShizukuCaptureRoute
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettledPageLoopWiringTest {

    @Test
    fun settledPageMode_isWiredFromSettingsToIndependentObservationAndInvalidation() {
        data class Marker(val name: String, val source: String, val expected: String)

        val cases = listOf(
            Marker("persisted enum", source("data/Settings.kt"), "SETTLED_PAGE"),
            Marker("settings chip", source("ui/SettingsScreen.kt"), "LoopTriggerMode.SETTLED_PAGE"),
            Marker("Chinese label", resource("values-zh-rCN/strings.xml"), ">停稳翻译</string>"),
            Marker("English label", resource("values/strings.xml"), ">Translate when settled</string>"),
            Marker("independent observer", source("service/CaptureService.kt"), "observeSettledPage()"),
            Marker("latest page policy", source("service/CaptureService.kt"), "settledPagePolicy.observe("),
            Marker("old work invalidation", source("service/CaptureService.kt"), "invalidateSettledPageWork("),
            Marker("old translation cancellation", source("service/CaptureService.kt"), "cancelActiveTranslationBatch(\"settledPage:${'$'}reason\")"),
            Marker("masked observation", source("service/CaptureService.kt"), "LoopFrameFingerprintFactory.createObservation("),
            Marker("late OCR rejection", source("service/CaptureService.kt"), "ensureCurrentSettledPage(settledPageRevision"),
            Marker("read-only overlay bounds", source("overlay/OverlayManager.kt"), "observationExclusionRects()"),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.source.contains(case.expected))
        }
        val observer = source("service/CaptureService.kt").substringAfter("private suspend fun observeSettledPage()")
            .substringBefore("private fun selectLoopTextRoi(")
        assertFalse("polling must not flash windows", observer.contains("setHiddenForCapture"))
        assertFalse("polling must not flash translated text", observer.contains("setTranslationResultsHiddenForCapture"))
        assertFalse("observation screenshot contains our UI, never hand it to OCR", observer.contains("preparedFullScreen ="))
    }

    @Test
    fun ShizukuObservation_isPacedAndRawCaptureAvoidsTheGrowingBufferPath() {
        val screenshotter = source("capture/ShizukuScreenshotter.kt")
        val rawPath = screenshotter.substringAfter("private fun executeRawScreencap()")
            .substringBefore("private fun executeScreencap(")

        assertTrue(screenshotter.contains("minimumLoopObservationIntervalMs"))
        assertTrue(screenshotter.contains("MIN_LOOP_OBSERVATION_INTERVAL_MS = 350L"))
        assertTrue(screenshotter.contains("accessibilityFastPath.capture()"))
        assertTrue(rawPath.contains("ByteArray(currentFrameBytes)"))
        assertFalse(rawPath.contains("ByteArrayOutputStream"))
        assertFalse(rawPath.contains("toByteArray()"))
    }

    @Test
    fun ShizukuCaptureRoute_prefersDirectAccessibilityAndFallsBackToShell() {
        data class Case(
            val accessibilityReady: Boolean,
            val shizukuReady: Boolean,
            val expected: ShizukuCaptureRoute,
        )

        listOf(
            Case(accessibilityReady = true, shizukuReady = true, expected = ShizukuCaptureRoute.ACCESSIBILITY),
            Case(accessibilityReady = true, shizukuReady = false, expected = ShizukuCaptureRoute.ACCESSIBILITY),
            Case(accessibilityReady = false, shizukuReady = true, expected = ShizukuCaptureRoute.SHELL),
            Case(accessibilityReady = false, shizukuReady = false, expected = ShizukuCaptureRoute.UNAVAILABLE),
        ).forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                resolveShizukuCaptureRoute(case.accessibilityReady, case.shizukuReady),
            )
        }
    }

    private fun source(relative: String): String =
        File("src/main/java/com/gameocr/app/$relative").readText()

    private fun resource(relative: String): String =
        File("src/main/res/$relative").readText()
}
