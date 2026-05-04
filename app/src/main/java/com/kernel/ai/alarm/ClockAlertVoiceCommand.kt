package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType

internal enum class ClockAlertVoiceCommand {
    DISMISS,
    SNOOZE,
    ADD_ONE_MINUTE,
}

private val dismissCommandPhrases = listOf(
    listOf("stop"),
    listOf("dismiss"),
    listOf("cancel"),
    listOf("stop", "alarm"),
    listOf("dismiss", "alarm"),
    listOf("cancel", "alarm"),
    listOf("stop", "timer"),
    listOf("dismiss", "timer"),
    listOf("cancel", "timer"),
)

private val snoozeCommandPhrases = listOf(
    listOf("snooze"),
    listOf("snooze", "alarm"),
)

private val addOneMinuteCommandPhrases = listOf(
    listOf("add", "one", "minute"),
    listOf("add", "a", "minute"),
    listOf("add", "1", "minute"),
    listOf("one", "more", "minute"),
    listOf("another", "minute"),
)

private val alertCommandPhrases = listOf(
    ClockAlertVoiceCommand.DISMISS to dismissCommandPhrases,
    ClockAlertVoiceCommand.SNOOZE to snoozeCommandPhrases,
    ClockAlertVoiceCommand.ADD_ONE_MINUTE to addOneMinuteCommandPhrases,
)

internal fun parseClockAlertVoiceCommand(raw: String): ClockAlertVoiceCommand? {
    val tokens = raw
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .split(" ")
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null

    return alertCommandPhrases
        .flatMap { (command, phrases) ->
            phrases.mapNotNull { phrase ->
                findPhraseStart(tokens, phrase)?.let { start ->
                    MatchedAlertVoiceCommand(command = command, startIndex = start, phraseLength = phrase.size)
                }
            }
        }
        .maxWithOrNull(compareBy<MatchedAlertVoiceCommand>({ it.startIndex }, { it.phraseLength }))
        ?.command
}

private data class MatchedAlertVoiceCommand(
    val command: ClockAlertVoiceCommand,
    val startIndex: Int,
    val phraseLength: Int,
    )

private fun findPhraseStart(tokens: List<String>, phrase: List<String>): Int? {
    if (phrase.size > tokens.size) return null
    for (startIndex in 0..tokens.size - phrase.size) {
        if (tokens.subList(startIndex, startIndex + phrase.size) == phrase) {
            return startIndex
        }
    }
    return null
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
