package com.gameocr.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gameocr.app.R

internal enum class FloatingSettingsPage(val titleRes: Int) {
    DOCKING(R.string.settings_search_item_floating_snap),
    MENU(R.string.settings_section_arc_menu),
    WAKE(R.string.gesture_wake_title),
    BINDINGS(R.string.gesture_binding_title),
}

internal fun floatingSettingsPageForTarget(target: Int, quickPanelEnabled: Boolean = false): FloatingSettingsPage? {
    val page = when (target) {
        R.string.settings_search_item_floating_snap,
        R.string.settings_search_item_floating_auto_dock,
        R.string.settings_search_item_floating_dock_inset -> FloatingSettingsPage.DOCKING
        R.string.settings_search_item_arc_menu_order -> FloatingSettingsPage.MENU
        R.string.gesture_mode, R.string.gesture_wake_title -> FloatingSettingsPage.WAKE
        R.string.gesture_binding_title -> FloatingSettingsPage.BINDINGS
        else -> null
    }
    return page?.takeIf { canOpenFloatingSettingsPage(it, quickPanelEnabled) }
}

internal fun canOpenFloatingSettingsPage(page: FloatingSettingsPage, quickPanelEnabled: Boolean): Boolean =
    page != FloatingSettingsPage.MENU || !quickPanelEnabled

@Composable
internal fun FloatingSettingsOverview(
    appearance: @Composable () -> Unit,
    menuMode: @Composable () -> Unit,
    dockSummary: String,
    quickPanelEnabled: Boolean,
    onOpen: (FloatingSettingsPage) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_floating)) {
        appearance()
        menuMode()
        FloatingSettingsLink(FloatingSettingsPage.DOCKING, dockSummary, onOpen)
        FloatingSettingsLink(FloatingSettingsPage.MENU, null, onOpen, enabled = !quickPanelEnabled)
    }
}

@Composable
internal fun GestureSettingsOverview(
    globalGesturesEnabled: Boolean,
    onGlobalGesturesChange: (Boolean) -> Unit,
    onOpen: (FloatingSettingsPage) -> Unit,
) {
    SectionCard(title = stringResource(R.string.gesture_motion_title)) {
        SwitchRow(
            label = stringResource(R.string.gesture_global_enabled),
            checked = globalGesturesEnabled,
            helpText = stringResource(R.string.gesture_global_hint),
            onChange = onGlobalGesturesChange,
        )
        FloatingSettingsLink(FloatingSettingsPage.WAKE, null, onOpen)
        FloatingSettingsLink(FloatingSettingsPage.BINDINGS, null, onOpen)
    }
}

@Composable
private fun FloatingSettingsLink(
    page: FloatingSettingsPage,
    summary: String?,
    onOpen: (FloatingSettingsPage) -> Unit,
    enabled: Boolean = true,
) {
    SettingsLinkCell(
        label = stringResource(page.titleRes),
        status = summary,
        enabled = enabled,
        onClick = { onOpen(page) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FloatingSettingsDetail(
    page: FloatingSettingsPage,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(page.titleRes)) }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}
