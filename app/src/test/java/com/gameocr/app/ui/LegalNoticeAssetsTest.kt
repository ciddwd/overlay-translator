package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class LegalNoticeAssetsTest {

    @Test
    fun packagedLegalFiles_matchRepositorySources() {
        data class Case(
            val name: String,
            val repositoryFile: String,
            val assetFile: String,
        )

        listOf(
            Case("third-party notices", "NOTICE", "third_party_notices.txt"),
            Case("Apache 2.0 license", "LICENSE", "apache_license_2_0.txt"),
        ).forEach { case ->
            assertEquals(
                case.name,
                normalize(repositoryFile(case.repositoryFile).readText()),
                normalize(moduleFile("src/main/assets/${case.assetFile}").readText()),
            )
        }
    }

    @Test
    fun thirdPartyNotices_coverRuntimeAndModelCases() {
        data class Case(val name: String, val requiredMarkers: List<String>)

        val notice = moduleFile("src/main/assets/third_party_notices.txt").readText()
        listOf(
            Case("Android runtime", listOf("AndroidX", "Material Components", "Kotlin", "Dagger and Hilt")),
            Case("network runtime", listOf("Retrofit", "OkHttp", "Okio", "Timber")),
            Case("ML Kit terms", listOf("Google ML Kit", "https://developers.google.com/ml-kit/terms")),
            Case("native runtime", listOf("ONNX Runtime", "llama.cpp", "Vulkan-Headers", "SPIRV-Headers")),
            Case("Shizuku", listOf("Shizuku API", "Copyright (c) 2021 RikkaW")),
            Case("bundled Paddle models", listOf("doc_ori.onnx", "textline_ori.onnx", "SHA-256")),
            Case("Hy-MT2", listOf("Hy-MT2-1.8B-GGUF", "Copyright 2026 Tencent", "Apache License 2.0")),
            Case("Sakura", listOf("Sakura-1.5B-Qwen2.5-v1.0", "CC BY-NC-SA 4.0", "Commercial use is not permitted")),
            Case("PaddleOCR downloads", listOf("PaddleOCR v6", "PaddleOCR v5")),
            Case("manga-ocr caveat", listOf("manga-ocr ONNX", "does not declare license metadata", "must not be described as open-source")),
        ).forEach { case ->
            case.requiredMarkers.forEach { marker ->
                assertTrue("${case.name}: missing $marker", notice.contains(marker))
            }
        }
    }

    @Test
    fun modelNotices_matchCurrentUpstreamLicenseCases() {
        data class Case(
            val name: String,
            val asset: String,
            val requiredMarkers: List<String>,
            val forbiddenMarkers: List<String>,
        )

        listOf(
            Case(
                name = "Hy-MT2",
                asset = "hy_mt_license.txt",
                requiredMarkers = listOf("Hy-MT2-1.8B-GGUF", "Apache License", "Copyright 2026 Tencent"),
                forbiddenMarkers = listOf("Hy-MT1.5", "Territory", "EUROPEAN UNION"),
            ),
            Case(
                name = "Sakura",
                asset = "sakura_notice.txt",
                requiredMarkers = listOf("CC BY-NC-SA 4.0", "Commercial use is not permitted", "Qwen2.5-1.5B", "Apache License"),
                forbiddenMarkers = listOf("Tongyi Qianwen LICENSE AGREEMENT"),
            ),
        ).forEach { case ->
            val text = moduleFile("src/main/assets/${case.asset}").readText()
            case.requiredMarkers.forEach { marker ->
                assertTrue("${case.name}: missing $marker", text.contains(marker))
            }
            case.forbiddenMarkers.forEach { marker ->
                assertFalse("${case.name}: stale marker $marker", text.contains(marker))
            }
        }
    }

    @Test
    fun noticeUrls_areUniqueAbsoluteHttpsUrls() {
        val notice = moduleFile("src/main/assets/third_party_notices.txt").readText()
        val urls = Regex("https://[^\\s]+")
            .findAll(notice)
            .map { it.value.trimEnd('.', ',', ')') }
            .toList()

        assertTrue("expected a substantive source list", urls.size >= 20)
        assertEquals("duplicate legal-notice URLs", urls.distinct(), urls)
        urls.forEach { url ->
            val uri = URI(url)
            assertEquals(url, "https", uri.scheme)
            assertTrue("missing host: $url", !uri.host.isNullOrBlank())
        }
    }

    @Test
    fun legalNotices_areRenderedOnDedicatedScreen() {
        val aboutSource = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val activitySource = moduleFile("src/main/java/com/gameocr/app/ui/MainActivity.kt").readText()
        val pageSource = moduleFile("src/main/java/com/gameocr/app/ui/LegalNoticesScreen.kt").readText()
        data class Case(val name: String, val marker: String)

        listOf(
            Case("third-party notice asset", "THIRD_PARTY_NOTICES_ASSET"),
            Case("Apache license asset", "APACHE_LICENSE_ASSET"),
            Case("offline asset loading", "context.assets.open(section.assetName)"),
            Case("selectable notice text", "SelectionContainer"),
            Case("scrollable notice text", ".verticalScroll(rememberScrollState())"),
            Case("localized page title", "R.string.settings_about_licenses_page_title"),
            Case("system back handling", "BackHandler(onBack = onBack)"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", pageSource.contains(case.marker))
        }

        listOf(
            Case("about entry callback", "onClick = onOpenLegalNotices"),
            Case("main route callback", "onOpenLegalNotices = { routeName = Route.LegalNotices.name }"),
            Case("dedicated route", "Route.LegalNotices -> LegalNoticesScreen"),
        ).forEach { case ->
            val source = if (case.name == "about entry callback") aboutSource else activitySource
            assertTrue("${case.name}: missing ${case.marker}", source.contains(case.marker))
        }

        assertFalse("legal notices must not use local dialog state", aboutSource.contains("showLegalNotices"))
        assertFalse("legal notices must not use a dialog title", aboutSource.contains("licenses_dialog_title"))
    }

    @Test
    fun aboutPage_ordersProjectThenLegalThenCommunity() {
        val source = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val aboutContent = source.substring(
            source.indexOf("private fun AboutContent"),
            source.indexOf("private fun UpdateResultDialog"),
        )
        data class Case(val name: String, val marker: String)

        val sections = listOf(
            Case("project address", "R.string.settings_about_github_label"),
            Case("open-source licenses", "R.string.settings_about_open_source_licenses"),
            Case("QQ community", "R.string.settings_about_qq_group_label"),
        )
        val positions = sections.map { case ->
            aboutContent.indexOf(case.marker).also { position ->
                assertTrue("missing ${case.name}", position >= 0)
            }
        }

        positions.zipWithNext().forEachIndexed { index, (before, after) ->
            assertTrue(
                "${sections[index].name} must appear before ${sections[index + 1].name}",
                before < after,
            )
        }
        assertTrue(
            "QQ community must follow the update action and remain the final about section",
            positions.last() > aboutContent.indexOf("R.string.update_btn_check"),
        )
    }

    private fun normalize(text: String): String = text.replace("\r\n", "\n").trimEnd()

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")

    private fun repositoryFile(path: String): File = listOf(File("..", path), File(path))
        .firstOrNull(File::isFile)
        ?: error("Repository file not found: $path")
}
