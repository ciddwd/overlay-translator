package com.gameocr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.translate.ConnectionBalance
import com.gameocr.app.translate.TestResult
import com.gameocr.app.translate.formatConnectionBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Ephemeral test state belongs to the tested inputs, never to a saved preset. */
@Composable
internal fun TranslatorConnectionTestPanel(
    inputKey: Any,
    onTest: suspend () -> TestResult,
    onModels: (List<String>) -> Unit,
) {
    key(inputKey) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<TestResult?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = !running,
                    onClick = {
                        running = true
                        result = null
                        onModels(emptyList())
                        scope.launch {
                            try {
                                val tested = onTest()
                                // Some engine probes catch exceptions internally. Never publish a
                                // late response after navigation or an input/provider change.
                                ensureActive()
                                result = tested
                                onModels(if (tested.success) tested.models else emptyList())
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                ensureActive()
                                result = TestResult(false, error.javaClass.simpleName)
                            } finally {
                                running = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (running) R.string.settings_test_testing else R.string.settings_test_connection))
                }
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            result?.let { tested ->
                Text(
                    tested.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tested.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                when (val balance = tested.balance) {
                    is ConnectionBalance.Available -> Text(
                        stringResource(
                            R.string.settings_test_balance_format,
                            formatConnectionBalance(balance, LocalConfiguration.current.locales[0]),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is ConnectionBalance.Failed -> Text(
                        stringResource(R.string.settings_test_balance_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> Unit
                }
            }
        }
    }
}

/** The existing Settings model picker, shared with quick setup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslatorModelPicker(models: List<String>, onSelect: (String) -> Unit) {
    if (models.isEmpty()) return
    var expanded by remember(models) { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_test_pick_model)) },
            placeholder = { Text("${models.size} models") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { id ->
                DropdownMenuItem(text = { Text(id) }, onClick = {
                    onSelect(id)
                    expanded = false
                })
            }
        }
    }
}
