package com.kernel.ai.core.permissions

import android.Manifest

/** User-facing Jandal capabilities that may require Android access or platform readiness. */
enum class CapabilityKey {
    VoiceInput,
    WeatherCurrentLocation,
    ContactLookup,
    HandsFreeCalling,
    CalendarLookup,
    DoNotDisturbControl,
    ModifySystemSettings,
    DefaultAssistant,
    HeyJandal,
    JandalAlarmsTimers,
}

/** Requirement categories stay separate so future UX can explain and repair each kind correctly. */
sealed interface CapabilityRequirement {
    data class RuntimePermission(val permission: String) : CapabilityRequirement
    data class SpecialAccess(val key: SpecialAccessKey) : CapabilityRequirement
    data class Role(val key: RoleRequirementKey) : CapabilityRequirement
    data class PlatformCapability(val key: PlatformCapabilityKey) : CapabilityRequirement
}

enum class SpecialAccessKey {
    NotificationPolicyAccess,
    WriteSettings,
}

enum class RoleRequirementKey {
    Assistant,
}

enum class PlatformCapabilityKey {
    NotificationsEnabled,
    ExactAlarmScheduling,
    FullScreenIntent,
    BootRestore,
    ForegroundMicrophoneService,
}

enum class CapabilityFallbackAction {
    TypedInput,
    NamedLocation,
    ProfileLocation,
    HomeLocation,
    ManualPhoneOrEmailInput,
    ActionDial,
    ManualImportantDateEntry,
    ExplainLimitation,
    ManualAppLaunch,
    DisabledUntilSetupComplete,
    DegradedAlarmTimerAlert,
}

data class CapabilityFallback(
    val action: CapabilityFallbackAction,
    val label: String,
    val description: String,
)

data class CapabilityDefinition(
    val key: CapabilityKey,
    val label: String,
    val description: String,
    val requirements: List<CapabilityRequirement>,
    val fallbacks: List<CapabilityFallback> = emptyList(),
)

sealed interface CapabilityStatus {
    data object Available : CapabilityStatus
    data class Missing(val requirements: List<CapabilityRequirement>) : CapabilityStatus
    data class Limited(val reason: String) : CapabilityStatus
}

interface CapabilityStatusChecker {
    fun statusFor(capability: CapabilityKey): CapabilityStatus
}

object CapabilityRegistry {
    private val definitions = listOf(
        CapabilityDefinition(
            key = CapabilityKey.VoiceInput,
            label = "Voice input",
            description = "Listen for a user voice command through Jandal's on-device voice path.",
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.RECORD_AUDIO),
                CapabilityRequirement.PlatformCapability(PlatformCapabilityKey.ForegroundMicrophoneService),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.TypedInput,
                    label = "Type instead",
                    description = "The user can enter the same request with typed input.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.WeatherCurrentLocation,
            label = "Weather for current location",
            description = "Answer local weather questions when the user has not named a place.",
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.NamedLocation,
                    label = "Ask for a place",
                    description = "Prompt the user to name a city, suburb, or region.",
                ),
                CapabilityFallback(
                    action = CapabilityFallbackAction.ProfileLocation,
                    label = "Use saved profile location",
                    description = "Use a location the user has saved in their Jandal profile.",
                ),
                CapabilityFallback(
                    action = CapabilityFallbackAction.HomeLocation,
                    label = "Use home location",
                    description = "Use a saved home location when one is available.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.ContactLookup,
            label = "Contact lookup",
            description = "Resolve people by name for calls, messages, email, or attendee lookup.",
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_CONTACTS),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.ManualPhoneOrEmailInput,
                    label = "Enter details manually",
                    description = "Ask the user for the phone number or email address instead of reading contacts.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.HandsFreeCalling,
            label = "Hands-free calling",
            description = "Place calls directly when the user explicitly asks Jandal to call someone.",
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.CALL_PHONE),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.ActionDial,
                    label = "Open dialer this time",
                    description = "Use ACTION_DIAL for this call without granting hands-free call permission.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.CalendarLookup,
            label = "Calendar lookup",
            description = "Read calendar-backed birthdays and important dates when the user enables that feature.",
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.READ_CALENDAR),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.ManualImportantDateEntry,
                    label = "Add important dates manually",
                    description = "Let the user save birthdays and important dates directly in Jandal.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.DoNotDisturbControl,
            label = "Do Not Disturb control",
            description = "Turn Android Do Not Disturb on or off after the user grants notification policy access.",
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessKey.NotificationPolicyAccess),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.ExplainLimitation,
                    label = "Explain limitation",
                    description = "Tell the user Android requires notification policy access before Jandal can control DND.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.ModifySystemSettings,
            label = "Modify system settings",
            description = "Change supported system settings such as screen brightness after write-settings access is granted.",
            requirements = listOf(
                CapabilityRequirement.SpecialAccess(SpecialAccessKey.WriteSettings),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.ExplainLimitation,
                    label = "Explain limitation",
                    description = "Tell the user Android requires special access before Jandal can change these settings.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.DefaultAssistant,
            label = "Default assistant",
            description = "Use Android's assistant role for hold-Home, side-key, and assistant activation paths.",
            requirements = listOf(
                CapabilityRequirement.Role(RoleRequirementKey.Assistant),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.ManualAppLaunch,
                    label = "Open Jandal manually",
                    description = "The user can launch Jandal directly when the assistant role is not configured.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.HeyJandal,
            label = "Hey Jandal",
            description = "Enable wake-word and assistant-style voice activation when role and microphone readiness are satisfied.",
            requirements = listOf(
                CapabilityRequirement.Role(RoleRequirementKey.Assistant),
                CapabilityRequirement.RuntimePermission(Manifest.permission.RECORD_AUDIO),
                CapabilityRequirement.PlatformCapability(PlatformCapabilityKey.ForegroundMicrophoneService),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.DisabledUntilSetupComplete,
                    label = "Keep disabled until setup is complete",
                    description = "Do not enable Hey Jandal until the assistant role and microphone path are ready.",
                ),
            ),
        ),
        CapabilityDefinition(
            key = CapabilityKey.JandalAlarmsTimers,
            label = "Jandal alarms, timers, and reminders",
            description = "Schedule and present Jandal-owned alarms, timers, reminders, and due-date alerts inside the app.",
            requirements = listOf(
                CapabilityRequirement.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS),
                CapabilityRequirement.PlatformCapability(PlatformCapabilityKey.NotificationsEnabled),
                CapabilityRequirement.PlatformCapability(PlatformCapabilityKey.ExactAlarmScheduling),
                CapabilityRequirement.PlatformCapability(PlatformCapabilityKey.FullScreenIntent),
                CapabilityRequirement.PlatformCapability(PlatformCapabilityKey.BootRestore),
            ),
            fallbacks = listOf(
                CapabilityFallback(
                    action = CapabilityFallbackAction.DegradedAlarmTimerAlert,
                    label = "Create with explicit degraded behaviour",
                    description = "Only proceed when Jandal can clearly explain any notification, exact-alarm, full-screen, or boot-restore limitation.",
                ),
            ),
        ),
    )

    val all: List<CapabilityDefinition> = definitions

    private val byKey: Map<CapabilityKey, CapabilityDefinition> = definitions.associateBy { it.key }

    fun byKey(key: CapabilityKey): CapabilityDefinition? = byKey[key]

    fun require(key: CapabilityKey): CapabilityDefinition =
        requireNotNull(byKey(key)) { "Missing capability definition for $key" }
}
