package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class CloudSpeechFailurePolicyTest {
    @Test fun tencentAccountFailuresAreDistinctAndNeverRetried_tableDriven() {
        mapOf(4000 to CloudAsrFailure.RATE_LIMIT, 4001 to CloudAsrFailure.CONFIGURATION,
            4002 to CloudAsrFailure.AUTH, 4003 to CloudAsrFailure.NOT_ACTIVATED,
            4004 to CloudAsrFailure.QUOTA, 4005 to CloudAsrFailure.OVERDUE,
            4006 to CloudAsrFailure.RATE_LIMIT, 4007 to CloudAsrFailure.AUDIO,
            4010 to CloudAsrFailure.CONFIGURATION, 6001 to CloudAsrFailure.REGION,
            4999 to CloudAsrFailure.UNKNOWN).forEach { (code, expected) ->
            val actual = CloudSpeechFailurePolicy.native(AsrCloudProvider.TENCENT, code)
            assertEquals(code.toString(), expected, actual)
            assertFalse(code.toString(), actual.retryable)
        }
        listOf(4008, 4009, 5000, 5001, 5002).forEach {
            assertTrue(CloudSpeechFailurePolicy.native(AsrCloudProvider.TENCENT, it).retryable)
        }
        assertEquals(CloudAsrFailure.entries.size, CloudAsrFailure.entries.map(CloudSpeechFailurePolicy::message).distinct().size)
    }

    @Test fun providerCodeSpacesAndHttpStatusSpacesStaySeparate_tableDriven() {
        mapOf(45000081 to CloudAsrFailure.CONNECTION, 45000001 to CloudAsrFailure.CONFIGURATION,
            45000002 to CloudAsrFailure.AUDIO, 45000151 to CloudAsrFailure.AUDIO,
            55000031 to CloudAsrFailure.SERVICE, 4004 to CloudAsrFailure.UNKNOWN).forEach { (code, expected) ->
            assertEquals(expected, CloudSpeechFailurePolicy.native(AsrCloudProvider.VOLCENGINE, code))
        }
        mapOf(401 to CloudAsrFailure.AUTH, 403 to CloudAsrFailure.AUTH, 402 to CloudAsrFailure.QUOTA,
            429 to CloudAsrFailure.RATE_LIMIT, 400 to CloudAsrFailure.CONFIGURATION,
            408 to CloudAsrFailure.CONNECTION, 503 to CloudAsrFailure.SERVICE,
            499 to CloudAsrFailure.UNKNOWN).forEach { (code, expected) ->
            assertEquals(expected, CloudSpeechFailurePolicy.http(code))
        }
        assertEquals(CloudAsrFailure.CONNECTION, CloudSpeechFailurePolicy.http(null))
        mapOf("InvalidApiKey" to CloudAsrFailure.AUTH, "Arrearage" to CloudAsrFailure.OVERDUE,
            "CommodityNotPurchased" to CloudAsrFailure.NOT_ACTIVATED,
            "Throttling.AllocationQuota" to CloudAsrFailure.RATE_LIMIT,
            "InternalError.Algo" to CloudAsrFailure.SERVICE, "new_error" to CloudAsrFailure.UNKNOWN).forEach { (code, expected) ->
            assertEquals(expected, CloudSpeechFailurePolicy.aliyun(code))
        }
    }

    @Test fun volcAudioSequenceAndPayloadPreserveEveryByteAndTerminateWithNegativeSequence() {
        val pcm = ByteArray(6400) { (it % 253).toByte() }
        listOf(false, true).forEach { last ->
            val frame = VolcSpeechProtocol.audio(pcm.toByteString(), last, sequence = 3).toByteArray()
            assertEquals(if (last) 0x23 else 0x21, frame[1].toInt())
            assertEquals(if (last) -3 else 3, ByteBuffer.wrap(frame, 4, 4).int)
            assertEquals(frame.size - 12, ByteBuffer.wrap(frame, 8, 4).int)
            val decoded = GZIPInputStream(ByteArrayInputStream(frame, 12, frame.size - 12)).readBytes()
            assertArrayEquals(pcm, decoded)
        }
        val end = VolcSpeechProtocol.audio(ByteString.EMPTY, last = true, sequence = 4).toByteArray()
        assertEquals(-4, ByteBuffer.wrap(end, 4, 4).int)
        assertTrue(runCatching { VolcSpeechProtocol.configuration(0) }.isFailure)
    }

    @Test fun nativeErrorsRetainBoundedDiagnosticDetailsWithoutLeakingCredentials_tableDriven() {
        listOf(false, true).forEach { gzip ->
            val raw = "audio packet timeout id=secret-id key=secret-key".toByteArray()
            val payload = if (gzip) ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray() else raw
            val frame = ByteBuffer.allocate(12 + payload.size).apply {
                put(byteArrayOf(0x11, 0xf0.toByte(), if (gzip) 0x01 else 0x00, 0))
                putInt(45000081); putInt(payload.size); put(payload)
            }.array().toByteString()
            val parsed = VolcSpeechProtocol.parse(frame)
            assertEquals(45000081, parsed.error)
            assertEquals(raw.decodeToString(), parsed.errorMessage)
            val detail = CloudSpeechErrorDiagnostic.describe(parsed.error.toString(), parsed.errorMessage.orEmpty(), "secret-key", "secret-id")
            assertTrue(detail.contains("45000081"))
            assertFalse(detail.contains("secret-id")); assertFalse(detail.contains("secret-key"))
            val truncated = frame.substring(0, frame.size - 1)
            assertTrue(runCatching { VolcSpeechProtocol.parse(truncated) }.isFailure)
        }
        val parsed = TencentSpeechProtocol.parse("""{"code":4004,"message":"resource exhausted"}""")
        assertEquals("resource exhausted", parsed.errorMessage)
    }
}
