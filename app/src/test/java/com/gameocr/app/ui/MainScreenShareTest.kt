package com.gameocr.app.ui

import com.gameocr.app.update.UpdateChecker
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenShareTest {

    @Test
    fun officialShareLinks_areStableHttpsDestinations() {
        data class Case(
            val name: String,
            val url: String,
            val expectedPath: String,
        )

        listOf(
            Case("source repository", GITHUB_URL, "/ciddwd/overlay-translator"),
            Case(
                "latest release",
                UpdateChecker.RELEASE_PAGE_URL,
                "/ciddwd/overlay-translator/releases/latest",
            ),
        ).forEach { case ->
            val uri = URI(case.url)
            assertEquals("${case.name} scheme", "https", uri.scheme)
            assertEquals("${case.name} host", "github.com", uri.host)
            assertEquals("${case.name} path", case.expectedPath, uri.path)
        }
    }

    @Test
    fun shareSection_usesSystemSharesheetAndFollowsLicenses() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val aboutContent = source
            .substringAfter("private fun AboutContent(")
            .substringBefore("private fun launchAppShare(")
        val shareLauncher = source
            .substringAfter("private fun launchAppShare(")
            .substringBefore("private fun SharePromptDialog(")

        data class Case(
            val name: String,
            val content: String,
            val marker: String,
        )

        listOf(
            Case("share label", aboutContent, "R.string.settings_about_share_label"),
            Case("share summary", aboutContent, "R.string.settings_about_share_summary"),
            Case("share action", aboutContent, "R.string.settings_about_share_action"),
            Case("shared callback", aboutContent, "onClick = onShareApp"),
            Case("text MIME type", shareLauncher, """type = "text/plain""""),
            Case("send action", shareLauncher, "Intent.ACTION_SEND"),
            Case("subject extra", shareLauncher, "Intent.EXTRA_SUBJECT"),
            Case("preview title extra", shareLauncher, "Intent.EXTRA_TITLE"),
            Case("text extra", shareLauncher, "Intent.EXTRA_TEXT"),
            Case("system chooser", shareLauncher, "Intent.createChooser"),
            Case("latest release link", source, "UpdateChecker.RELEASE_PAGE_URL"),
            Case("source repository link", source, "GITHUB_URL"),
        ).forEach { case ->
            assertTrue("${case.name} is missing", case.marker in case.content)
        }

        assertTrue(
            "share section must follow open-source licenses",
            aboutContent.indexOf("R.string.settings_about_view_licenses") <
                aboutContent.indexOf("R.string.settings_about_share_label"),
        )
        assertTrue(
            "share section must remain above the QQ community",
            aboutContent.indexOf("R.string.settings_about_share_label") <
                aboutContent.indexOf("R.string.settings_about_qq_group_label"),
        )
    }

    @Test
    fun oneTimePrompt_isDeferredAndConsumedBeforeDisplay() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val promptDialog = source
            .substringAfter("private fun SharePromptDialog(")
            .substringBefore("private fun UpdateResultDialog(")

        data class Case(
            val name: String,
            val marker: String,
        )

        listOf(
            Case("main entry is recorded", "recordMainScreenEntryForSharePrompt()"),
            Case("prompt is delayed", "delay(SHARE_PROMPT_DELAY_MS)"),
            Case("update loading blocks prompt", "updateVm.autoChecking.value"),
            Case("update dialog blocks prompt", "updateVm.state.value !is"),
            Case("shown flag is persisted", "markSharePromptShown()"),
            Case("prompt becomes visible", "showSharePrompt = true"),
            Case("share uses existing action", "onShareApp()"),
            Case("back and outside tap decline", "onDismissRequest = onDecline"),
            Case("same Activity records once", "sharePromptEntryRecorded"),
        ).forEach { case ->
            assertTrue("${case.name} is missing", case.marker in source)
        }

        assertTrue(
            "shown flag must be persisted before displaying the prompt",
            source.indexOf("viewModel.markSharePromptShown()") <
                source.indexOf("showSharePrompt = true"),
        )

        data class ZincCase(
            val name: String,
            val marker: String,
        )
        listOf(
            ZincCase("custom Compose dialog", "Dialog("),
            ZincCase("shared tour zinc palette", "FloatingMenuTourPalette.colors("),
            ZincCase("zinc border", "BorderStroke(1.dp, borderColor)"),
            ZincCase("custom rounded container", "RoundedCornerShape(20.dp)"),
            ZincCase("custom dialog width", "DialogProperties(usePlatformDefaultWidth = false)"),
            ZincCase("zinc primary action", "ButtonDefaults.buttonColors("),
            ZincCase("share icon", "Icons.Default.Share"),
        ).forEach { case ->
            assertTrue("${case.name} is missing", case.marker in promptDialog)
        }
        assertTrue(
            "stock AlertDialog must not style the share prompt",
            "AlertDialog(" !in promptDialog,
        )
    }

    @Test
    fun shareCopy_isCompleteAcrossSupportedLocales() {
        data class Case(
            val path: String,
            val expectedLabel: String,
            val expectedSummary: String,
            val expectedAction: String,
            val expectedDownloadLabel: String,
            val expectedSourceLabel: String,
            val expectedFeatureMarkers: List<String>,
            val expectedPromptTitle: String,
            val expectedPromptMessage: String,
            val expectedPromptConfirm: String,
            val expectedPromptDecline: String,
        )

        listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expectedLabel = "Share with friends",
                expectedSummary = "Enjoying Screen Translator? Recommend it to a friend.",
                expectedAction = "Share Screen Translator",
                expectedDownloadLabel = "Latest release:",
                expectedSourceLabel = "Source code:",
                expectedFeatureMarkers = listOf(
                    "foreign-language forums",
                    "overseas shopping and trading platforms",
                    "floating screenshot translation",
                    "continuous translation",
                    "manga mode",
                    "offline and cloud LLM translation",
                    "inflections and synonyms",
                    "TTS reading",
                ),
                expectedPromptTitle = "Recommend Screen Translator?",
                expectedPromptMessage = "If Screen Translator has been useful, consider recommending it to a friend " +
                    "who also needs translation.",
                expectedPromptConfirm = "Share with a friend",
                expectedPromptDecline = "No, thanks",
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expectedLabel = "分享给朋友",
                expectedSummary = "觉得屏译好用？推荐给朋友吧。",
                expectedAction = "分享屏译",
                expectedDownloadLabel = "最新版下载：",
                expectedSourceLabel = "开源项目：",
                expectedFeatureMarkers = listOf(
                    "外文论坛",
                    "海外购物与交易平台",
                    "悬浮窗截图翻译",
                    "循环翻译",
                    "漫画模式",
                    "离线和云端 LLM 翻译",
                    "词形与同义词",
                    "TTS 朗读",
                ),
                expectedPromptTitle = "愿意把屏译推荐给朋友吗？",
                expectedPromptMessage = "如果屏译对你有帮助，欢迎推荐给同样有翻译需求的朋友。",
                expectedPromptConfirm = "分享给朋友",
                expectedPromptDecline = "不分享",
            ),
        ).forEach { case ->
            val strings = stringResources(sourceFile(case.path))
            val shareText = strings.getValue("settings_about_share_text")
            assertEquals(
                "${case.path} label",
                case.expectedLabel,
                strings["settings_about_share_label"],
            )
            assertEquals(
                "${case.path} summary",
                case.expectedSummary,
                strings["settings_about_share_summary"],
            )
            assertEquals(
                "${case.path} action",
                case.expectedAction,
                strings["settings_about_share_action"],
            )
            assertTrue("${case.path} download label", case.expectedDownloadLabel in shareText)
            assertTrue("${case.path} source label", case.expectedSourceLabel in shareText)
            assertTrue("${case.path} latest release placeholder", "%1\$s" in shareText)
            assertTrue("${case.path} source placeholder", "%2\$s" in shareText)
            case.expectedFeatureMarkers.forEach { marker ->
                assertTrue("${case.path} feature marker: $marker", marker in shareText)
            }
            assertEquals(
                "${case.path} prompt title",
                case.expectedPromptTitle,
                strings["main_share_prompt_title"],
            )
            assertEquals(
                "${case.path} prompt message",
                case.expectedPromptMessage,
                strings["main_share_prompt_message"],
            )
            assertEquals(
                "${case.path} prompt confirm",
                case.expectedPromptConfirm,
                strings["main_share_prompt_confirm"],
            )
            assertEquals(
                "${case.path} prompt decline",
                case.expectedPromptDecline,
                strings["main_share_prompt_decline"],
            )
        }
    }

    private fun stringResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.getElementsByTagName("string").let { nodes ->
            buildMap {
                repeat(nodes.length) { index ->
                    val node = nodes.item(index)
                    put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
                }
            }
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
