package com.kernel.ai.core.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionRoutingTest {

    @Test
    fun `granted runtime permission routes to Granted whatever the history`() {
        for (denied in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    PermissionRoute.Granted,
                    decidePermissionRoute(
                        isGranted = true,
                        isSpecialAccess = false,
                        wasDeniedBefore = denied,
                        shouldShowRationale = rationale,
                    ),
                )
            }
        }
    }

    @Test
    fun `special access always routes to its system page when not granted`() {
        for (denied in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    PermissionRoute.SpecialAccess,
                    decidePermissionRoute(
                        isGranted = false,
                        isSpecialAccess = true,
                        wasDeniedBefore = denied,
                        shouldShowRationale = rationale,
                    ),
                )
            }
        }
    }

    @Test
    fun `never denied runtime permission is requested in app`() {
        assertEquals(
            PermissionRoute.RequestInApp,
            decidePermissionRoute(
                isGranted = false,
                isSpecialAccess = false,
                wasDeniedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `dismissed dialog only (no denial recorded, no rationale) is requested in app again`() {
        assertEquals(
            PermissionRoute.RequestInApp,
            decidePermissionRoute(
                isGranted = false,
                isSpecialAccess = false,
                wasDeniedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `denied once with rationale still available is requested in app again`() {
        assertEquals(
            PermissionRoute.RequestInApp,
            decidePermissionRoute(
                isGranted = false,
                isSpecialAccess = false,
                wasDeniedBefore = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `denied before with no rationale is permanently denied and opens app info`() {
        assertEquals(
            PermissionRoute.AppInfo,
            decidePermissionRoute(
                isGranted = false,
                isSpecialAccess = false,
                wasDeniedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }
}
