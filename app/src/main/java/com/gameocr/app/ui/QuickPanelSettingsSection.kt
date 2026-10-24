package com.gameocr.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.gameocr.app.R
import com.gameocr.app.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The ViewModel owns pending writes, just as it does for the floating-ball settings. */
internal class QuickPanelSettingsSaver(private val scope: CoroutineScope, private val write: suspend (Boolean) -> Unit) {
    private val mutex = Mutex()
    fun save(enabled: Boolean): Deferred<Boolean> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock { write(enabled); enabled }
    }
}

@Composable
internal fun QuickPanelSettingsControl(
    settings: Settings?,
    viewModel: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    onSaved: (Boolean) -> Unit,
) {
    var enabled by remember(settings?.quickPanelEnabled) { mutableStateOf(settings?.quickPanelEnabled ?: false) }
    var revision by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val onChange: (Boolean) -> Unit = { next ->
        if (next != enabled && settings != null) {
            val previous = enabled
            val request = ++revision
            enabled = next
            // Update only this field in the baseline, preserving unrelated unsaved drafts.
            onSaved(next)
            val save = viewModel.saveQuickPanelEnabled(next)
            scope.launch {
                try { save.await() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (request == revision) { enabled = previous; onSaved(previous) }
                    snackbarHostState.showSnackbar(error.localizedMessage ?: error.javaClass.simpleName)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_quick_panel_enabled))
        SettingsChoiceRow(Modifier.fillMaxWidth()) {
            listOf(false, true).forEach { next ->
                SettingsChoiceButton(selected = enabled == next, enabled = settings != null,
                    onClick = { onChange(next) }, label = {
                        Text(stringResource(if (next) R.string.settings_quick_panel_title else R.string.settings_section_arc_menu))
                    })
            }
        }
    }
}
