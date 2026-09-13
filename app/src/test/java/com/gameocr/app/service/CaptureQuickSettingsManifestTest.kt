package com.gameocr.app.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class CaptureQuickSettingsManifestTest {
    @Test
    fun manifest_declaresToggleableQuickSettingsTile() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        listOf(
            ".service.CaptureQuickSettingsTileService",
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            "android.service.quicksettings.action.QS_TILE",
            "android.service.quicksettings.TOGGLEABLE_TILE",
            "@drawable/ic_quick_settings_tile",
        ).forEach { expected ->
            assertTrue("missing $expected", manifest.contains(expected))
        }
    }

    @Test
    fun captureLaunchHost_isIsolatedWithoutBreakingConsentResults() {
        val android = "http://schemas.android.com/apk/res/android"
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val host = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".capture.CaptureStartRequestActivity" }

        data class Case(val attribute: String, val expected: String)
        listOf(
            Case("taskAffinity", ""),
            Case("exported", "false"),
            Case("excludeFromRecents", "true"),
            Case("theme", "@style/Theme.GameOcr.Transparent"),
        ).forEach { case ->
            assertTrue("explicit ${case.attribute}", host.hasAttributeNS(android, case.attribute))
            assertEquals(case.attribute, case.expected, host.getAttributeNS(android, case.attribute))
        }
        assertFalse("the host must survive the consent dialog", host.getAttributeNS(android, "noHistory") == "true")
    }
}
