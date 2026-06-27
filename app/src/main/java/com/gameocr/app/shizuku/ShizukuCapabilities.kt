package com.gameocr.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 能力探测。把"包是否装、服务是否运行、是否已授权"三个状态合并成 [Availability]。
 */
@Singleton
class ShizukuCapabilities @Inject constructor(
    private val manager: ShizukuManager
) {

    fun isShizukuInstalled(context: Context): Boolean = listOf(
        "moe.shizuku.privileged.api",  // 当前 GitHub 版
        "moe.shizuku.api"              // 旧版
    ).any { pkg ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (t: Throwable) {
            false
        }
    }

    fun isShizukuReady(context: Context): Boolean =
        manager.isServiceRunning() && manager.hasPermission() && manager.shellPrivilegeOk.value

    /**
     * Shizuku 可用性：
     * - NOT_INSTALLED：根本没装
     * - NOT_RUNNING：装了但 Shizuku 进程没跑（binder 死）
     * - INSTALLED_NOT_GRANTED：跑着但屏译还没拿到 Shizuku 权限
     * - **INSTALLED_NOT_PAIRED**：权限有但**没经过 ADB / root 启动配对**——`pingBinder` 和
     *   `checkSelfPermission` 都通过，但 newProcess(screencap) 跑不动。CaptureService 走 Shizuku
     *   路径会立刻 screencap exit=1。
     * - READY：以上都通过
     */
    enum class Availability { READY, INSTALLED_NOT_GRANTED, INSTALLED_NOT_PAIRED, NOT_INSTALLED, NOT_RUNNING }

    fun availability(context: Context): Availability {
        if (!isShizukuInstalled(context)) return Availability.NOT_INSTALLED
        if (!manager.isServiceRunning()) return Availability.NOT_RUNNING
        if (!manager.hasPermission()) return Availability.INSTALLED_NOT_GRANTED
        if (!manager.shellPrivilegeOk.value) return Availability.INSTALLED_NOT_PAIRED
        return Availability.READY
    }
}
