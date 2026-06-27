package com.gameocr.app.util

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云 API v3 (TC3-HMAC-SHA256) 通用签名工具。
 *
 * 适用于所有腾讯云服务（OCR / TMT 翻译 / NLP / ...）：调用方提供 secret / service / host /
 * region / action / version / payload，本工具计算签名并返回完整 HTTP 头 map（含 Authorization /
 * X-TC-* / Host / Content-Type）。调用方拿到 headers 后自行构造 OkHttp Request 并发送、解析。
 *
 * 抽出动机：OCR 与翻译共用一套腾讯云签名，分散在两处难维护。
 */
internal object TencentSigner {

    /**
     * 生成 TC3-HMAC-SHA256 签名所需的全套请求头。
     *
     * @param secretId 腾讯云 SecretId
     * @param secretKey 腾讯云 SecretKey
     * @param service 服务名（如 "ocr" / "tmt"）
     * @param host 服务域名（如 "ocr.tencentcloudapi.com" / "tmt.tencentcloudapi.com"）
     * @param region 地域（如 "ap-guangzhou"）
     * @param action 接口名（如 "GeneralBasicOCR" / "TextTranslateBatch"）
     * @param version API 版本（如 "2018-11-19"）
     * @param payload 已序列化的 JSON body 字符串
     * @return 包含 Authorization / Content-Type / Host / X-TC-Action / X-TC-Timestamp /
     *         X-TC-Version / X-TC-Region 的 header map，可直接喂给 Request.Builder。
     */
    fun signTc3(
        secretId: String,
        secretKey: String,
        service: String,
        host: String,
        region: String,
        action: String,
        version: String,
        payload: String
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis() / 1000L
        val date = utcDate(timestamp)
        val credentialScope = "$date/$service/tc3_request"

        // 1. Canonical Request
        val payloadHash = sha256Hex(payload)
        val canonicalHeaders =
            "content-type:application/json; charset=utf-8\nhost:$host\nx-tc-action:${action.lowercase()}\n"
        val signedHeaders = "content-type;host;x-tc-action"
        val canonicalRequest = listOf(
            "POST",
            "/",
            "",
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        // 2. String to Sign
        val stringToSign = listOf(
            "TC3-HMAC-SHA256",
            timestamp.toString(),
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        // 3. Signature
        val secretDate = hmacSha256(("TC3$secretKey").toByteArray(), date)
        val secretService = hmacSha256(secretDate, service)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = bytesToHex(hmacSha256(secretSigning, stringToSign))

        val authorization =
            "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return mapOf(
            "Authorization" to authorization,
            "Content-Type" to "application/json; charset=utf-8",
            "Host" to host,
            "X-TC-Action" to action,
            "X-TC-Timestamp" to timestamp.toString(),
            "X-TC-Version" to version,
            "X-TC-Region" to region
        )
    }

    private fun utcDate(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(epochSeconds * 1000))
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
