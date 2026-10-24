package com.gameocr.app.download

/** Whole-megabyte amounts and a percentage; the percentage is null while the size is unknown. */
internal object ModelDownloadProgressText {
    private const val MB = 1024L * 1024L

    fun megabytes(bytes: Long): Long = (bytes.coerceAtLeast(0L) + MB / 2) / MB

    /** Rounded down so a download never reads 100% before it finishes. */
    fun percent(downloaded: Long, total: Long): Int? =
        if (total > 0) (downloaded.coerceAtLeast(0L) * 100 / total).toInt().coerceIn(0, 100) else null

    /** Bar position; null (animated) only while running with an unknown size. Paused work never animates. */
    fun barFraction(downloaded: Long, total: Long, active: Boolean): Float? =
        if (total > 0) (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else if (active) null else 0f
}
