package com.gameocr.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

internal data class ShizukuAvailabilitySnapshot(
    val knownBackendInstalled: Boolean,
    val shizukuPlusCompatibilityRequired: Boolean,
    val serviceRunning: Boolean,
    val permissionGranted: Boolean,
    val shellPrivilegeOk: Boolean,
)

internal enum class ShizukuBackendPackage(
    val packageName: String,
) {
    SHIZUKU("moe.shizuku.privileged.api"),
    LEGACY_SHIZUKU("moe.shizuku.api"),
    NIGHTZUKU("kerneldroid.nightzuku"),
    SHIZUKU_PLUS("af.shizuku.plus.api"),
}

internal data class InstalledShizukuBackends(
    val packages: Set<ShizukuBackendPackage>,
) {
    val anyKnownBackendInstalled: Boolean
        get() = packages.isNotEmpty()

    val shizukuPlusCompatibilityRequired: Boolean
        get() = ShizukuBackendPackage.SHIZUKU_PLUS in packages &&
            ShizukuBackendPackage.SHIZUKU !in packages &&
            ShizukuBackendPackage.LEGACY_SHIZUKU !in packages &&
            ShizukuBackendPackage.NIGHTZUKU !in packages
}

internal fun resolveInstalledShizukuBackends(
    installedPackageNames: Set<String>,
): InstalledShizukuBackends = InstalledShizukuBackends(
    packages = ShizukuBackendPackage.entries
        .filterTo(mutableSetOf()) { it.packageName in installedPackageNames },
)

internal fun resolveShizukuAvailability(
    snapshot: ShizukuAvailabilitySnapshot,
): ShizukuCapabilities.Availability = when {
    !snapshot.serviceRunning && snapshot.shizukuPlusCompatibilityRequired ->
        ShizukuCapabilities.Availability.INSTALLED_COMPATIBILITY_REQUIRED
    !snapshot.serviceRunning && snapshot.knownBackendInstalled ->
        ShizukuCapabilities.Availability.NOT_RUNNING
    !snapshot.serviceRunning -> ShizukuCapabilities.Availability.NOT_INSTALLED
    !snapshot.permissionGranted -> ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED
    !snapshot.shellPrivilegeOk -> ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED
    else -> ShizukuCapabilities.Availability.READY
}

internal fun shouldRefreshShizukuShellPrivilege(snapshot: ShizukuAvailabilitySnapshot): Boolean =
    snapshot.serviceRunning &&
        snapshot.permissionGranted &&
        !snapshot.shellPrivilegeOk

/** Resolves all supported Shizuku-compatible providers into one capability state. */
@Singleton
class ShizukuCapabilities @Inject constructor(
    private val manager: ShizukuManager,
) {

    internal fun installedBackends(context: Context): InstalledShizukuBackends =
        resolveInstalledShizukuBackends(
            ShizukuBackendPackage.entries
                .mapNotNullTo(mutableSetOf()) { backend ->
                    backend.packageName.takeIf { isPackageInstalled(context, it) }
                },
        )

    fun isShizukuInstalled(context: Context): Boolean =
        installedBackends(context).anyKnownBackendInstalled

    fun isShizukuReady(context: Context): Boolean =
        manager.isServiceRunning() && manager.hasPermission() && manager.shellPrivilegeOk.value

    enum class Availability {
        READY,
        INSTALLED_NOT_GRANTED,
        INSTALLED_NOT_PAIRED,
        INSTALLED_COMPATIBILITY_REQUIRED,
        NOT_INSTALLED,
        NOT_RUNNING,
    }

    fun availability(context: Context): Availability {
        // A live Binder is authoritative. Sui intentionally has no manager APK, and compatible
        // providers may use a different package name, so package discovery must never gate IPC.
        val installedBackends = installedBackends(context)
        val serviceRunning = manager.isServiceRunning()
        val permissionGranted = serviceRunning && manager.hasPermission()
        val snapshot = ShizukuAvailabilitySnapshot(
            knownBackendInstalled = installedBackends.anyKnownBackendInstalled,
            shizukuPlusCompatibilityRequired =
                installedBackends.shizukuPlusCompatibilityRequired,
            serviceRunning = serviceRunning,
            permissionGranted = permissionGranted,
            shellPrivilegeOk = permissionGranted && manager.shellPrivilegeOk.value,
        )
        if (shouldRefreshShizukuShellPrivilege(snapshot)) {
            manager.refreshShellPrivilege()
        }
        return resolveShizukuAvailability(snapshot)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }
}
