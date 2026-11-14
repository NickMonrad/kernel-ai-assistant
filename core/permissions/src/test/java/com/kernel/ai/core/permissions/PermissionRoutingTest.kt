package com.kernel.ai.core.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionRoutingTest {

    @Test
    fun `granted runtime permission routes to Granted whatever the history`() {
        for (requested in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    PermissionRoute.Granted,
                    decidePermissionRoute(
                        isGranted = true,
                        isSpecialAccess = false,
                        wasRequestedBefore = requested,
                        shouldShowRationale = rationale,
                    ),
                )
            }
        }
    }

    @Test
    fun `special access always routes to its system page when not granted`() {
        for (requested in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                assertEquals(
                    PermissionRoute.SpecialAccess,
                    decidePermissionRoute(
                        isGranted = false,
                        isSpecialAccess = true,
                        wasRequestedBefore = requested,
                        shouldShowRationale = rationale,
                    ),
                )
            }
        }
    }

    @Test
    fun `never requested runtime permission is requested in app`() {
        // shouldShowRationale is false before the first request, so history must decide.
        assertEquals(
            PermissionRoute.RequestInApp,
            decidePermissionRoute(
                isGranted = false,
                isSpecialAccess = false,
                wasRequestedBefore = false,
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
                wasRequestedBefore = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `requested before with no rationale is permanently denied and opens app info`() {
        assertEquals(
            PermissionRoute.AppInfo,
            decidePermissionRoute(
                isGranted = false,
                isSpecialAccess = false,
                wasRequestedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }
}
