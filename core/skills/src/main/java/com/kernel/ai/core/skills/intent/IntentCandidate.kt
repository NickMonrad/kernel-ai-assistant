package com.kernel.ai.core.skills.intent

/**
 * Represents a candidate intent recovered from [QuickIntentRouter.RouteResult.FallThrough.bestGuess].
 *
 * @property intentName The canonical intent name (e.g. "create_calendar_event").
 * @property confidence The classifier confidence score from the FallThrough.
 * @property source The source of the guess ("classifier" or "regex").
 */
data class IntentCandidate(
    val intentName: String,
    val confidence: Float,
    val source: String,
)
