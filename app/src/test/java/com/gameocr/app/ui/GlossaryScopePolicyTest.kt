package com.gameocr.app.ui

import com.gameocr.app.appcontext.SelectableApp
import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryScopePolicyTest {
    private val currentApp = SelectableApp("game.current", "Current Game")
    private val selectedApp = SelectableApp("game.selected", "Selected Game")

    @Test
    fun initialSelection_cases() {
        data class Case(
            val name: String,
            val scopePackage: String,
            val appLabel: String,
            val currentApp: SelectableApp?,
            val expectedMode: GlossaryScopeMode,
            val expectedApp: SelectableApp?,
        )
        val cases = listOf(
            Case("new global term", "", "", currentApp, GlossaryScopeMode.GLOBAL, null),
            Case(
                "existing current app term",
                currentApp.packageName,
                currentApp.displayName,
                currentApp,
                GlossaryScopeMode.CURRENT_APP,
                null,
            ),
            Case(
                "existing different app term",
                selectedApp.packageName,
                selectedApp.displayName,
                currentApp,
                GlossaryScopeMode.SELECTED_APP,
                selectedApp,
            ),
            Case(
                "keeps app term when current app is unavailable",
                selectedApp.packageName,
                selectedApp.displayName,
                null,
                GlossaryScopeMode.SELECTED_APP,
                selectedApp,
            ),
            Case(
                "uses package when imported label is blank",
                selectedApp.packageName,
                "",
                currentApp,
                GlossaryScopeMode.SELECTED_APP,
                SelectableApp(selectedApp.packageName, selectedApp.packageName),
            ),
        )

        cases.forEach { case ->
            val result = GlossaryScopePolicy.initialSelection(
                scopePackage = case.scopePackage,
                appLabel = case.appLabel,
                currentApp = case.currentApp,
            )
            assertEquals(case.name, case.expectedMode, result.mode)
            assertEquals(case.name, case.expectedApp, result.selectedApp)
        }
    }

    @Test
    fun resolutionAndValidation_cases() {
        data class Case(
            val mode: GlossaryScopeMode,
            val currentApp: SelectableApp?,
            val selectedApp: SelectableApp?,
            val expectedApp: SelectableApp?,
            val valid: Boolean,
        )
        val cases = listOf(
            Case(GlossaryScopeMode.GLOBAL, currentApp, selectedApp, null, true),
            Case(GlossaryScopeMode.CURRENT_APP, currentApp, selectedApp, currentApp, true),
            Case(GlossaryScopeMode.CURRENT_APP, null, selectedApp, null, false),
            Case(GlossaryScopeMode.SELECTED_APP, currentApp, selectedApp, selectedApp, true),
            Case(GlossaryScopeMode.SELECTED_APP, currentApp, null, null, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expectedApp,
                GlossaryScopePolicy.scopedApp(case.mode, case.currentApp, case.selectedApp),
            )
            assertEquals(
                case.toString(),
                case.valid,
                GlossaryScopePolicy.isValid(case.mode, case.currentApp, case.selectedApp),
            )
        }
    }
}
