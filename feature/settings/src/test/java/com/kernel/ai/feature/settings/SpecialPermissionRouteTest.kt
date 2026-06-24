package com.kernel.ai.feature.settings

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [specialPermissionRoute]. This function has no Android
 * framework dependency — it maps a permission string to a [SettingsRoute]
 * descriptor. The descriptor->Intent conversion is tested at the Android
 * boundary (on-device or via Robolectric in an integration test).
 */
class SpecialPermissionRouteTest {

    private val packageName = "com.kernel.ai.test"

    @Test
    fun `ACCESS_NOTIFICATION_POLICY routes to NotificationPolicy`() {
        val route = specialPermissionRoute(
            Manifest.permission.ACCESS_NOTIFICATION_POLICY, packageName,
        )

        assertEquals(SettingsRoute.NotificationPolicy, route)
    }

    @Test
    fun `WRITE_SETTINGS routes to WriteSettings with the given package`() {
        val route = specialPermissionRoute(
            Manifest.permission.WRITE_SETTINGS, packageName,
        )

        assertEquals(SettingsRoute.WriteSettings(packageName), route)
    }

    @Test
    fun `unknown permission returns null`() {
        val route = specialPermissionRoute("unknown.permission", packageName)

        assertNull(route)
    }
}
