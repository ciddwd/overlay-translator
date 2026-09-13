package com.gameocr.app.network

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class DebugNetworkSecurityConfigTest {

    @Test
    fun configurations_tableDriven_keepSystemTrustAndExistingCleartextPolicy() {
        data class Case(val sourceSet: String, val sections: List<String>)

        listOf(
            Case("main", listOf("base-config")),
            Case("debug", listOf("base-config", "debug-overrides")),
        ).forEach { case ->
            val root = xml("src/${case.sourceSet}/res/xml/network_security_config.xml")
            assertEquals(case.sourceSet, "network-security-config", root.tagName)
            assertEquals(case.sourceSet, case.sections, root.children().map { it.tagName })
            val base = root.children().single { it.tagName == "base-config" }
            assertEquals(case.sourceSet, "true", base.getAttribute("cleartextTrafficPermitted"))
            assertEquals(case.sourceSet, listOf("trust-anchors"), base.children().map { it.tagName })
            val certificates = base.children().single().children()
            assertEquals(case.sourceSet, listOf("certificates"), certificates.map { it.tagName })
            assertEquals(case.sourceSet, listOf("system"), certificates.map { it.getAttribute("src") })
        }
    }

    @Test
    fun userCertificateTrust_isOnlyInsideTheDebuggableOverride() {
        val root = xml("src/debug/res/xml/network_security_config.xml")
        val override = root.children().single { it.tagName == "debug-overrides" }
        assertEquals(listOf("trust-anchors"), override.children().map { it.tagName })
        val certificates = override.children().single().children()
        assertEquals(listOf("certificates"), certificates.map { it.tagName })
        assertEquals(listOf("user"), certificates.map { it.getAttribute("src") })
        assertEquals(1, certificates.single().attributes.length)

        val main = xml("src/main/res/xml/network_security_config.xml")
        assertEquals(0, main.getElementsByTagName("debug-overrides").length)
        assertEquals(1, main.getElementsByTagName("certificates").length)
    }

    @Test
    fun manifest_usesVariantResourceWithoutMakingReleaseDebuggable() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val application = manifest.children().single { it.tagName == "application" }
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS(androidNamespace, "networkSecurityConfig"),
        )
        assertFalse(application.getAttributeNS(androidNamespace, "debuggable") == "true")
    }

    private fun xml(path: String): Element {
        val file = listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source not found: $path")
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
}
