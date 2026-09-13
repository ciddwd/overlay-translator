package com.gameocr.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadCancelWiringTest {
    private val root = listOf(File("src"), File("app/src")).first(File::isDirectory)
    private fun source(path: String) = File(root, "main/java/com/gameocr/app/$path").readText().replace("\r\n", "\n")

    @Test
    fun entryPoints_tableDriven_shareConfirmationWithoutChangingAutomaticTimeout() {
        val dialog = source("ui/ModelDownloadCancelDialog.kt")
        val screen = source("ui/SettingsScreen.kt")
        val mlkit = source("ui/MlKitModelDownloadUi.kt")
        val worker = source("download/ModelDownloadWorker.kt")
        val activity = source("download/ModelDownloadCancelActivity.kt")
        listOf(
            "reuse existing dialog style" to dialog.contains("CatalystAlertDialog("),
            "no default Material dialog" to !dialog.contains("import androidx.compose.material3.AlertDialog"),
            "no independent dialog surface" to !dialog.contains("Surface("),
            "dismiss leaves download alone" to dialog.contains("onDismissRequest = onDismiss"),
            "negative dismisses" to dialog.contains("TextButton(onClick = onDismiss)"),
            "positive confirms" to dialog.contains("TextButton(onClick = onConfirm)"),
            "per request state" to dialog.contains("remember(requestKey)"),
            "consume before cancellation" to dialog.contains("if (confirmation.consume(requestKey)) onCancel()"),
            "work UUID passed to card" to screen.contains("requestKey = download.id.toString()"),
            "reordered jobs retain their own dialog" to screen.contains("androidx.compose.runtime.key(download.id)"),
            "background card shared button" to screen.contains("ModelDownloadCancelButton(requestKey = requestKey, onCancel = onCancel)"),
            "MLKit shared button" to mlkit.contains("ModelDownloadCancelButton(requestKey = requestId.toString(), onCancel = onCancel)"),
            "settings captures displayed request" to screen.contains("val mlKitCancelRequestId = mlKitModelDownloadState.requestId"),
            "welcome captures displayed request" to source("onboarding/OnboardingScreen.kt").contains("val mlKitCancelRequestId = mlKitState.requestId"),
            "notification no immediate cancellation" to !worker.contains("createCancelPendingIntent"),
            "notification shared dialog" to activity.contains("ModelDownloadCancelDialog("),
            "notification request isolated by URI" to activity.contains("gameocr-model-download://cancel/\$workId"),
            "immutable notification action" to activity.contains("PendingIntent.FLAG_IMMUTABLE"),
            "direct Activity pending intent" to activity.contains("PendingIntent.getActivity("),
            "recheck finished work on confirm" to activity.contains("canCancelModelDownload(work.first())"),
            "no navigation to home" to !activity.contains("MainActivity"),
            "uses app locale" to activity.contains("AppLocalePrefs.wrap(newBase)"),
            "uses app theme" to activity.contains("ThemeModePrefs.read(this)"),
            "timeout still automatic" to source("translate/MlKitModelDownloadSession.kt").contains("pending.copy(phase = MlKitDownloadPhase.TIMED_OUT)"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test
    fun resources_tableDriven_onlyApprovedTitleAndButtonLabels() {
        listOf(
            "values" to listOf("Cancel this download?", "Cancel", "OK"),
            "values-zh-rCN" to listOf("取消当前下载？", "取消", "确定"),
        ).forEach { (locale, expected) ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "main/res/$locale/strings.xml"))
            val strings = doc.getElementsByTagName("string")
            val values = (0 until strings.length).associate { index ->
                val node = strings.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            listOf("model_download_cancel_confirm_title", "model_download_cancel", "model_download_cancel_confirm_ok")
                .zip(expected).forEach { (key, value) -> assertEquals("$locale $key", value, values[key]) }
        }
    }

    @Test
    fun notificationHost_isPrivateAndDoesNotJoinTheHomeTask() {
        val manifest = File(root, "main/AndroidManifest.xml").readText()
        val entry = Regex("<activity\\s+android:name=\"\\.download.ModelDownloadCancelActivity\"[\\s\\S]*?/>")
            .find(manifest)?.value ?: error("Missing confirmation activity")
        listOf("android:exported=\"false\"", "android:taskAffinity=\"\"", "android:excludeFromRecents=\"true\"",
            "android:theme=\"@style/Theme.GameOcr.Transparent\"")
            .forEach { assertTrue(it, entry.contains(it)) }
    }
}
