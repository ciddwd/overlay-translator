package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecretCodecTest {

    private val codec = SettingsSecretCodec(FakeCipher())

    @Test
    fun encryptPlainText_wrapsNonBlankValuesWithEncryptedPrefix() {
        data class Case(val label: String, val plainText: String)

        val cases = listOf(
            Case("api key", "sk-test-123"),
            Case("endpoint", "https://internal.example/v1/"),
            Case("prompt", "translate {source} to {target}\nkeep names")
        )

        cases.forEach { case ->
            val stored = codec.encryptPlainText(case.plainText)

            assertTrue(case.label, stored.startsWith(SettingsSecretCodec.PREFIX))
            assertFalse(case.label, stored.contains(case.plainText))
            assertEquals(case.label, case.plainText, codec.decodeStored(stored))
        }
    }

    @Test
    fun encryptPlainText_keepsBlankValueBlank() {
        assertEquals("", codec.encryptPlainText(""))
    }

    @Test
    fun encryptPlainText_fallsBackToPlainTextWhenCipherIsUnavailable() {
        val unavailableCodec = SettingsSecretCodec(UnavailableCipher())

        val stored = unavailableCodec.encryptPlainText("still-saveable")

        assertEquals("still-saveable", stored)
        assertFalse(unavailableCodec.isEncrypted(stored))
    }

    @Test
    fun decodeStored_supportsLegacyPlainTextAndBadCiphertext() {
        data class Case(val label: String, val stored: String, val expected: String?)

        val cases = listOf(
            Case("legacy plaintext", "plain-secret", "plain-secret"),
            Case("blank legacy value", "", ""),
            Case("bad encrypted value", SettingsSecretCodec.PREFIX + "bad", null)
        )

        cases.forEach { case ->
            assertEquals(case.label, case.expected, codec.decodeStored(case.stored))
        }
    }

    @Test
    fun needsMigration_onlyFlagsNonBlankLegacyPlainText() {
        data class Case(val label: String, val stored: String?, val expected: Boolean)

        val encrypted = codec.encryptPlainText("already-secure")
        val cases = listOf(
            Case("missing", null, false),
            Case("blank", "", false),
            Case("legacy plaintext", "plain-secret", true),
            Case("encrypted", encrypted, false)
        )

        cases.forEach { case ->
            assertEquals(case.label, case.expected, codec.needsMigration(case.stored))
        }
    }

    private class FakeCipher : SettingsSecretCipher {
        override fun encrypt(plainText: String): String =
            plainText.reversed()

        override fun decrypt(cipherText: String): String {
            if (cipherText == "bad") error("bad ciphertext")
            return cipherText.reversed()
        }
    }

    private class UnavailableCipher : SettingsSecretCipher {
        override fun encrypt(plainText: String): String =
            error("cipher unavailable")

        override fun decrypt(cipherText: String): String =
            error("cipher unavailable")
    }
}
