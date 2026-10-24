package com.gameocr.app.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CaptureSetupHistoryTest {
    @Test fun completedUsersFromOlderVersionsNeverBecomeFirstTimeUsersOnUpgrade() {
        val store = Store(mutableMapOf("completed_v1" to true))
        assertTrue(OnboardingPrefs.isCompleted(store.context()))
        assertFalse(OnboardingPrefs.isCaptureSetupPending(store.context()))
        // Opening and completing help again must not restart capture setup either.
        OnboardingPrefs.markCompleted(store.context())
        assertFalse(OnboardingPrefs.isCaptureSetupPending(store.context()))
        assertTrue(OnboardingPrefs.isCompleted(store.context()))
    }

    @Test fun newUsersKeepFirstSetupAcrossOnboardingAndReopeningUntilTheirFirstStart() {
        val store = Store()
        assertFalse(OnboardingPrefs.isCompleted(store.context()))
        assertTrue(OnboardingPrefs.isCaptureSetupPending(store.context()))
        OnboardingPrefs.markCompleted(store.context())
        assertTrue(OnboardingPrefs.isCompleted(store.context()))
        assertTrue(OnboardingPrefs.isCaptureSetupPending(store.context()))
        OnboardingPrefs.markCaptureSetupCompleted(store.context())
        assertFalse(OnboardingPrefs.isCaptureSetupPending(store.context()))
        OnboardingPrefs.markCompleted(store.context())
        assertFalse(OnboardingPrefs.isCaptureSetupPending(store.context()))
    }

    @Test fun explicitFirstSetupHistoryIsIndependentOfOtherOnboardingState() {
        for (completed in listOf(false, true)) for (pending in listOf(false, true)) {
            val store = Store(mutableMapOf("completed_v1" to completed, "capture_setup_pending_v1" to pending))
            assertEquals("completed=$completed pending=$pending", pending,
                OnboardingPrefs.isCaptureSetupPending(store.context()))
            OnboardingPrefs.markCompleted(store.context())
            assertEquals(pending, OnboardingPrefs.isCaptureSetupPending(store.context()))
        }
    }

    /** SharedPreferences contract with independent Contexts, without device state or permissions. */
    private class Store(private val values: MutableMap<String, Boolean> = mutableMapOf()) {
        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Boolean>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> proxy.also { pending[args!![0] as String] = args[1] as Boolean }
                    "apply" -> { values.putAll(pending); null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }

        fun context(): Context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                assertEquals("onboarding", name)
                return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                    arrayOf(SharedPreferences::class.java)) { _, method, args ->
                    when (method.name) {
                        "getBoolean" -> values[args!![0] as String] ?: args[1]
                        "edit" -> editor()
                        else -> error("Unexpected preference call: ${method.name}")
                    }
                } as SharedPreferences
            }
        }
    }
}
