package com.gameocr.app.overlay

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.gameocr.app.R

internal fun TextView.enableSelectionSpeech(
    label: String,
    isEnabled: () -> Boolean = { true },
    onSpeak: (String) -> Unit,
) {
    customSelectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, R.id.action_speak_selected_text, 100, label).apply {
                setIcon(R.drawable.ic_volume_up)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                isVisible = isEnabled() && selectedText() != null
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(R.id.action_speak_selected_text)?.isVisible =
                isEnabled() && selectedText() != null
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != R.id.action_speak_selected_text) return false
            val selected = selectedText().takeIf { isEnabled() } ?: return false
            onSpeak(selected)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
}

private fun TextView.selectedText(): String? =
    selectedTextForSpeech(text, selectionStart, selectionEnd)
