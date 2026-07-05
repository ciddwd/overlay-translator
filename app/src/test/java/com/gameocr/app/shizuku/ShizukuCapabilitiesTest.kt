package com.gameocr.app.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuCapabilitiesTest {

    @Test
    fun resolveShizukuAvailability_mapsSnapshotToUserVisibleState() {
        data class Case(
            val snapshot: ShizukuAvailabilitySnapshot,
            val expected: ShizukuCapabilities.Availability,
        )

        val cases = listOf(
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = false,
                    serviceRunning = false,
                    permissionGranted = false,
                    shellPrivilegeOk = false,
                ),
                ShizukuCapabilities.Availability.NOT_INSTALLED,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = false,
                    permissionGranted = false,
                    shellPrivilegeOk = false,
                ),
                ShizukuCapabilities.Availability.NOT_RUNNING,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = true,
                    permissionGranted = false,
                    shellPrivilegeOk = false,
                ),
                ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = false,
                ),
                ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = true,
                ),
                ShizukuCapabilities.Availability.READY,
            ),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, resolveShizukuAvailability(case.snapshot))
        }
    }

    @Test
    fun shouldRefreshShizukuShellPrivilege_onlyWhenPermissionIsGrantedButShellWasNotVerified() {
        data class Case(
            val snapshot: ShizukuAvailabilitySnapshot,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = false,
                    serviceRunning = false,
                    permissionGranted = false,
                    shellPrivilegeOk = false,
                ),
                false,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = false,
                    permissionGranted = false,
                    shellPrivilegeOk = false,
                ),
                false,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = true,
                    permissionGranted = false,
                    shellPrivilegeOk = false,
                ),
                false,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = false,
                ),
                true,
            ),
            Case(
                ShizukuAvailabilitySnapshot(
                    installed = true,
                    serviceRunning = true,
                    permissionGranted = true,
                    shellPrivilegeOk = true,
                ),
                false,
            ),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, shouldRefreshShizukuShellPrivilege(case.snapshot))
        }
    }
}
