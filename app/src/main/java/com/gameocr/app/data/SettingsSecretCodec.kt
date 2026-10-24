package com.gameocr.app.data

/** Contains no field values or provider exception messages. */
class SettingsSecretStorageException : java.io.IOException(
    "Could not save settings. Your previous data has been kept. Please try again.",
)

class SettingsSecretCodec(
    private val cipher: SettingsSecretCipher
) {
    /** Credentials must never fall back to plaintext or device-derived encryption. */
    fun encryptCredential(value: String): String {
        if (value.isEmpty()) return ""
        return encrypt(value)
    }

    private fun encrypt(value: String): String {
        return try {
            val encrypted = cipher.encrypt(value)
            require(encrypted.isNotEmpty() && encrypted != value)
            PREFIX + encrypted
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw SettingsSecretStorageException()
        }
    }

    // Also used for non-secret addresses, prompts and credential-free preset metadata.
    fun encryptPlainText(value: String): String {
        if (value.isEmpty()) return ""
        return encrypt(value)
    }

    fun decodeStored(value: String): String? {
        if (!isEncrypted(value)) return value
        val payload = value.removePrefix(PREFIX)
        return try { cipher.decrypt(payload) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun decodeForWrite(value: String): String =
        decodeStored(value) ?: throw SettingsSecretStorageException()

    fun needsMigration(value: String?): Boolean =
        !value.isNullOrEmpty() && !isEncrypted(value)

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    companion object {
        const val PREFIX = "enc:v1:"
    }
}
