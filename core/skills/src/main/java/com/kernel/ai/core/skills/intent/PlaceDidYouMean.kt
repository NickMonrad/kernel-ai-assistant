package com.kernel.ai.core.skills.intent

import android.content.Context
import com.kernel.ai.core.skills.QuickIntentRouter
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** Required gap between the NZ top and the runner-up (any competitor). */
    const val MARGIN = 0.05

    /** Silent re-asks before the question is dropped without acting. */
    const val SILENCE_BUDGET = 2

    const val NZ_ASSET = "place_dym/nz_places.txt"
    const val COMPETITOR_ASSET = "place_dym/competitor_places.txt"
}

/** The user's reply to a pending "Did you mean" question. */
enum class PlaceDidYouMeanAnswer { Yes, No, Other }

object PlaceDidYouMeanReply {
    private val YES = setOf("yes", "yeah", "yep", "yup", "correct", "that's right", "thats right", "sure", "right")
    private val NO = setOf("no", "nope", "nah", "wrong", "not that", "no that's wrong", "no thats wrong")

    fun interpret(reply: String): PlaceDidYouMeanAnswer {
        val norm = reply.trim().trimEnd('.', '!', '?').lowercase()
        return when (norm) {
            in YES -> PlaceDidYouMeanAnswer.Yes
            in NO -> PlaceDidYouMeanAnswer.No
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

/** Builds the suggested utterance by replacing the heard place. Null if the place is not in the text. */
fun suggestedUtterance(text: String, heardPlace: String, suggestedPlace: String): String? {
    val start = text.indexOf(heardPlace, ignoreCase = true)
    if (heardPlace.isBlank() || start < 0) return null
    return text.substring(0, start) + suggestedPlace + text.substring(start + heardPlace.length)
}

/**
 * The "Did you mean" question for [route] when it is a navigate_to destination or get_weather location
 * that is a near miss of an NZ place, else null. Blocking: call off the main thread. Nothing is rewritten.
 */
fun placeDidYouMeanQuestion(
    suggester: PlaceDidYouMeanSuggester,
    route: QuickIntentRouter.RouteResult,
    text: String,
): PendingPlaceDidYouMean? {
    val intent = (route as? QuickIntentRouter.RouteResult.RegexMatch)?.intent ?: return null
    val slot = when (intent.intentName) {
        "navigate_to" -> "destination"
        "get_weather" -> "location"
        else -> return null
    }
    val heard = intent.params[slot]?.takeIf { it.isNotBlank() } ?: return null
    val suggested = suggester.suggest(heard) ?: return null
    val suggestedText = suggestedUtterance(text, heard, suggested) ?: return null
    return PendingPlaceDidYouMean(originalText = text, suggestedText = suggestedText, suggestedPlace = suggested)
}

/** Suggests NZ places for near-miss heard names. Matcher is loaded once from assets, lazily. */
@Singleton
class PlaceDidYouMeanSuggester internal constructor(private val matcherProvider: () -> PlaceSlotMatcher) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ loadMatcher(context) })

    private val matcher: PlaceSlotMatcher by lazy(matcherProvider)

    /** The NZ place to ask about for [heardPlace], or null. Never a rewrite. Blocking: call off the main thread. */
    fun suggest(heardPlace: String): String? =
        matcher.suggest(
            heard = heardPlace,
            maxDistance = PlaceDidYouMeanConfig.MAX_DISTANCE,
            margin = PlaceDidYouMeanConfig.MARGIN,
        )?.name

    private companion object {
        fun loadMatcher(context: Context): PlaceSlotMatcher {
            val nz = readLines(context, PlaceDidYouMeanConfig.NZ_ASSET)
            val competitors = readLines(context, PlaceDidYouMeanConfig.COMPETITOR_ASSET)
            return PlaceSlotMatcher(nz, competitors)
        }

        fun readLines(context: Context, asset: String): List<String> =
            context.assets.open(asset).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
    }
}
