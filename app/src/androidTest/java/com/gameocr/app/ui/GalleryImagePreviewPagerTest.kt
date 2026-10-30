package com.gameocr.app.ui

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gameocr.app.gallery.GalleryImageDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryImagePreviewPagerTest {

    @Test
    fun previewUpdatesWhileResultsGrowFrom19ThenShrink() = checkChangingResults(19)

    @Test
    fun previewUpdatesWhileResultsGrowFrom24ThenShrink() = checkChangingResults(24)

    private fun checkChangingResults(initialCount: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sources = mutableStateOf(previewSources(initialCount))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decoder = GalleryImageDecoder(activity)
                activity.setContent {
                    MaterialTheme {
                        GalleryImagePreviewDialog(
                            sources = sources.value,
                            initialPage = initialCount - 1,
                            imageDecoder = decoder,
                            onDismiss = {},
                        )
                    }
                }
            }
            assertPreviewTitle("preview-${initialCount - 1}")

            // Each update crosses the Activity/Dialog composition boundary, as a
            // completed background translation does while its preview is open.
            for (count in initialCount + 1..initialCount + 8) {
                updateAndAwaitFrames(scenario) { sources.value = previewSources(count) }
                assertPreviewTitle("preview-${initialCount - 1}")
            }

            updateAndAwaitFrames(scenario) { sources.value = previewSources(1) }
            assertPreviewTitle("preview-0")
            updateAndAwaitFrames(scenario) { sources.value = emptyList() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun updateAndAwaitFrames(scenario: ActivityScenario<MainActivity>, update: () -> Unit) {
        val frames = CountDownLatch(1)
        scenario.onActivity { activity ->
            update()
            activity.window.decorView.postOnAnimation {
                activity.window.decorView.postOnAnimation { frames.countDown() }
            }
        }
        assertTrue("Preview update must reach a rendered frame", frames.await(5, TimeUnit.SECONDS))
    }

    private fun assertPreviewTitle(title: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000
        var activePackage: CharSequence? = null
        var observedTexts = emptyList<String>()
        do {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            activePackage = root?.packageName
            observedTexts = visibleTexts(root)
            if (title in observedTexts) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Preview must display $title; active package=$activePackage; texts=$observedTexts", false)
    }

    private fun visibleTexts(node: AccessibilityNodeInfo?): List<String> = buildList {
        if (node == null) return@buildList
        node.text?.toString()?.let(::add)
        for (index in 0 until node.childCount) addAll(visibleTexts(node.getChild(index)))
    }

    private fun previewSources(count: Int): List<GalleryPreviewSource> = List(count) { index ->
        GalleryPreviewSource(
            sourceUri = "content://preview-test/$index",
            localPath = "",
            displayName = "preview-$index",
        )
    }
}
