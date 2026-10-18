package com.gameocr.app.asr

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * Captures what other apps are playing (API 29+). Output is always 16 kHz mono float.
 *
 * The AudioRecord buffer is about one second and is never drained into an unbounded queue: when
 * recognition falls behind, the platform overwrites old audio and the reader jumps to live audio.
 * Late subtitles are worse than missing ones.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class PlaybackAudioCapture private constructor(
    private val record: AudioRecord,
    val sourceRate: Int,
) {
    private val decimator = if (sourceRate == TARGET_RATE) null else PcmDecimator(sourceRate / TARGET_RATE)
    private val pcm = ShortArray(sourceRate * READ_MS / 1000)

    fun start() {
        decimator?.reset()
        record.startRecording()
    }

    fun stop() {
        runCatching { record.stop() }
    }

    fun release() {
        stop()
        record.release()
    }

    /**
     * Blocks for roughly [READ_MS]. Returns null when the recorder fails or was stopped; the
     * caller ends its read loop before [release] so native memory is never read after release.
     */
    fun read(): FloatArray? {
        val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
        if (count < 0) {
            Timber.w("[asr-capture] AudioRecord.read error=%d", count)
            return null
        }
        val samples = FloatArray(count) { pcm[it] / 32768f }
        return decimator?.process(samples, count) ?: samples
    }

    companion object {
        const val TARGET_RATE = 16_000
        const val READ_MS = 100

        /** Requires a granted RECORD_AUDIO permission; returns null when no format initializes. */
        @SuppressLint("MissingPermission")
        fun create(projection: MediaProjection, excludedUid: Int): PlaybackAudioCapture? {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(excludedUid)
                .build()
            for (rate in listOf(TARGET_RATE, 48_000)) {
                val minBytes = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBytes <= 0) continue
                val record = runCatching {
                    AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(minBytes, rate * 2))
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                }.onFailure { Timber.w(it, "[asr-capture] AudioRecord rate=%d failed", rate) }.getOrNull()
                if (record?.state == AudioRecord.STATE_INITIALIZED) {
                    Timber.i("[asr-capture] AudioRecord ready rate=%d bufferBytes=%d", rate, maxOf(minBytes, rate * 2))
                    return PlaybackAudioCapture(record, rate)
                }
                record?.release()
            }
            return null
        }
    }
}
