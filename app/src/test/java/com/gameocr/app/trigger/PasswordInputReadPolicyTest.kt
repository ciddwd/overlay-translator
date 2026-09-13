package com.gameocr.app.trigger

import org.junit.Assert.*
import org.junit.Test

class PasswordInputReadPolicyTest {
    @Test fun passwordTextSupplierIsNeverInvoked() {
        for (password in listOf(false, true)) {
            var reads = 0
            val text = readNonPasswordInputText(password) { reads++; "input" }
            assertEquals(if (password) 0 else 1, reads)
            assertEquals(if (password) "" else "input", text)
        }
    }
}
