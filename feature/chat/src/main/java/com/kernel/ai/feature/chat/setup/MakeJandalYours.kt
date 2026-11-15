package com.kernel.ai.feature.chat.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kernel.ai.core.permissions.MicrophonePermissionReadiness
import com.kernel.ai.core.permissions.MicrophoneReadiness
import com.kernel.ai.core.permissions.canScheduleExactAlarms
import com.kernel.ai.core.voice.HeyJandalBlock
import com.kernel.ai.core.voice.HeyJandalGate
import com.kernel.ai.core.voice.WakeWordDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Live state shown on each "Make Jandal yours" row (#1855 part 3). */
enum class MakeYoursStatus { On, Off, NotAvailable }

/**
 * What this phone has right now. Read when the wizard loads and again on every return from a system page
 * (ON_RESUME), so each row shows the state the user just left behind.
 */
data class MakeYoursPlatform(
    val hasMicrophone: Boolean = false,
    val micGranted: Boolean = false,
    val wakeModelAvailable: Boolean = false,
    val usageAccessRequired: Boolean = false,
    val assistantRoleAvailable: Boolean = false,
    val assistantRoleHeld: Boolean = false,
    val notificationsGranted: Boolean = false,
    val exactAlarmAllowed: Boolean = false,
)

/** Pure mapping from platform and stored state to row statuses. No Android calls, so it is unit-tested. */
internal object MakeYoursRows {

    /** First prerequisite Hey Jandal is missing, from [HeyJandalGate.block]. Null when the wizard can turn it on. */
    fun heyJandalBlock(platform: MakeYoursPlatform): HeyJandalBlock? = HeyJandalGate.block(
        hasMicrophone = platform.hasMicrophone,
        micGranted = platform.micGranted,
        modelAvailable = platform.wakeModelAvailable,
        usageAccessRequired = platform.usageAccessRequired,
    )

    /**
     * Effective Hey Jandal state: On only when stored on and nothing blocks it. Not available when the
     * phone has no microphone or no wake-word model. Otherwise Off, with the block reason shown in the row.
     */
    fun wakeWord(storedEnabled: Boolean, platform: MakeYoursPlatform): MakeYoursStatus {
        val block = heyJandalBlock(platform)
        return when {
            block == HeyJandalBlock.NoMicrophone || block == HeyJandalBlock.ModelNotInstalled -> MakeYoursStatus.NotAvailable
            block == null && storedEnabled -> MakeYoursStatus.On
            else -> MakeYoursStatus.Off
        }
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
     * Plain reason Hey Jandal cannot listen right now, or null when it can. The default assistant is not a
     * blocker: Settings treats it as recommended only, and so does the wizard.
     */
    fun wakeWordBlockReason(platform: MakeYoursPlatform): String? = heyJandalBlock(platform)?.let(::blockText)

    private fun blockText(block: HeyJandalBlock): String = when (block) {
        HeyJandalBlock.NoMicrophone -> "This phone has no microphone, so Hey Jandal can't listen."
        HeyJandalBlock.MicrophoneNotGranted -> "Hey Jandal needs microphone access. Tap Allow microphone."
        HeyJandalBlock.ModelNotInstalled -> "The Hey Jandal model isn't on this phone yet, so it can't listen."
        HeyJandalBlock.UsageAccessNeeded ->
            "On this Honor phone, Hey Jandal needs Usage Access so it can pause while the camera is in use. " +
                "Tap Open Usage Access, turn it on for Jandal, then come back."
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
    private val wakeWordDetector: WakeWordDetector,
) {
    /** Reads on [Dispatchers.IO]: the wake-word check and permission queries are not main-thread work. */
    open suspend fun read(): MakeYoursPlatform = withContext(Dispatchers.IO) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        MakeYoursPlatform(
            hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            micGranted = MicrophonePermissionReadiness.evaluate(context) == MicrophoneReadiness.Granted,
            wakeModelAvailable = wakeWordDetector.isAvailable,
            usageAccessRequired = HeyJandalGate.usageAccessRequired(context),
            assistantRoleAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true,
            assistantRoleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
            exactAlarmAllowed = canScheduleExactAlarms(context),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
