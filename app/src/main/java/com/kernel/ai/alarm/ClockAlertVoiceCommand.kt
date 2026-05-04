package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType

internal enum class ClockAlertVoiceCommand {
    DISMISS,
    SNOOZE,
    ADD_ONE_MINUTE,
}

internal fun parseClockAlertVoiceCommand(raw: String): ClockAlertVoiceCommand? {
    val normalized = raw
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank()) return null
    return when {
        normalized == "stop" ||
            normalized == "dismiss" ||
            normalized == "stop alarm" ||
            normalized == "dismiss alarm" ||
            normalized == "stop timer" ||
            normalized == "dismiss timer" -> ClockAlertVoiceCommand.DISMISS

        normalized == "snooze" || normalized == "snooze alarm" -> ClockAlertVoiceCommand.SNOOZE

        normalized == "add one minute" ||
            normalized == "add a minute" ||
            normalized == "add 1 minute" ||
            normalized == "one more minute" ||
            normalized == "another minute" -> ClockAlertVoiceCommand.ADD_ONE_MINUTE

        else -> null
    }
}

internal fun alertVoiceListeningPrompt(type: ClockEventType): String =
    when (type) {
        ClockEventType.ALARM -> "Listening… Say stop, dismiss, snooze, or add one minute."
        ClockEventType.TIMER -> "Listening… Say stop, dismiss, or add one minute."
        ClockEventType.PRE_ALARM -> "Listening… Say stop or dismiss."
    }

internal fun alertVoiceUnsupportedMessage(
    command: ClockAlertVoiceCommand,
    type: ClockEventType,
): String? =
    when {
        command == ClockAlertVoiceCommand.SNOOZE && type != ClockEventType.ALARM ->
            "Snooze is only available for alarms."

        command == ClockAlertVoiceCommand.ADD_ONE_MINUTE && type == ClockEventType.PRE_ALARM ->
            "Add one minute is not available for alarm reminders."

        else -> null
    }
