package com.gameocr.app.appcontext

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppCatalogTest {
    @Test
    fun iconSize_cases() {
        data class Case(val density: Float, val expectedPx: Int)

        listOf(
            Case(density = 0f, expectedPx = 1),
            Case(density = 1f, expectedPx = 40),
            Case(density = 2f, expectedPx = 80),
            Case(density = 2.75f, expectedPx = 110),
            Case(density = 3.5f, expectedPx = 140),
        ).forEach { case ->
            assertEquals(
                "density=${case.density}",
                case.expectedPx,
                selectableAppIconSizePx(case.density),
            )
        }
    }

    @Test
    fun normalize_cases() {
        data class Case(
            val name: String,
            val apps: List<SelectableApp>,
            val ownPackage: String = "com.gameocr.app.debug",
            val expected: List<SelectableApp>,
        )

        val cases = listOf(
            Case(
                name = "trims values and falls back to package name",
                apps = listOf(SelectableApp("  game.alpha  ", "  ")),
                expected = listOf(SelectableApp("game.alpha", "game.alpha")),
            ),
            Case(
                name = "removes own app and blank packages",
                apps = listOf(
                    SelectableApp("com.gameocr.app.debug", "Screen Translator"),
                    SelectableApp("", "Missing package"),
                    SelectableApp("game.alpha", "Alpha"),
                ),
                expected = listOf(SelectableApp("game.alpha", "Alpha")),
            ),
            Case(
                name = "deduplicates packages and sorts by label",
                apps = listOf(
                    SelectableApp("game.zeta", "Zeta"),
                    SelectableApp("game.alpha", "Alpha secondary activity"),
                    SelectableApp("game.alpha", "Alpha"),
                    SelectableApp("game.beta", "beta"),
                ),
                expected = listOf(
                    SelectableApp("game.alpha", "Alpha"),
                    SelectableApp("game.beta", "beta"),
                    SelectableApp("game.zeta", "Zeta"),
                ),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                SelectableAppPolicy.normalize(case.apps, case.ownPackage),
            )
        }
    }

    @Test
    fun search_cases() {
        val apps = listOf(
            SelectableApp("com.mihoyo.game", "Genshin Impact"),
            SelectableApp("com.tencent.mm", "微信"),
            SelectableApp("com.openai.chatgpt", "ChatGPT"),
        )
        data class Case(val query: String, val expectedPackages: List<String>)
        val cases = listOf(
            Case("", apps.map(SelectableApp::packageName)),
            Case("   ", apps.map(SelectableApp::packageName)),
            Case("GENSHIN", listOf("com.mihoyo.game")),
            Case("mihoyo", listOf("com.mihoyo.game")),
            Case("genshin mihoyo", listOf("com.mihoyo.game")),
            Case("微信", listOf("com.tencent.mm")),
            Case("OPENAI CHATGPT", listOf("com.openai.chatgpt")),
            Case("not-installed", emptyList()),
        )

        cases.forEach { case ->
            assertEquals(
                case.query,
                case.expectedPackages,
                SelectableAppPolicy.filter(apps, case.query).map(SelectableApp::packageName),
            )
        }
    }
}
