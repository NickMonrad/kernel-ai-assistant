package com.kernel.ai.core.skills.intent

import android.content.Context
import android.util.Log
import com.kernel.ai.core.skills.QuickIntentRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #2079 experimental "Did you mean <place>?" for navigation and weather place slots.
 *
 * Never rewrites silently: [PlaceDidYouMeanSuggester.suggest] returns a question target only, and the
 * caller acts on it only after an explicit yes. Thresholds are the offline sweep's frozen config
 * (`tools/place-did-you-mean-offline/RESULTS.md`).
 */
internal object PlaceDidYouMeanConfig {
    /** Max normalised distance for a suggestion. */
    const val MAX_DISTANCE = 0.30

    /** Longest the matcher may take before the question is dropped and the normal path runs. */
    const val TIMEOUT_MS = 3_000L

    /** Required gap between the NZ top and the runner-up (any competitor). */
    const val MARGIN = 0.05

    /** Silent re-asks before the question is dropped without acting. */
    const val SILENCE_BUDGET = 2

    const val NZ_ASSET = "place_dym/nz_places.txt"
    const val COMPETITOR_ASSET = "place_dym/competitor_places.txt"
    const val LINZ_ASSET = "place_dym/linz_localities.txt"
}

/** The user's reply to a pending "Did you mean" question. */
enum class PlaceDidYouMeanAnswer { Yes, No, Other }

object PlaceDidYouMeanReply {
    /** Yes and no use the shared affirmation and negation sets, so "ok", "okay" and "yes please" are yes. */
    fun interpret(reply: String): PlaceDidYouMeanAnswer {
        val norm = reply.trim().trimEnd('.', '!', '?').lowercase()
        return when {
            norm in QuickIntentRouter.AFFIRMATIONS -> PlaceDidYouMeanAnswer.Yes
            norm in QuickIntentRouter.NEGATIONS -> PlaceDidYouMeanAnswer.No
            else -> PlaceDidYouMeanAnswer.Other
        }
    }
}

/**
 * Pending question state. [suggestedText] is the user's utterance with the heard place replaced by
 * [suggestedPlace]; [originalText] is what was heard. [silentRetries] counts voice silences since the
 * question was asked; once it reaches [PlaceDidYouMeanConfig.SILENCE_BUDGET] the question is dropped.
 */
data class PendingPlaceDidYouMean(
    val originalText: String,
    val suggestedText: String,
    val suggestedPlace: String,
    val silentRetries: Int = 0,
) {
    val prompt: String get() = "Did you mean $suggestedPlace?"
}

/** An NZ place to offer, and the span of the heard place it replaces (indices into the heard place). */
data class PlaceSuggestion(val place: String, val heardRange: IntRange)

/**
 * Builds the suggested utterance by replacing only the heard place token with [suggestion]'s place, so the
 * rest of the phrase (for example a street number or street type) is kept. Null if the heard place is not in the text.
 */
fun suggestedUtterance(text: String, heardPlace: String, suggestion: PlaceSuggestion): String? {
    val start = text.indexOf(heardPlace, ignoreCase = true)
    if (heardPlace.isBlank() || start < 0) return null
    val place = heardPlace.replaceRange(suggestion.heardRange.first, suggestion.heardRange.last + 1, suggestion.place)
    return text.substring(0, start) + place + text.substring(start + heardPlace.length)
}

/** The heard place of a navigate_to destination or get_weather location, or null for any other route. */
private fun heardPlaceFor(route: QuickIntentRouter.RouteResult): String? {
    val intent = when (route) {
        is QuickIntentRouter.RouteResult.RegexMatch -> route.intent
        is QuickIntentRouter.RouteResult.ClassifierMatch -> route.intent
        else -> return null
    }
    val slot = when (intent.intentName) {
        "navigate_to" -> "destination"
        "get_weather" -> "location"
        else -> return null
    }
    return intent.params[slot]?.takeIf { it.isNotBlank() }
}

/**
 * The "Did you mean" question for [route] when it is a navigate_to destination or get_weather location
 * that is a near miss of an NZ place, else null. Covers regex and classifier matches alike (#2079: a
 * classifier-routed navigate_to used to skip the question). Blocking: call off the main thread. Nothing is rewritten.
 */
fun placeDidYouMeanQuestion(
    suggester: PlaceDidYouMeanSuggester,
    route: QuickIntentRouter.RouteResult,
    text: String,
): PendingPlaceDidYouMean? {
    val heard = heardPlaceFor(route) ?: return null
    val suggestion = suggester.suggest(heard) ?: return null
    val suggestedText = suggestedUtterance(text, heard, suggestion) ?: return null
    return PendingPlaceDidYouMean(originalText = text, suggestedText = suggestedText, suggestedPlace = suggestion.place)
}

/**
 * The matcher is a blocking computation that cannot be interrupted, so it runs in a scope the caller does
 * not wait on. A timed-out or abandoned match finishes in the background and its result is dropped.
 */
private val matcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * [placeDidYouMeanQuestion] on Dispatchers.Default, bounded by [timeoutMs] of real time. A matcher that
 * throws or runs past the bound gives null, so the caller takes the normal path and never goes silent.
 * The bound runs on the Default dispatcher's clock, so it is real time regardless of the caller.
 */
suspend fun placeDidYouMeanQuestionWithin(
    suggester: PlaceDidYouMeanSuggester,
    route: QuickIntentRouter.RouteResult,
    text: String,
    timeoutMs: Long = PlaceDidYouMeanConfig.TIMEOUT_MS,
): PendingPlaceDidYouMean? {
    // Only a navigate or weather route with a place starts the matcher; any other route never waits on it.
    heardPlaceFor(route) ?: return null
    return withContext(Dispatchers.Default) {
        // A failure is returned inside the Result: nothing is thrown into matcherScope.
        val pending = matcherScope.async { runCatching { placeDidYouMeanQuestion(suggester, route, text) } }
        try {
            val result = withTimeoutOrNull(timeoutMs) { pending.await() }
            if (result == null) {
                Log.w(TAG, "Did-you-mean matcher gave no result within ${timeoutMs}ms; normal path")
                null
            } else {
                result.onFailure { Log.w(TAG, "Did-you-mean matcher failed; normal path", it) }.getOrNull()
            }
        } finally {
            pending.cancel()
        }
    }
}

private const val TAG = "KernelAI"

/** Suggests NZ places for near-miss heard names. Matcher is loaded once from assets, lazily. */
@Singleton
class PlaceDidYouMeanSuggester internal constructor(private val matcherProvider: () -> PlaceSlotMatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ loadMatcher(context) })

    private val matcher: PlaceSlotMatcher by lazy(matcherProvider)

    /**
     * The NZ place to ask about for [heardPlace], with the span of [heardPlace] it replaces, or null. Never a
     * rewrite. Blocking: call off the main thread.
     */
    fun suggest(heardPlace: String): PlaceSuggestion? =
        matcher.suggest(
            heard = heardPlace,
            maxDistance = PlaceDidYouMeanConfig.MAX_DISTANCE,
            margin = PlaceDidYouMeanConfig.MARGIN,
        )?.let { PlaceSuggestion(place = it.name, heardRange = it.heardRange) }

    private companion object {
        fun loadMatcher(context: Context): PlaceSlotMatcher {
            val nz = readLines(context, PlaceDidYouMeanConfig.NZ_ASSET)
            val competitors = readLines(context, PlaceDidYouMeanConfig.COMPETITOR_ASSET)
            val known = readLines(context, PlaceDidYouMeanConfig.LINZ_ASSET)
            return PlaceSlotMatcher(nz, competitors, known)
        }

        fun readLines(context: Context, asset: String): List<String> =
            context.assets.open(asset).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
    }
}
