package com.gameocr.app.asr

import com.gameocr.app.network.DebugHttpLogSanitizer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Retain the server's explanation without logging request credentials or audio payloads. */
internal object CloudSpeechErrorDiagnostic {
    fun describe(code: String, message: String, apiKey: String, vararg otherSecrets: String): String {
        val raw = buildJsonObject {
            put("code", code)
            put("message", message)
        }.toString()
        val withoutKey = (listOf(apiKey) + otherSecrets).filter { it.isNotBlank() }.sortedByDescending { it.length }
            .fold(raw) { text, secret -> text.replace(secret, "[REDACTED]") }
        return DebugHttpLogSanitizer.payload(withoutKey)
            .replace(Regex("(?i)sk-[a-z0-9_-]+"), "[REDACTED]")
            .replace(Regex("(?i)Bearer\\s+[^\\s\\\"]+"), "Bearer [REDACTED]")
            .take(1024)
    }
}
