package com.gameocr.app.data

class SettingsSecretCodec(
    private val cipher: SettingsSecretCipher
) {
    fun encryptPlainText(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching { PREFIX + cipher.encrypt(value) }.getOrElse { value }
    }

    fun decodeStored(value: String): String? {
        if (!isEncrypted(value)) return value
        val payload = value.removePrefix(PREFIX)
        return runCatching { cipher.decrypt(payload) }.getOrNull()
    }

    fun needsMigration(value: String?): Boolean =
        !value.isNullOrEmpty() && !isEncrypted(value)

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    companion object {
        const val PREFIX = "enc:v1:"
    }
}
