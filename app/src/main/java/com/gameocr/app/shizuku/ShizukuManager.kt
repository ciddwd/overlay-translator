package com.gameocr.app.shizuku

import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Shizuku 权限与可用性管理。
 *
 * Shizuku 服务安装后通过 ADB / 无线调试启动，本 App 调用 [Shizuku.requestPermission] 申请使用其 IBinder。
 * 拿到权限后可以用 [Shizuku.newProcess] 在 shell uid 下执行 `screencap -p`，实现免 MediaProjection 弹窗截屏。
 */
@Singleton
class ShizukuManager @Inject constructor() {

    private val PERMISSION_REQUEST_CODE = 0xC4A

    private val _binderAlive = MutableStateFlow(safePingBinder())
    /**
     * Shizuku binder 是否仍存活。Shizuku 进程被杀 / 服务停了 / 没配对都会触发变化。
     * UI 应当 collect 这个 flow，binder 死了立即把"就绪"切回"未运行"。仅靠 lifecycle ON_RESUME
     * 探测会漏掉「app 一直前台但 Shizuku 被外部停了」的场景。
     */
    val binderAlive: StateFlow<Boolean> = _binderAlive.asStateFlow()

    private val _shellPrivilegeOk = MutableStateFlow(false)
    /**
     * Shizuku 是否拿到了 shell uid 特权（**已通过 ADB / root 配对启动**）。
     * `pingBinder()` 只能确认 Shizuku 进程在跑、IPC 通道在，**不能** 判定特权 session 是否建立。
     * 用户「没配对」时 pingBinder 返回 true，但 newProcess(screencap) 跑不动——只有跑一次
     * `id` 验证 uid=2000(shell) 才能确认。结果异步刷到这个 flow。
     */
    val shellPrivilegeOk: StateFlow<Boolean> = _shellPrivilegeOk.asStateFlow()

    private val verifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verifyMutex = Mutex()

    init {
        runCatching {
            Shizuku.addBinderReceivedListener {
                _binderAlive.value = true
                verifyScope.launch { verifyShellPrivilegeAsync() }
            }
            Shizuku.addBinderDeadListener {
                _binderAlive.value = false
                _shellPrivilegeOk.value = false
            }
        }
        if (_binderAlive.value) {
            verifyScope.launch { verifyShellPrivilegeAsync() }
        }
    }

    private fun safePingBinder(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    /**
     * 验证 Shizuku 是否真正建立了 shell 特权 session。跑 `id` 命令读 stdout，看 uid=2000(shell)。
     * 未配对（Shizuku 进程在但没 ADB / root 启动过）时拿不到 shell uid——这是 [pingBinder] 检测不到的情形。
     */
    suspend fun verifyShellPrivilegeAsync() = withContext(Dispatchers.IO) {
        verifyMutex.withLock {
            val ok = runCatching {
                val binderAlive = safePingBinder()
                _binderAlive.value = binderAlive
                if (!binderAlive) return@runCatching false
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return@runCatching false
                val process = invokeNewProcessReflective(arrayOf("id")) ?: return@runCatching false
                val inStream = process.javaClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
                val stdout = inStream.use { String(it.readBytes(), Charsets.US_ASCII) }
                val ec = runCatching {
                    process.javaClass.getMethod("waitFor").invoke(process) as Int
                }.getOrElse { -1 }
                val pass = ec == 0 && ("uid=2000" in stdout || "(shell)" in stdout || "uid=0" in stdout)
                if (!pass) Timber.w("[shizuku-verify] privilege check failed: exit=%d stdout=%s", ec, stdout.take(200))
                pass
            }.getOrElse { t ->
                Timber.w(t, "[shizuku-verify] verify threw")
                false
            }
            _shellPrivilegeOk.value = ok
        }
    }

    fun refreshShellPrivilege() {
        verifyScope.launch { verifyShellPrivilegeAsync() }
    }

    suspend fun ensureReady(): Boolean {
        if (!requestPermission()) return false
        verifyShellPrivilegeAsync()
        return shellPrivilegeOk.value
    }

    /** 反射调用 `Shizuku.newProcess`——同 [com.gameocr.app.capture.ShizukuScreenshotter.invokeNewProcess]。
     *  暂时各保留一份，免引入 helper 类。 */
    private fun invokeNewProcessReflective(cmd: Array<String>): Any? {
        val cls = Shizuku::class.java
        val method = runCatching {
            cls.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        }.getOrNull() ?: cls.declaredMethods.firstOrNull { it.name == "newProcess" } ?: return null
        method.isAccessible = true
        return method.invoke(null, cmd, null, null)
    }

    /** Shizuku 服务是否就绪（用户已通过 ADB / 无线调试启动）。 */
    fun isServiceRunning(): Boolean = safePingBinder()

    /** 当前是否已被 Shizuku 授予权限。 */
    fun hasPermission(): Boolean = try {
        if (!isServiceRunning()) false
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /**
     * 请求 Shizuku 权限。挂起到用户授权 / 拒绝。
     * 返回 true = 授权成功，false = 拒绝 / 未就绪。
     */
    suspend fun requestPermission(): Boolean {
        if (!isServiceRunning()) return false
        if (hasPermission()) return true
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Timber.w("Shizuku says we should explain — but we just ask anyway")
        }
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (t: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                cont.resume(false)
            }
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }
    }
}
