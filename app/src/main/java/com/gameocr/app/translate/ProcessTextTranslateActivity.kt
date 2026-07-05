package com.gameocr.app.translate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.gameocr.app.GameOcrApp
import com.gameocr.app.R
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.overlay.TranslationCardOverlay
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 系统「文字选中」菜单入口：用户在任意 App 长按选中文字 → 系统弹处理菜单 → 「屏译翻译」
 * 出现其中（注册了 ACTION_PROCESS_TEXT）。点击后系统启动本 Activity 并把选中的文字
 * 放在 [Intent.EXTRA_PROCESS_TEXT]。
 *
 * Activity 本身无 UI（Theme.GameOcr.Transparent + onCreate 立即 finish）：
 *  - 取 text → 派给 [GameOcrApp.appScope]（Activity 结束不取消，保证翻译跑完）
 *  - 用 applicationContext 持 [TranslationCardOverlay]，结果以系统级 overlay 浮卡
 *    盖在原 App 上方，跟划词翻译同款 UI
 *  - 缺 SYSTEM_ALERT_WINDOW 权限时跳系统授权页 + Toast 提示
 *
 * 注意：用 ApplicationContext 持 overlay 避免 Activity finish 后 window leak。
 */
@AndroidEntryPoint
class ProcessTextTranslateActivity : ComponentActivity() {

    @Inject lateinit var translator: Translator
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var logRepository: LogRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            finish(); return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.process_text_need_overlay, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            finish(); return
        }

        // 翻译跑在 application scope；finish() 后 Activity 不在了也不影响协程。
        val app = applicationContext
        val appScope = (application as GameOcrApp).appScope
        val translatingLabel = getString(R.string.process_text_translating)
        val failedLabel = getString(R.string.process_text_translate_failed)
        val engineFailedFmt = getString(R.string.log_msg_translate_failed_format)
        appScope.launch {
            val settings = settingsRepository.get()
            // 浮卡用 applicationContext 避免持 Activity 引用 leak
            val card = TranslationCardOverlay(app)
            withContext(Dispatchers.Main) {
                card.show(text, translatingLabel, null, settings)
            }
            // translate() 返回 String? —— 引擎返回 null 视作失败，与 onFailure 走同一兜底分支
            val translateStartedAt = System.currentTimeMillis()
            val translated: String = runCatching {
                withContext(Dispatchers.IO) { translator.translate(text, settings) }
            }.fold(
                onSuccess = { result ->
                    val translateElapsedMs = elapsedSince(translateStartedAt)
                    if (result.isNullOrBlank()) {
                        logRepository.error(
                            LogRepository.Category.TRANSLATE,
                            String.format(engineFailedFmt, settings.translatorEngine.name),
                            elapsedMs = translateElapsedMs
                        )
                        failedLabel
                    } else {
                        logRepository.pair(
                            LogRepository.Category.TRANSLATE,
                            text,
                            result,
                            elapsedMs = translateElapsedMs
                        )
                        result
                    }
                },
                onFailure = { t ->
                    val translateElapsedMs = elapsedSince(translateStartedAt)
                    Timber.w(t, "PROCESS_TEXT translate failed")
                    logRepository.error(
                        LogRepository.Category.TRANSLATE,
                        String.format(engineFailedFmt, settings.translatorEngine.name),
                        t,
                        elapsedMs = translateElapsedMs
                    )
                    failedLabel
                }
            )
            withContext(Dispatchers.Main) {
                card.show(text, translated, null, settings)
            }
        }
        finish()
    }

    private fun elapsedSince(startMs: Long): Long =
        (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
}
