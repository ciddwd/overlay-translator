package com.gameocr.app.ui

import com.gameocr.app.appcontext.SelectableApp

internal enum class GlossaryScopeMode {
    GLOBAL,
    CURRENT_APP,
    SELECTED_APP,
}

internal data class GlossaryScopeSelection(
    val mode: GlossaryScopeMode,
    val selectedApp: SelectableApp? = null,
)

internal object GlossaryScopePolicy {
    fun initialSelection(
        scopePackage: String,
        appLabel: String,
        currentApp: SelectableApp?,
    ): GlossaryScopeSelection {
        if (scopePackage.isBlank()) {
            return GlossaryScopeSelection(GlossaryScopeMode.GLOBAL)
        }
        if (currentApp?.packageName == scopePackage) {
            return GlossaryScopeSelection(GlossaryScopeMode.CURRENT_APP)
        }
        return GlossaryScopeSelection(
            mode = GlossaryScopeMode.SELECTED_APP,
            selectedApp = SelectableApp(
                packageName = scopePackage,
                displayName = appLabel.ifBlank { scopePackage },
            ),
        )
    }

    fun scopedApp(
        mode: GlossaryScopeMode,
        currentApp: SelectableApp?,
        selectedApp: SelectableApp?,
    ): SelectableApp? = when (mode) {
        GlossaryScopeMode.GLOBAL -> null
        GlossaryScopeMode.CURRENT_APP -> currentApp
        GlossaryScopeMode.SELECTED_APP -> selectedApp
    }

    fun isValid(
        mode: GlossaryScopeMode,
        currentApp: SelectableApp?,
        selectedApp: SelectableApp?,
    ): Boolean = mode == GlossaryScopeMode.GLOBAL || scopedApp(mode, currentApp, selectedApp) != null
}
