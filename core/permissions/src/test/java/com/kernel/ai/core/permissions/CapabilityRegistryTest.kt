package com.kernel.ai.core.permissions

import android.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityRegistryTest {

    @Test
    fun registryContainsExpectedCapabilityKeys() {
        assertEquals(
            CapabilityKey.entries.toSet(),
            CapabilityRegistry.all.map { it.key }.toSet(),
        )
    }

    @Test
    fun handsFreeCallingMapsToCallPhoneAndDialFallback() {
        val definition = CapabilityRegistry.require(CapabilityKey.HandsFreeCalling)

        assertTrue(definition.hasRuntimePermission(Manifest.permission.CALL_PHONE))
        assertTrue(definition.hasFallback(CapabilityFallbackAction.ActionDial))
        assertTrue(definition.fallbacks.any { fallback ->
            fallback.description.contains("ACTION_DIAL")
        })
    }

    @Test
    fun doNotDisturbUsesSpecialAccessNotRuntimePermission() {
        val definition = CapabilityRegistry.require(CapabilityKey.DoNotDisturbControl)

        assertTrue(definition.hasSpecialAccess(SpecialAccessKey.NotificationPolicyAccess))
        assertFalse(definition.hasAnyRuntimePermission())
    }

    @Test
    fun writeSettingsUsesSpecialAccessNotRuntimePermission() {
        val definition = CapabilityRegistry.require(CapabilityKey.ModifySystemSettings)

        assertTrue(definition.hasSpecialAccess(SpecialAccessKey.WriteSettings))
        assertFalse(definition.hasAnyRuntimePermission())
    }

    @Test
    fun defaultAssistantUsesAssistantRoleRequirement() {
        val definition = CapabilityRegistry.require(CapabilityKey.DefaultAssistant)

        assertTrue(definition.hasRole(RoleRequirementKey.Assistant))
    }

    @Test
    fun heyJandalDependsOnAssistantRoleAndMicrophoneReadiness() {
        val definition = CapabilityRegistry.require(CapabilityKey.HeyJandal)

        assertTrue(definition.hasRole(RoleRequirementKey.Assistant))
        assertTrue(definition.hasRuntimePermission(Manifest.permission.RECORD_AUDIO))
        assertTrue(definition.hasPlatformCapability(PlatformCapabilityKey.ForegroundMicrophoneService))
    }

    @Test
    fun jandalAlarmsTimersAreInternalPlatformReadinessNotClockHandoff() {
        val definition = CapabilityRegistry.require(CapabilityKey.JandalAlarmsTimers)

        assertTrue(definition.description.contains("Jandal-owned"))
        assertFalse(definition.description.contains("Android Clock", ignoreCase = true))
        assertTrue(definition.hasRuntimePermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(definition.hasPlatformCapability(PlatformCapabilityKey.NotificationsEnabled))
        assertTrue(definition.hasPlatformCapability(PlatformCapabilityKey.ExactAlarmScheduling))
        assertTrue(definition.hasPlatformCapability(PlatformCapabilityKey.FullScreenIntent))
        assertTrue(definition.hasPlatformCapability(PlatformCapabilityKey.BootRestore))
    }

    @Test
    fun weatherCurrentLocationHasLocationPermissionAndNamedProfileHomeFallbacks() {
        val definition = CapabilityRegistry.require(CapabilityKey.WeatherCurrentLocation)

        assertTrue(definition.hasRuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(definition.hasFallback(CapabilityFallbackAction.NamedLocation))
        assertTrue(definition.hasFallback(CapabilityFallbackAction.ProfileLocation))
        assertTrue(definition.hasFallback(CapabilityFallbackAction.HomeLocation))
    }

    @Test
    fun contactLookupHasContactsPermissionAndManualFallback() {
        val definition = CapabilityRegistry.require(CapabilityKey.ContactLookup)

        assertTrue(definition.hasRuntimePermission(Manifest.permission.READ_CONTACTS))
        assertTrue(definition.hasFallback(CapabilityFallbackAction.ManualPhoneOrEmailInput))
    }

    @Test
    fun calendarLookupHasCalendarPermissionAndManualImportantDateFallback() {
        val definition = CapabilityRegistry.require(CapabilityKey.CalendarLookup)

        assertTrue(definition.hasRuntimePermission(Manifest.permission.READ_CALENDAR))
        assertTrue(definition.hasFallback(CapabilityFallbackAction.ManualImportantDateEntry))
    }

    private fun CapabilityDefinition.hasAnyRuntimePermission(): Boolean =
        requirements.any { it is CapabilityRequirement.RuntimePermission }

    private fun CapabilityDefinition.hasRuntimePermission(permission: String): Boolean =
        requirements.any { requirement ->
            requirement is CapabilityRequirement.RuntimePermission && requirement.permission == permission
        }

    private fun CapabilityDefinition.hasSpecialAccess(key: SpecialAccessKey): Boolean =
        requirements.any { requirement ->
            requirement is CapabilityRequirement.SpecialAccess && requirement.key == key
        }

    private fun CapabilityDefinition.hasRole(key: RoleRequirementKey): Boolean =
        requirements.any { requirement ->
            requirement is CapabilityRequirement.Role && requirement.key == key
        }

    private fun CapabilityDefinition.hasPlatformCapability(key: PlatformCapabilityKey): Boolean =
        requirements.any { requirement ->
            requirement is CapabilityRequirement.PlatformCapability && requirement.key == key
        }

    private fun CapabilityDefinition.hasFallback(action: CapabilityFallbackAction): Boolean =
        fallbacks.any { it.action == action }
}
