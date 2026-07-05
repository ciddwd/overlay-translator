package com.gameocr.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsSecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

@Singleton
class AndroidKeystoreSettingsSecretCipher @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsSecretCipher {

    override fun encrypt(plainText: String): String =
        runCatching {
            KEYSTORE_PREFIX + encryptWithKey(getOrCreateKey(), plainText)
        }.getOrElse {
            FALLBACK_PREFIX + encryptWithKey(fallbackKey, plainText)
        }

    override fun decrypt(cipherText: String): String =
        when {
            cipherText.startsWith(KEYSTORE_PREFIX) ->
                decryptWithKey(getOrCreateKey(), cipherText.removePrefix(KEYSTORE_PREFIX))
            cipherText.startsWith(FALLBACK_PREFIX) ->
                decryptWithKey(fallbackKey, cipherText.removePrefix(FALLBACK_PREFIX))
            else ->
                decryptWithKey(getOrCreateKey(), cipherText)
        }

    private fun encryptWithKey(key: SecretKey, plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = requireNotNull(cipher.iv) { "AES-GCM encryption did not provide an IV" }
        val payload = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
        return Base64.getEncoder().encodeToString(payload)
    }

    private fun decryptWithKey(key: SecretKey, cipherText: String): String {
        val payload = Base64.getDecoder().decode(cipherText)
        require(payload.size > 1) { "Encrypted settings payload is empty" }
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize == IV_BYTES && payload.size > 1 + ivSize) {
            "Encrypted settings payload has an invalid IV"
        }
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private val fallbackKey: SecretKey by lazy {
        val androidId = AndroidSettings.Secure.getString(
            context.contentResolver,
            AndroidSettings.Secure.ANDROID_ID
        ).orEmpty()
        val material = listOf(
            FALLBACK_KEY_PURPOSE,
            context.packageName,
            androidId,
            signingCertificateDigest()
        ).joinToString(separator = "\u001f")
        SecretKeySpec(sha256(material.toByteArray(Charsets.UTF_8)), KeyProperties.KEY_ALGORITHM_AES)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun signingCertificateDigest(): String = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = info.signingInfo
            if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures
        }
        signatures.orEmpty()
            .map(Signature::toByteArray)
            .joinToString("|") { bytes -> Base64.getEncoder().encodeToString(sha256(bytes)) }
    }.getOrDefault("unknown-signature")

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "game_ocr_settings_secret_aes_gcm_v1"
        const val KEYSTORE_PREFIX = "ak:"
        const val FALLBACK_PREFIX = "fb:"
        const val FALLBACK_KEY_PURPOSE = "game_ocr_settings_fallback_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
