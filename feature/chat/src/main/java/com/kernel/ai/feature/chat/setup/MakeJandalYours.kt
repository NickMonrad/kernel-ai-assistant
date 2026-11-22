package com.kernel.ai.feature.chat.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kernel.ai.core.ui.ChipStatus
import com.kernel.ai.core.permissions.MicrophonePermissionReadiness
import com.kernel.ai.core.permissions.MicrophoneReadiness
import com.kernel.ai.feature.chat.formatBytesShort
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

/** Button a Hey Jandal card offers; the screen maps each one to its callback. */
internal enum class HeyJandalAction { TurnOn, TurnOff, AllowMicrophone, OpenUsageAccess }

/**
 * What the Hey Jandal card shows. [why] is non-null only when the card has no secondary button, which is when
 * "Why?" is offered (screens 4 and 4b in the README).
 */
internal data class HeyJandalCard(
    val status: ChipStatus,
    val description: String,
    val caveat: String?,
    val primary: HeyJandalAction?,
    val secondary: HeyJandalAction?,
    val why: String?,
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
     * The Hey Jandal card for [choice] (what Finish will apply) against [stored], by the step 4 state table in
     * docs/specs/setup-visual-pass/README.md. [extraBytes] is the download this phone still needs.
     */
    fun heyJandalCard(
        stored: Boolean,
        choice: Boolean,
        platform: MakeYoursPlatform,
        extraBytes: Long,
    ): HeyJandalCard {
        val pending = choice != stored
        val base = "Say \"Hey Jandal\" to start talking, hands-free."
        val sizeLine = if (extraBytes > 0L) {
            "Adds a ${formatBytesShort(extraBytes)} download. Uses a little battery."
        } else {
            "Uses a little battery."
        }
        val status: ChipStatus
        val description: String
        val caveat: String?
        val primary: HeyJandalAction?
        val secondary: HeyJandalAction?
        when (val block = heyJandalBlock(platform)) {
            null -> {
                caveat = sizeLine
                when {
                    choice && pending -> {
                        status = ChipStatus.OnAtFinish
                        description = "$base Turns on when you tap Finish."
                        primary = null
                        secondary = HeyJandalAction.TurnOff
                    }
                    choice -> {
                        status = ChipStatus.On
                        description = base
                        primary = null
                        secondary = HeyJandalAction.TurnOff
                    }
                    pending -> {
                        status = ChipStatus.Off
                        description = "$base Turns off when you tap Finish."
                        primary = HeyJandalAction.TurnOn
                        secondary = null
                    }
                    else -> {
                        status = ChipStatus.Off
                        description = base
                        primary = HeyJandalAction.TurnOn
                        secondary = null
                    }
                }
            }
            HeyJandalBlock.NoMicrophone, HeyJandalBlock.ModelNotInstalled -> {
                status = ChipStatus.NotAvailable
                description = blockText(block)
                caveat = if (choice) STAYS_OFF else null
                primary = null
                secondary = if (choice) HeyJandalAction.TurnOff else null
            }
            HeyJandalBlock.MicrophoneNotGranted, HeyJandalBlock.UsageAccessNeeded -> {
                status = if (choice) ChipStatus.NeedsAccess else ChipStatus.Off
                description = base
                caveat = if (choice) "${blockText(block)} $STAYS_OFF" else blockText(block)
                primary = if (block == HeyJandalBlock.MicrophoneNotGranted) {
                    HeyJandalAction.AllowMicrophone
                } else {
                    HeyJandalAction.OpenUsageAccess
                }
                secondary = if (choice) HeyJandalAction.TurnOff else null
            }
        }
        return HeyJandalCard(
            status = status,
            description = description,
            caveat = caveat,
            primary = primary,
            secondary = secondary,
            why = if (secondary == null) heyJandalWhy() else null,
        )
    }

    private const val STAYS_OFF = "Stays off until this is fixed."

    private fun heyJandalWhy(): String =
        "Say \"Hey Jandal\" and Jandal starts listening, hands-free. Whisper tiny.en verifies the wake phrase. " +
            "The mic stays on for the wake word, so it uses a little battery. It works most reliably once Jandal is " +
            "your default assistant, which is the next row."

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

    private fun blockText(block: HeyJandalBlock): String = when (block) {
        HeyJandalBlock.NoMicrophone -> "This phone has no microphone, so Hey Jandal can't listen."
        HeyJandalBlock.MicrophoneNotGranted -> "Hey Jandal needs microphone access."
        HeyJandalBlock.ModelNotInstalled -> "The Hey Jandal model isn't on this phone yet, so it can't listen."
        HeyJandalBlock.UsageAccessNeeded ->
            "On this phone it needs Usage Access, so it can pause while the camera's in use."
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
