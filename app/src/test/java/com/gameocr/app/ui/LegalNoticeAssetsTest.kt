package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.security.MessageDigest

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
            Case(
                "ML Kit terms",
                listOf(
                    "Google ML Kit",
                    "ML Kit Translation 17.0.3",
                    "performance and utilization metrics",
                    "https://developers.google.com/ml-kit/terms",
                ),
            ),
            Case(
                "native runtime",
                listOf(
                    "ONNX Runtime",
                    "onnxruntime_third_party_notices_1_20_0.txt",
                    "llama.cpp",
                    "Vulkan-Headers",
                    "SPIRV-Headers",
                ),
            ),
            Case("Shizuku", listOf("Shizuku API", "Copyright (c) 2021 RikkaW")),
            Case("Google transitive runtime", listOf("Android Data Transport", "Firebase Components / Encoders", "JSpecify")),
            Case("bundled Paddle models", listOf("doc_ori.onnx", "textline_ori.onnx", "SHA-256")),
            Case("Hy-MT2", listOf("Hy-MT2-1.8B-GGUF", "Copyright (C) 2026 Tencent", "Apache License 2.0")),
            Case(
                "Sakura",
                listOf(
                    "Sakura-1.5B-Qwen2.5-v1.0",
                    "Hugging Face user shing3232",
                    "Quantization is a modification",
                    "CC BY-NC-SA 4.0",
                    "Commercial use is not permitted",
                ),
            ),
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
                requiredMarkers = listOf(
                    "Hy-MT2-1.8B-GGUF",
                    "Apache License",
                    "Copyright (C) 2026 Tencent. All rights reserved.",
                ),
                forbiddenMarkers = listOf("Hy-MT1.5", "Territory", "EUROPEAN UNION"),
            ),
            Case(
                name = "Sakura",
                asset = "sakura_notice.txt",
                requiredMarkers = listOf(
                    "SakuraLLM/Sakura-1.5B-Qwen2.5-v1.0-GGUF",
                    "Hugging Face user shing3232",
                    "without further modification",
                    "CC BY-NC-SA 4.0",
                    "Commercial use is not permitted",
                    "Qwen2.5-1.5B",
                    "Apache License",
                ),
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
    fun onnxRuntimeNotices_matchPinnedOfficialArtifact() {
        val asset = moduleFile("src/main/assets/onnxruntime_third_party_notices_1_20_0.txt")
        val bytes = asset.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02X".format(byte) }

        assertEquals(338_538, bytes.size)
        assertEquals(
            "CF7342F7BA482EF715AE58F5F497A8D3564FA255164175AEA324CD293C5701A0",
            digest,
        )
        assertTrue(asset.readText().startsWith("THIRD PARTY SOFTWARE NOTICES AND INFORMATION"))
    }

    @Test
    fun thirdPartyNotices_matchCurrentBuildVersions() {
        data class Case(val name: String, val catalogKey: String, val noticePrefix: String)

        val notice = moduleFile("src/main/assets/third_party_notices.txt").readText()
        val catalog = repositoryFile("gradle/libs.versions.toml").readText()
        val appBuild = moduleFile("build.gradle.kts").readText()
        val versionName = requireNotNull(
            Regex("""versionName\s*=\s*"([^"]+)"""").find(appBuild)?.groupValues?.get(1),
        )

        assertTrue("stale application version", notice.contains("v$versionName build configuration"))
        assertTrue("project license must use the main branch", notice.contains("blob/main/LICENSE"))
        assertFalse("project license must not use the dev branch", notice.contains("blob/dev/LICENSE"))

        listOf(
            Case("Kotlin", "kotlin", "Kotlin"),
            Case("Material Components", "material", "Material Components for Android"),
            Case("Dagger and Hilt", "hilt", "Dagger and Hilt"),
            Case("Retrofit", "retrofit", "Retrofit"),
            Case("OkHttp", "okhttp", "OkHttp"),
            Case("kotlinx.serialization", "serialization", "kotlinx.serialization"),
            Case("kotlinx.coroutines", "coroutines", "kotlinx.coroutines"),
            Case("Timber", "timber", "Timber"),
            Case("Shizuku", "shizuku", "Shizuku API"),
            Case("ML Kit Text Recognition", "mlkitTextRecognition", "Text Recognition"),
            Case("ML Kit Translation", "mlkitTranslate", "ML Kit Translation"),
            Case("ONNX Runtime", "onnxruntime", "ONNX Runtime Android"),
        ).forEach { case ->
            val version = requireNotNull(
                Regex("(?m)^${Regex.escape(case.catalogKey)} = \"([^\"]+)\"")
                    .find(catalog)
                    ?.groupValues
                    ?.get(1),
            ) { "missing version catalog key: ${case.catalogKey}" }
            assertTrue(
                "${case.name}: NOTICE missing version $version",
                notice.contains("${case.noticePrefix} $version"),
            )
        }
    }

    @Test
    fun noticeUrls_areUniqueAbsoluteHttpsUrls() {
        data class Case(val name: String, val asset: String, val minimumUrlCount: Int)

        listOf(
            Case("third-party notices", "third_party_notices.txt", 25),
            Case("Hy-MT2 notice", "hy_mt_license.txt", 2),
            Case("Sakura notice", "sakura_notice.txt", 5),
        ).forEach { case ->
            val urls = Regex("https://[^\\s]+")
                .findAll(moduleFile("src/main/assets/${case.asset}").readText())
                .map { it.value.trimEnd('.', ',', ')') }
                .toList()

            assertTrue("${case.name}: expected source links", urls.size >= case.minimumUrlCount)
            assertEquals("${case.name}: duplicate URLs", urls.distinct(), urls)
            urls.forEach { url ->
                val uri = URI(url)
                assertEquals(url, "https", uri.scheme)
                assertTrue("${case.name}: missing host: $url", !uri.host.isNullOrBlank())
            }
        }
    }

    @Test
    fun mlKitDataDisclosure_isVisibleInSettingsAndNotices() {
        data class Case(val name: String, val file: File, val markers: List<String>)

        listOf(
            Case(
                "settings screen",
                moduleFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
                listOf("R.string.settings_mlkit_data_disclosure"),
            ),
            Case(
                "English resources",
                moduleFile("src/main/res/values/strings.xml"),
                listOf("settings_mlkit_data_disclosure", "Captured images, source text, and translations are not sent to Google"),
            ),
            Case(
                "Chinese resources",
                moduleFile("src/main/res/values-zh-rCN/strings.xml"),
                listOf("settings_mlkit_data_disclosure", "截屏、原文和译文不会发送给 Google"),
            ),
            Case(
                "third-party notices",
                moduleFile("src/main/assets/third_party_notices.txt"),
                listOf("Input images and text are processed on-device", "performance and utilization metrics"),
            ),
        ).forEach { case ->
            val text = case.file.readText()
            case.markers.forEach { marker ->
                assertTrue("${case.name}: missing $marker", text.contains(marker))
            }
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
            Case("ONNX Runtime notices asset", "ONNXRUNTIME_NOTICES_ASSET"),
            Case("Hy-MT2 notice asset", "HY_MT_NOTICE_ASSET"),
            Case("Sakura notice asset", "SAKURA_NOTICE_ASSET"),
            Case("on-demand offline asset loading", "context.assets.open(section.assetName)"),
            Case("on-demand section selection", "selectedAssetName = section.assetName"),
            Case("selectable notice text", "SelectionContainer"),
            Case("scrollable notice text", ".verticalScroll(rememberScrollState())"),
            Case("localized page title", "R.string.settings_about_licenses_page_title"),
            Case("system back handling", "BackHandler(onBack = navigateBack)"),
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
