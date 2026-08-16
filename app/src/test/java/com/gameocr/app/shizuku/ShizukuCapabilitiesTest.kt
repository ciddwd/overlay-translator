package com.gameocr.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuCapabilitiesTest {

    @Test
    fun resolveShizukuAvailability_mapsSnapshotToUserVisibleState_tableDriven() {
        data class Case(
            val name: String,
            val snapshot: ShizukuAvailabilitySnapshot,
            val expected: ShizukuCapabilities.Availability,
        )

        val cases = listOf(
            Case("nothing installed", snapshot(), ShizukuCapabilities.Availability.NOT_INSTALLED),
            Case(
                "known manager stopped",
                snapshot(knownBackendInstalled = true),
                ShizukuCapabilities.Availability.NOT_RUNNING,
            ),
            Case(
                "running without grant",
                snapshot(
                    knownBackendInstalled = true,
                    serviceRunning = true,
                ),
                ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED,
            ),
            Case(
                "granted without verified privilege",
                snapshot(
                    knownBackendInstalled = true,
                    serviceRunning = true,
                    permissionGranted = true,
                ),
                ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED,
            ),
            Case(
                "known manager ready",
                snapshot(
                    knownBackendInstalled = true,
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = true,
                ),
                ShizukuCapabilities.Availability.READY,
            ),
            Case(
                "Sui running without manager package",
                snapshot(serviceRunning = true),
                ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED,
            ),
            Case(
                "Sui ready without manager package",
                snapshot(
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = true,
                ),
                ShizukuCapabilities.Availability.READY,
            ),
            Case(
                "Shizuku Plus needs Compat Hub",
                snapshot(
                    knownBackendInstalled = true,
                    shizukuPlusCompatibilityRequired = true,
                ),
                ShizukuCapabilities.Availability.INSTALLED_COMPATIBILITY_REQUIRED,
            ),
            Case(
                "live binder overrides Shizuku Plus package guidance",
                snapshot(
                    knownBackendInstalled = true,
                    shizukuPlusCompatibilityRequired = true,
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = true,
                ),
                ShizukuCapabilities.Availability.READY,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolveShizukuAvailability(case.snapshot),
            )
        }
    }

    @Test
    fun shouldRefreshShizukuShellPrivilege_onlyForGrantedLiveBinder_tableDriven() {
        data class Case(
            val name: String,
            val snapshot: ShizukuAvailabilitySnapshot,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("nothing installed", snapshot(), false),
            Case("manager stopped", snapshot(knownBackendInstalled = true), false),
            Case(
                "binder alive without grant",
                snapshot(serviceRunning = true),
                false,
            ),
            Case(
                "known manager granted and unverified",
                snapshot(
                    knownBackendInstalled = true,
                    serviceRunning = true,
                    permissionGranted = true,
                ),
                true,
            ),
            Case(
                "Sui granted and unverified",
                snapshot(
                    serviceRunning = true,
                    permissionGranted = true,
                ),
                true,
            ),
            Case(
                "already verified",
                snapshot(
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = true,
                ),
                false,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldRefreshShizukuShellPrivilege(case.snapshot),
            )
        }
    }

    @Test
    fun resolveInstalledShizukuBackends_recognizesSupportedPackages_tableDriven() {
        data class Case(
            val name: String,
            val installedPackages: Set<String>,
            val expectedBackends: Set<ShizukuBackendPackage>,
            val expectedCompatibilityRequired: Boolean,
        )

        val cases = listOf(
            Case("none", emptySet(), emptySet(), false),
            Case(
                "official",
                setOf("moe.shizuku.privileged.api"),
                setOf(ShizukuBackendPackage.SHIZUKU),
                false,
            ),
            Case(
                "legacy official",
                setOf("moe.shizuku.api"),
                setOf(ShizukuBackendPackage.LEGACY_SHIZUKU),
                false,
            ),
            Case(
                "Nightzuku",
                setOf("kerneldroid.nightzuku"),
                setOf(ShizukuBackendPackage.NIGHTZUKU),
                false,
            ),
            Case(
                "Shizuku Plus without Compat Hub",
                setOf("af.shizuku.plus.api"),
                setOf(ShizukuBackendPackage.SHIZUKU_PLUS),
                true,
            ),
            Case(
                "Shizuku Plus with Compat Hub",
                setOf("af.shizuku.plus.api", "moe.shizuku.privileged.api"),
                setOf(ShizukuBackendPackage.SHIZUKU_PLUS, ShizukuBackendPackage.SHIZUKU),
                false,
            ),
            Case(
                "multiple compatible managers",
                setOf(
                    "moe.shizuku.privileged.api",
                    "kerneldroid.nightzuku",
                    "af.shizuku.plus.api",
                ),
                setOf(
                    ShizukuBackendPackage.SHIZUKU,
                    ShizukuBackendPackage.NIGHTZUKU,
                    ShizukuBackendPackage.SHIZUKU_PLUS,
                ),
                false,
            ),
            Case("unrelated package", setOf("com.example.shizuku.toolbox"), emptySet(), false),
        )

        cases.forEach { case ->
            val result = resolveInstalledShizukuBackends(case.installedPackages)
            assertEquals(case.name, case.expectedBackends, result.packages)
            assertEquals(
                case.name,
                case.expectedCompatibilityRequired,
                result.shizukuPlusCompatibilityRequired,
            )
        }
    }

    private fun snapshot(
        knownBackendInstalled: Boolean = false,
        shizukuPlusCompatibilityRequired: Boolean = false,
        serviceRunning: Boolean = false,
        permissionGranted: Boolean = false,
        shellPrivilegeOk: Boolean = false,
    ) = ShizukuAvailabilitySnapshot(
        knownBackendInstalled = knownBackendInstalled,
        shizukuPlusCompatibilityRequired = shizukuPlusCompatibilityRequired,
        serviceRunning = serviceRunning,
        permissionGranted = permissionGranted,
        shellPrivilegeOk = shellPrivilegeOk,
    )
}
