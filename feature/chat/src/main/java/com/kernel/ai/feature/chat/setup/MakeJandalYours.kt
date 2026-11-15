package com.kernel.ai.feature.chat.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kernel.ai.core.permissions.canScheduleExactAlarms
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Live state shown on each "Make Jandal yours" row (#1855 part 3). */
enum class MakeYoursStatus { On, Off, NotAvailable }

/**
 * What this phone has right now. Read when the wizard loads and again on every return from a system page
 * (ON_RESUME), so each row shows the state the user just left behind.
 */
data class MakeYoursPlatform(
    val hasMicrophone: Boolean = false,
    val micGranted: Boolean = false,
    val assistantRoleAvailable: Boolean = false,
    val assistantRoleHeld: Boolean = false,
    val notificationsGranted: Boolean = false,
    val exactAlarmAllowed: Boolean = false,
)

/** Pure mapping from platform and stored state to row statuses. No Android calls, so it is unit-tested. */
internal object MakeYoursRows {

    /** Hey Jandal is On only when stored on; Not available when this phone has no microphone. */
    fun wakeWord(storedEnabled: Boolean, platform: MakeYoursPlatform): MakeYoursStatus = when {
        !platform.hasMicrophone || !platform.assistantRoleAvailable -> MakeYoursStatus.NotAvailable
        storedEnabled -> MakeYoursStatus.On
        else -> MakeYoursStatus.Off
    }

    /** Default assistant: On when Jandal holds the role; Not available when the phone has no assistant role. */
    fun defaultAssistant(platform: MakeYoursPlatform): MakeYoursStatus = when {
        !platform.assistantRoleAvailable -> MakeYoursStatus.NotAvailable
        platform.assistantRoleHeld -> MakeYoursStatus.On
        else -> MakeYoursStatus.Off
    }

    fun notifications(platform: MakeYoursPlatform): MakeYoursStatus =
        if (platform.notificationsGranted) MakeYoursStatus.On else MakeYoursStatus.Off

    fun exactAlarm(platform: MakeYoursPlatform): MakeYoursStatus =
        if (platform.exactAlarmAllowed) MakeYoursStatus.On else MakeYoursStatus.Off

    /**
     * Why Hey Jandal cannot be turned on yet, or null when it can. The wake word needs the microphone and
     * Jandal as default assistant (the reliable background mic path, #1855).
     */
    fun wakeWordBlockReason(platform: MakeYoursPlatform): String? = when {
        !platform.hasMicrophone -> "This phone has no microphone, so Hey Jandal can't listen."
        !platform.micGranted -> "Needs microphone access first."
        !platform.assistantRoleHeld -> "Needs Jandal as your default assistant first. Set that in the row below."
        else -> null
    }

    fun statusLabel(status: MakeYoursStatus): String = when (status) {
        MakeYoursStatus.On -> "On"
        MakeYoursStatus.Off -> "Off"
        MakeYoursStatus.NotAvailable -> "Not available"
    }
}

/** Reads [MakeYoursPlatform] from the system. Open so the view model tests can mock it. */
open class MakeYoursPlatformReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open fun read(): MakeYoursPlatform {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return MakeYoursPlatform(
            hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            assistantRoleAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true,
            assistantRoleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
            exactAlarmAllowed = canScheduleExactAlarms(context),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
