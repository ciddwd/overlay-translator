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
import kotlinx.coroutines.CancellationException
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
     * 已授权的 Shizuku 服务是否以 shell/root 身份运行。
     * `pingBinder()` 仅表示 IPC 通道可用；通过官方 getUid() 验证服务身份，
     * 不额外启动 shell 命令。实际截屏或设置权限仍需分别检查执行结果。
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
     * 检查 Binder、应用授权和服务 UID；不通过额外子进程探测。
     */
    suspend fun verifyShellPrivilegeAsync() = withContext(Dispatchers.IO) {
        verifyMutex.withLock {
            val ok = runCatching {
                val binderAlive = safePingBinder()
                _binderAlive.value = binderAlive
                if (!binderAlive) return@runCatching false
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return@runCatching false
                val uid = Shizuku.getUid()
                val pass = uid == 2000 || uid == 0
                if (!pass) Timber.w("[shizuku-verify] privilege check failed: uid=%d", uid)
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

    /**
     * Enables this app's SYSTEM_ALERT_WINDOW app-op with the already authorized shell identity.
     * The caller must still verify [android.provider.Settings.canDrawOverlays], because an OEM may
     * accept the command without making the public permission state effective.
     */
    suspend fun grantOverlayPermission(packageName: String, user: String = "current"): Boolean = withContext(Dispatchers.IO) {
        if (!isServiceRunning() || !hasPermission() || !shellPrivilegeOk.value) {
            return@withContext false
        }
        executePermissionCommand(overlayPermissionAppOpsCommand(packageName, user)) != null
    }

    /** Only settings get/put for this app's accessibility configuration; never log service lists. */
    internal suspend fun executeSettingsCommand(command: Array<String>): String? = withContext(Dispatchers.IO) {
        require(command.firstOrNull() == "settings")
        executePermissionCommand(command)
    }

    private suspend fun executePermissionCommand(command: Array<String>): String? {
        if (!isServiceRunning() || !hasPermission() || !shellPrivilegeOk.value) return null
        try {
            val process = invokeNewProcessReflective(command) as? Process ?: return null
            val result = runPermissionCommand(process)
            return when {
                result == null -> {
                    Timber.w("[shizuku-permissions] %s command timed out", command.first())
                    null
                }
                result.exitCode != 0 -> {
                    Timber.w("[shizuku-permissions] %s command failed exit=%d", command.first(), result.exitCode)
                    null
                }
                else -> result.output
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "[shizuku-permissions] %s command failed", command.first())
            return null
        }
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
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (t: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }
    }

}
