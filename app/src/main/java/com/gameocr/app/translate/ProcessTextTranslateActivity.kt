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
import com.gameocr.app.overlay.TtsPlaybackAction
import com.gameocr.app.tts.TtsEngine
import com.gameocr.app.tts.ttsFailureMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    @Inject lateinit var ttsEngine: TtsEngine

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
            val speechEngine = ttsEngine
            val speechLogRepository = logRepository
            val playbackSessionId = "process-text:${System.nanoTime()}"
            fun speechAction(
                role: ProcessTextSpeechRole,
                playbackKey: String = role.name.lowercase(),
            ): TtsPlaybackAction? {
                if (!settings.ttsEnabled) return null
                val playbackId = "$playbackSessionId:$playbackKey"
                fun dispatch(spokenText: String, toggle: Boolean) {
                    appScope.launch(Dispatchers.Main.immediate) {
                        runCatching {
                            val spokenSettings = processTextSpeechSettings(settings, role)
                            if (toggle) {
                                speechEngine.toggle(spokenText, spokenSettings, playbackId)
                            } else {
                                speechEngine.speak(spokenText, spokenSettings, playbackId)
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            Timber.w(error, "PROCESS_TEXT %s TTS failed", role.name.lowercase())
                            speechLogRepository.warn(
                                LogRepository.Category.TRANSLATE,
                                "TTS failed: ${error.javaClass.simpleName}: " +
                                    error.message.orEmpty().take(160),
                            )
                            Toast.makeText(
                                app,
                                app.ttsFailureMessage(error),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                return TtsPlaybackAction(
                    playbackId = playbackId,
                    playbackState = speechEngine.playbackState,
                    onToggle = { spokenText -> dispatch(spokenText, true) },
                    onStart = { spokenText -> dispatch(spokenText, false) },
                )
            }
            val sourceSpeech = speechAction(ProcessTextSpeechRole.SOURCE)
            val translationSpeech = speechAction(ProcessTextSpeechRole.TRANSLATION)
            val dictionarySpeech = speechAction(
                role = ProcessTextSpeechRole.TRANSLATION,
                playbackKey = "dictionary",
            )
            // 浮卡用 applicationContext 避免持 Activity 引用 leak
            val card = TranslationCardOverlay(app)
            withContext(Dispatchers.Main) {
                card.show(
                    sourceText = text,
                    translation = translatingLabel,
                    wordResult = null,
                    settings = settings,
                    onSpeakSource = sourceSpeech,
                )
            }
            val translateStartedAt = System.currentTimeMillis()
            val dictionaryTerm = WordHeuristic.manuallySelectedDictionaryTermOrNull(
                text = text,
                sourceLang = settings.sourceLang,
                translatorEngine = settings.translatorEngine,
            )
            Timber.i(
                "PROCESS_TEXT dictionary engine=%s sourceLang=%s selectedLength=%d eligible=%s termLength=%d",
                settings.translatorEngine.name,
                settings.sourceLang,
                text.length,
                dictionaryTerm != null,
                dictionaryTerm?.length ?: 0,
            )
            val outcome = WordSelectTranslationCoordinator(translator).execute(
                source = text,
                settings = settings,
                dictionaryTerm = dictionaryTerm,
                onPartialTranslation = { partial ->
                    withContext(Dispatchers.Main) { card.updateTranslation(partial) }
                },
                onWordResult = { result ->
                    withContext(Dispatchers.Main) { card.updateWordResult(result) }
                },
            )
            outcome.dictionaryError?.let { error ->
                Timber.w(error, "PROCESS_TEXT dictionary request failed")
                logRepository.warn(
                    LogRepository.Category.TRANSLATE,
                    "Dictionary lookup failed: ${error.javaClass.simpleName}: " +
                        error.message.orEmpty().take(160),
                )
            }
            outcome.translationError?.let { error ->
                Timber.w(error, "PROCESS_TEXT translate failed")
                logRepository.error(
                    LogRepository.Category.TRANSLATE,
                    String.format(engineFailedFmt, settings.translatorEngine.name),
                    error,
                    elapsedMs = elapsedSince(translateStartedAt),
                )
            }
            val presentation = processTextTranslationPresentation(outcome, failedLabel)
            Timber.i(
                "PROCESS_TEXT dictionary parsed=%s phonetic=%s pos=%d definitions=%d notes=%d examples=%d",
                outcome.wordResult != null,
                outcome.wordResult?.phonetic?.isNotBlank() == true,
                outcome.wordResult?.pos?.size ?: 0,
                outcome.wordResult?.definitions?.size ?: 0,
                outcome.wordResult?.difficultyNotes?.size ?: 0,
                outcome.wordResult?.examples?.size ?: 0,
            )
            if (!presentation.translationSucceeded && outcome.translationError == null) {
                logRepository.error(
                    LogRepository.Category.TRANSLATE,
                    String.format(engineFailedFmt, settings.translatorEngine.name),
                    elapsedMs = elapsedSince(translateStartedAt),
                )
            }
            if (presentation.translationSucceeded) {
                logRepository.pair(
                    LogRepository.Category.TRANSLATE,
                    text,
                    presentation.translation,
                    elapsedMs = elapsedSince(translateStartedAt),
                )
            }
            withContext(Dispatchers.Main) {
                card.show(
                    sourceText = text,
                    translation = presentation.translation,
                    wordResult = outcome.wordResult,
                    settings = settings,
                    onSpeakSource = sourceSpeech,
                    onSpeakTranslation = translationSpeech.takeIf {
                        presentation.translationSucceeded
                    },
                    onSpeakDictionary = dictionarySpeech.takeIf { outcome.wordResult != null },
                )
            }
        }
        finish()
    }

    private fun elapsedSince(startMs: Long): Long =
        (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
}

internal enum class ProcessTextSpeechRole {
    SOURCE,
    TRANSLATION,
}

internal data class ProcessTextTranslationPresentation(
    val translation: String,
    val translationSucceeded: Boolean,
)

internal fun processTextTranslationPresentation(
    outcome: WordSelectTranslationOutcome,
    failureText: String,
): ProcessTextTranslationPresentation {
    val dictionaryFallback = outcome.wordResult?.fallbackTranslation
        ?: outcome.wordResult?.definitions?.firstOrNull()
    val translation = outcome.translation?.takeIf(String::isNotBlank)
        ?: dictionaryFallback?.takeIf(String::isNotBlank)
    return ProcessTextTranslationPresentation(
        translation = translation ?: failureText,
        translationSucceeded = translation != null,
    )
}

internal fun processTextSpeechSettings(
    settings: com.gameocr.app.data.Settings,
    role: ProcessTextSpeechRole,
): com.gameocr.app.data.Settings = settings.copy(
    targetLang = when (role) {
        ProcessTextSpeechRole.SOURCE -> settings.sourceLang
        ProcessTextSpeechRole.TRANSLATION -> settings.targetLang
    },
)
