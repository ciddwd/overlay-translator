package com.gameocr.app.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedInputPolicyTest {

    @Test
    fun eligibility_isTableDriven() {
        data class Case(
            val name: String,
            val candidate: FocusedInputDescriptor,
            val expected: FocusedInputEligibility,
        )
        val eligible = descriptor()
        listOf(
            Case("eligible", eligible, FocusedInputEligibility.ELIGIBLE),
            Case("not focused", eligible.copy(focused = false), FocusedInputEligibility.NOT_FOCUSED),
            Case("password", eligible.copy(password = true), FocusedInputEligibility.PASSWORD),
            Case("set text action without editable flag", eligible.copy(editable = false), FocusedInputEligibility.ELIGIBLE),
            Case("editable without set text can use copy fallback", eligible.copy(supportsSetText = false), FocusedInputEligibility.ELIGIBLE),
            Case(
                "neither editable nor replaceable",
                eligible.copy(editable = false, supportsSetText = false),
                FocusedInputEligibility.NOT_EDITABLE,
            ),
            Case("empty", eligible.copy(text = " \n"), FocusedInputEligibility.EMPTY),
        ).forEach { case ->
            assertEquals(case.name, case.expected, FocusedInputPolicy.eligibility(case.candidate))
        }
    }

    @Test
    fun targetIdentityAndUnchangedText_areTableDriven() {
        data class Case(val name: String, val current: FocusedInputDescriptor, val same: Boolean)
        val original = descriptor()
        listOf(
            Case("same", original.copy(), true),
            Case("text changed is same target", original.copy(text = "changed"), true),
            Case("package changed", original.copy(packageName = "other"), false),
            Case("window changed", original.copy(windowId = 9), false),
            Case("view id changed", original.copy(viewId = "other:id/editor"), false),
            Case("missing one view id", original.copy(viewId = null), false),
        ).forEach { case ->
            assertEquals(case.name, case.same, FocusedInputPolicy.sameTarget(original, case.current))
        }
        assertTrue(FocusedInputPolicy.unchanged(original, original.copy()))
        assertFalse(FocusedInputPolicy.unchanged(original, original.copy(text = "changed")))

        val withoutId = original.copy(viewId = null)
        assertTrue(FocusedInputPolicy.sameTarget(withoutId, withoutId.copy()))
        assertFalse(FocusedInputPolicy.sameTarget(withoutId, withoutId.copy(left = 20)))
    }

    @Test fun replacementVerificationIsTableDriven() {
        val original = descriptor().copy(viewId = null)
        val replaced = original.copy(text = "translated")
        listOf(
            replaced to true,
            replaced.copy(bottom = 300) to true,
            replaced.copy(left = 40) to false,
            replaced.copy(windowId = 8) to false,
            replaced.copy(packageName = "other") to false,
            replaced.copy(text = "hello") to false,
            replaced.copy(password = true) to false,
            replaced.copy(focused = false) to false,
        ).forEach { (current, expected) ->
            assertEquals(expected, FocusedInputPolicy.replacementMatches(original, current, "translated"))
        }
        assertFalse(FocusedInputPolicy.unchanged(original, original.copy(bottom = 300)))
    }

    private fun descriptor() = FocusedInputDescriptor(
        packageName = "example.app",
        windowId = 3,
        viewId = "example.app:id/editor",
        className = "android.widget.EditText",
        left = 10,
        top = 20,
        right = 400,
        bottom = 120,
        text = "hello",
        focused = true,
        editable = true,
        supportsSetText = true,
        password = false,
    )
}
