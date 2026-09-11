package com.gameocr.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 全进程内 CaptureService 运行状态。UI（MainScreen）观察这个 StateFlow 显示运行中/未运行，
 * 并据此 enable/disable 启动/停止按钮，避免用户重复点。磁贴也读它 —— 同进程，免费。
 */
object CaptureServiceState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 服务实例在「未处于运行状态」时结束的累计次数。只有两种情形会 +1：启动尝试失败、以及一次
     * 正常停止 —— 对观察者来说两者的答案都是「没起来」。单调递增且从不重置：磁贴比较自己点击
     * 前后的取值来判断这次启动有没有结果，不会被上一轮遗留的取值骗到。
     */
    private val _stoppedWithoutRunning = MutableStateFlow(0)
    val stoppedWithoutRunning: StateFlow<Int> = _stoppedWithoutRunning.asStateFlow()

    internal fun setRunning(v: Boolean) {
        _running.value = v
    }

    internal fun signalStoppedWithoutRunning() {
        // 用 update 而非 `value += 1`：读-改-写丢计数，而这里的承诺是「从不丢失」。
        _stoppedWithoutRunning.update { it + 1 }
    }
}
