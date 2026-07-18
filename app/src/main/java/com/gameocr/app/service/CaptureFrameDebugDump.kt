package com.gameocr.app.service

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CaptureFrameDebugDumpPolicy {
    const val MAX_FRAMES_PER_PROCESS: Int = 8

    fun isEnabled(
        developerOptionsEnabled: Boolean,
        screenshotSavingEnabled: Boolean,
    ): Boolean = developerOptionsEnabled && screenshotSavingEnabled

    fun shouldDumpFrame(
        developerOptionsEnabled: Boolean,
        screenshotSavingEnabled: Boolean,
        frameIndex: Int,
    ): Boolean = isEnabled(developerOptionsEnabled, screenshotSavingEnabled) &&
        frameIndex in 1..MAX_FRAMES_PER_PROCESS

    fun fileName(diagId: Long, label: String, width: Int, height: Int): String {
        val safeLabel = label
            .lowercase(Locale.US)
            .map { ch -> if (ch.isLetterOrDigit()) ch else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "frame" }
        return "capture-${diagId}-${safeLabel}-${width}x${height}.png"
    }
}

private val dumpedFrameCount = AtomicInteger(0)

internal suspend fun dumpCaptureFrameForDebug(
    context: Context,
    diagId: Long,
    label: String,
    developerOptionsEnabled: Boolean,
    screenshotSavingEnabled: Boolean,
    bitmap: Bitmap
): File? {
    if (!CaptureFrameDebugDumpPolicy.isEnabled(developerOptionsEnabled, screenshotSavingEnabled)) {
        return null
    }
    val frameIndex = dumpedFrameCount.incrementAndGet()
    if (
        !CaptureFrameDebugDumpPolicy.shouldDumpFrame(
            developerOptionsEnabled,
            screenshotSavingEnabled,
            frameIndex,
        )
    ) {
        return null
    }
    return withContext(Dispatchers.IO) {
        val dir = (context.getExternalFilesDir("capture_frames") ?: File(context.filesDir, "capture_frames"))
            .apply { mkdirs() }
        val file = File(
            dir,
            CaptureFrameDebugDumpPolicy.fileName(diagId, label, bitmap.width, bitmap.height)
        )
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file
    }
}
