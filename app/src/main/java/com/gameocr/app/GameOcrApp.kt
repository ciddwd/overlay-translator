package com.gameocr.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.gameocr.app.data.CrashRecorder
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.di.PrivateCleartextInterceptor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class GameOcrApp : Application(), Configuration.Provider {

    @Inject lateinit var logRepository: LogRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var cleartextInterceptor: PrivateCleartextInterceptor
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Application-scope 协程容器。Activity / Service 销毁不取消，可承接「Activity 提交任务后立刻 finish」
     * 这类不依赖宿主生命周期的后台工作（如 [com.gameocr.app.translate.ProcessTextTranslateActivity]
     * 在 ACTION_PROCESS_TEXT 回调里跑翻译 → 弹 overlay 卡片）。
     */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 仅 debug 包 plant DebugTree。release 包不打 logcat —— 避免线上日志泄漏；
        // 用户能在 app 内日志页（LogRepository）看到 OCR / 翻译关键信息已经够诊断。
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 全局未捕获异常 → 写 <filesDir>/crash/*.crash 文件。先装再读，确保 onCreate 内部
        // 后续逻辑或子模块初始化崩溃也能被记到。
        CrashRecorder.install(this)
        // 上次启动遗留的 crash 文件 + API 30+ 的 ExitReasons（native/ANR/OOM）一并加载到
        // LogRepository，让用户在日志页就能看到上次为什么挂的。
        CrashRecorder.loadPendingCrashes(this, logRepository)
        CrashRecorder.loadExitReasons(this, logRepository)
        // 持续把脱敏后的 settings 快照塞给 CrashRecorder，crash 时同步读这个内存值即可，
        // 避免 crash handler 走 DataStore IO 二次 crash。
        appScope.launch {
            runCatching { settingsRepository.migratePlaintextSecretsIfNeeded() }
                .onSuccess { migrated ->
                    if (migrated > 0) {
                        Timber.i("Encrypted $migrated legacy settings values")
                    }
                }
                .onFailure { Timber.w(it, "Failed to migrate legacy settings secrets") }
            runCatching { settingsRepository.migrateTextOrientationAutoDetectDefaultOnIfNeeded() }
                .onSuccess { changed ->
                    if (changed) {
                        Timber.i("Enabled text orientation auto-detect for bundled orientation model default")
                    }
                }
                .onFailure { Timber.w(it, "Failed to migrate text orientation auto-detect default") }
            runCatching { settingsRepository.migrateRetiredMangaOcrAdvancedSettingsIfNeeded() }
                .onSuccess { changed ->
                    if (changed) {
                        Timber.i("Reset retired Manga OCR advanced settings to zero")
                    }
                }
                .onFailure { Timber.w(it, "Failed to reset retired Manga OCR advanced settings") }
            settingsRepository.settings.collect { settings ->
                CrashRecorder.updateSettingsSummary(CrashRecorder.formatSettings(settings))
                cleartextInterceptor.allowedHosts = settings.cleartextAllowedHosts.toSet()
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
