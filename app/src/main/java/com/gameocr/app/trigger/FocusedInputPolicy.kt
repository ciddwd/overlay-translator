package com.gameocr.app.trigger

internal inline fun readNonPasswordInputText(password: Boolean, readText: () -> String): String =
    if (password) "" else readText()

data class FocusedInputDescriptor(
    val packageName: String,
    val windowId: Int,
    val viewId: String?,
    val className: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val focused: Boolean,
    val editable: Boolean,
    val supportsSetText: Boolean,
    val password: Boolean,
)

enum class FocusedInputEligibility {
    ELIGIBLE,
    NOT_FOCUSED,
    PASSWORD,
    NOT_EDITABLE,
    EMPTY,
}

object FocusedInputPolicy {
    fun replacementMatches(
        original: FocusedInputDescriptor,
        current: FocusedInputDescriptor,
        expected: String,
    ): Boolean {
        if (current.password || !current.focused || current.text != expected) return false
        // A multiline editor may grow after replacement. Keep the pre-write check strict.
        val comparable = if (original.viewId.isNullOrBlank() && current.viewId.isNullOrBlank()) {
            current.copy(bottom = original.bottom)
        } else current
        return sameTarget(original, comparable)
    }

    fun eligibility(candidate: FocusedInputDescriptor): FocusedInputEligibility = when {
        !candidate.focused -> FocusedInputEligibility.NOT_FOCUSED
        candidate.password -> FocusedInputEligibility.PASSWORD
        !candidate.editable && !candidate.supportsSetText ->
            FocusedInputEligibility.NOT_EDITABLE
        candidate.text.isBlank() -> FocusedInputEligibility.EMPTY
        else -> FocusedInputEligibility.ELIGIBLE
    }

    fun sameTarget(
        original: FocusedInputDescriptor,
        current: FocusedInputDescriptor,
    ): Boolean {
        if (original.packageName != current.packageName ||
            original.windowId != current.windowId
        ) {
            return false
        }
        val originalViewId = original.viewId?.takeIf(String::isNotBlank)
        val currentViewId = current.viewId?.takeIf(String::isNotBlank)
        return if (originalViewId != null || currentViewId != null) {
            originalViewId != null && originalViewId == currentViewId
        } else {
            original.className == current.className &&
                original.left == current.left &&
                original.top == current.top &&
                original.right == current.right &&
                original.bottom == current.bottom
        }
    }

    fun unchanged(
        original: FocusedInputDescriptor,
        current: FocusedInputDescriptor,
    ): Boolean = sameTarget(original, current) && original.text == current.text
}
