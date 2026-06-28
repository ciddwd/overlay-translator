package com.gameocr.app.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val checker: UpdateChecker
) : ViewModel() {

    /** UI 一次性消费的状态。 */
    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class Loaded(val info: UpdateInfo) : State()
        data class Failed(val errorMessage: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 自动检查是否正在进行。仅用来驱动「主屏全屏 Loading」遮罩；手动检查走 [state]=Checking
     * 走「关于」卡内的按钮 spinner，不进遮罩，避免用户点过按钮还被遮一次。
     */
    private val _autoChecking = MutableStateFlow(false)
    val autoChecking: StateFlow<Boolean> = _autoChecking.asStateFlow()

    /** 用户主动检查（点按钮）：三态全显示（有新版 / 已最新 / 失败）。 */
    fun check() {
        if (_state.value is State.Checking) return
        _state.value = State.Checking
        viewModelScope.launch { doCheck(silent = false) }
    }

    /**
     * 自动检查：主屏一进就调，1h 限频 + 只在「有新版且非用户已跳过版本」时弹 dialog。
     * 已最新 / 失败 / 已跳过 静默，不打扰用户。期间 [autoChecking] = true 驱动主屏全屏 Loading
     * 遮罩；最少展示 [MIN_OVERLAY_MS] 避免请求秒回 loading 一闪而过用户看不到。
     */
    fun autoCheckIfDue() {
        if (_state.value !is State.Idle) return
        if (_autoChecking.value) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now - last < AUTO_INTERVAL_MS) return
        // 不管成功失败都更新时间戳，避免失败时（国内访问 api.github.com 不稳）频繁重试浪费 API 额度
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        _autoChecking.value = true
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                doCheck(silent = true)
            } finally {
                val elapsed = System.currentTimeMillis() - t0
                if (elapsed < MIN_OVERLAY_MS) kotlinx.coroutines.delay(MIN_OVERLAY_MS - elapsed)
                _autoChecking.value = false
            }
        }
    }

    /**
     * 用户在升级弹窗里点「跳过此版本」：记下 latestVersion，后续 autoCheckIfDue 拿到同一版本不再弹。
     * 用户主动 [check] 仍会显示（手动检查无视跳过列表，避免用户找不到入口）。
     */
    fun skipVersion(version: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIPPED_VERSION, version).apply()
        _state.value = State.Idle
    }

    private suspend fun doCheck(silent: Boolean) {
        val result = checker.checkLatest()
        _state.value = result.fold(
            onSuccess = { info ->
                val skipped = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SKIPPED_VERSION, null)
                when {
                    silent && info.hasUpdate && info.latestVersion == skipped -> State.Idle
                    info.hasUpdate || !silent -> State.Loaded(info)
                    else -> State.Idle
                }
            },
            onFailure = { t ->
                if (silent) State.Idle
                else State.Failed(t.message ?: t.javaClass.simpleName)
            }
        )
    }

    fun reset() {
        _state.value = State.Idle
    }

    companion object {
        private const val PREFS = "update_checker"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val AUTO_INTERVAL_MS = 60L * 60 * 1000
        // 自动检查 loading 遮罩最少展示时长（ms）：保证用户能瞥到「检查更新…」提示
        private const val MIN_OVERLAY_MS = 600L
    }
}
