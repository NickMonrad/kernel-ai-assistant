package com.kernel.ai.core.voice.kokoro

import com.kernel.ai.core.voice.VoiceOutputCopy
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/** Chat model state the speed check needs. The app maps ChatModelLoader's result to this, since core/voice cannot see core/memory. */
enum class KokoroChatModelState { READY, NO_MODEL, FAILED }

/** Progress of a speed check: [run] of [runs] passes started. */
data class KokoroCheckProgress(val run: Int, val runs: Int)

/** Outcome of the automatic check from setup (#2173). Shown once, on the chat screen or in Settings › Voice. */
sealed interface KokoroAutoCheckResult {
    /** Kokoro was selected with Emma. */
    data object Passed : KokoroAutoCheckResult

    /** The phone is too slow for Kokoro; the voice stays on [stayingOn]. */
    data class TooSlow(val measured: KokoroSpeedResult.Measured, val stayingOn: String) : KokoroAutoCheckResult

    /** Not enough free memory to load Kokoro; the voice stays on [stayingOn]. */
    data class MemoryShort(val unavailable: KokoroSpeedResult.Unavailable, val stayingOn: String) : KokoroAutoCheckResult

    /** The check could not run at all. */
    data class CannotRun(val unavailable: KokoroSpeedResult.Unavailable) : KokoroAutoCheckResult

    fun message(): String = when (this) {
        is Passed -> "Kokoro is on. This phone speaks it smoothly, so the voice is Emma."
        is TooSlow -> "This phone isn't fast enough for Kokoro; replies would stutter. Staying on $stayingOn."
        is MemoryShort -> "$KOKORO_LOW_MEMORY_MESSAGE Staying on $stayingOn."
        is CannotRun -> unavailable.message
    }
}

/** What an automatic run does with its measured result: whether to select Kokoro, and which result to show. */
internal data class KokoroAutoCheckDecision(val selectKokoro: Boolean, val result: KokoroAutoCheckResult?)

/**
 * Decides the automatic run's effect. A voice the user changed while the check ran stands: no selection, no result.
 */
internal fun decideKokoroAutoCheck(
    engineAtStart: VoiceOutputEngine,
    engineNow: VoiceOutputEngine,
    // Null: a pass cached from an earlier check on this build, so nothing was measured (#2174).
    measured: KokoroSpeedResult?,
): KokoroAutoCheckDecision {
    if (engineNow != engineAtStart) return KokoroAutoCheckDecision(selectKokoro = false, result = null)
    val stayingOn = VoiceOutputCopy.title(engineAtStart)
    val result = when (measured) {
        null -> KokoroAutoCheckResult.Passed
        is KokoroSpeedResult.Measured ->
            if (measured.allowsKokoro) KokoroAutoCheckResult.Passed
            else KokoroAutoCheckResult.TooSlow(measured, stayingOn)
        is KokoroSpeedResult.Unavailable ->
            if (measured.memoryShort) KokoroAutoCheckResult.MemoryShort(measured, stayingOn)
            else KokoroAutoCheckResult.CannotRun(measured)
    }
    return KokoroAutoCheckDecision(selectKokoro = result is KokoroAutoCheckResult.Passed, result = result)
}

/**
 * The once-only check that runs after a Kokoro pick in setup (#2173). It waits until the pack is on disk and the chat
 * model is ready, measures speed with no sample, then selects Kokoro with Emma on a pass and keeps the current voice
 * otherwise. The pending flag is cleared after the run, so it never repeats.
 */
@Singleton
class KokoroAutoCheck @Inject constructor(
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val kokoroVoiceOutputController: KokoroVoiceOutputController,
    private val cache: KokoroSpeedCheckCache,
) {
    private val _progress = MutableStateFlow<KokoroCheckProgress?>(null)
    val progress: StateFlow<KokoroCheckProgress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<KokoroAutoCheckResult?>(null)
    val result: StateFlow<KokoroAutoCheckResult?> = _result.asStateFlow()

    /** Returns the pending result and clears it, so only the first screen that shows it gets it. */
    fun takeResult(): KokoroAutoCheckResult? = _result.getAndUpdate { null }

    /**
     * Starts waiting for the pending check. [loadChatModel] is called once the conditions hold; the measurement waits
     * for [inferenceIdle], so a reply being generated does not skew the timing.
     */
    fun start(
        scope: CoroutineScope,
        chatModelReady: Flow<Boolean>,
        packReady: Flow<Boolean>,
        inferenceIdle: Flow<Boolean>,
        loadChatModel: suspend () -> KokoroChatModelState,
    ) {
        scope.launch {
            // A duplicate true (the pending flag written twice for one pick) runs once; a new pick comes after a false.
            combine(chatModelReady, packReady, voiceOutputPreferences.pendingKokoroCheck) { chat, pack, pending ->
                chat && pack && pending
            }
                .distinctUntilChanged()
                .filter { it }
                .collect { runOnce(loadChatModel, inferenceIdle) }
        }
    }

    internal suspend fun runOnce(
        loadChatModel: suspend () -> KokoroChatModelState,
        inferenceIdle: Flow<Boolean>,
    ): KokoroAutoCheckResult? {
        // Read again here: a Settings check or a plain engine choice clears the flag, and a stale pick must not measure (#2173).
        if (!voiceOutputPreferences.pendingKokoroCheck.first()) return null
        val engineAtStart = voiceOutputPreferences.selectedEngine.first()
        // A pass cached on this build, pack and threshold stands: no measurement, straight to Kokoro (#2174).
        val measured = if (cache.hasValidPass()) null else measureWithProgress(loadChatModel, inferenceIdle)
        // A measured result is a fact even if the user changed the voice meanwhile, so it is recorded regardless.
        when (measured) {
            null -> Unit
            is KokoroSpeedResult.Measured -> if (measured.allowsKokoro) cache.recordPass() else cache.recordNotPassed()
            is KokoroSpeedResult.Unavailable -> cache.recordNotPassed()
        }
        val engineNow = voiceOutputPreferences.selectedEngine.first()
        val decision = decideKokoroAutoCheck(engineAtStart, engineNow, measured)
        if (decision.selectKokoro) {
            voiceOutputPreferences.setSelectedKokoroVoice(KokoroVoice.EMMA)
            voiceOutputPreferences.setSelectedEngine(VoiceOutputEngine.KokoroExperimental)
        } else if (engineNow != VoiceOutputEngine.KokoroExperimental) {
            // Frees the engine the check loaded. A voice the user chose as Kokoro during the check keeps it loaded.
            kokoroVoiceOutputController.unloadEngine()
        }
        voiceOutputPreferences.setPendingKokoroCheck(false)
        decision.result?.let { shown -> _result.value = shown }
        return decision.result
    }

    private suspend fun measureWithProgress(
        loadChatModel: suspend () -> KokoroChatModelState,
        inferenceIdle: Flow<Boolean>,
    ): KokoroSpeedResult =
        try {
            // Checking from the start, so the chat-model load and the wait for idle show as checking too.
            _progress.value = KokoroCheckProgress(0, KOKORO_SPEED_TEST_RUNS)
            when (loadChatModel()) {
                KokoroChatModelState.READY -> {
                    inferenceIdle.first { it }
                    kokoroVoiceOutputController.measureSpeed { run, runs ->
                        _progress.value = KokoroCheckProgress(run, runs)
                    }
                }
                KokoroChatModelState.NO_MODEL -> KokoroSpeedResult.Unavailable(KOKORO_NO_CHAT_MODEL_MESSAGE)
                KokoroChatModelState.FAILED -> KokoroSpeedResult.Unavailable(KOKORO_CHECK_UNAVAILABLE_MESSAGE)
            }
        } finally {
            _progress.value = null
        }
}

/** Shown when the chat model is not downloaded, so the speed check cannot load it. */
const val KOKORO_NO_CHAT_MODEL_MESSAGE = "Kokoro can't be checked until a chat model is downloaded."

/** Shown when the check cannot prepare the Kokoro engine. */
const val KOKORO_CHECK_UNAVAILABLE_MESSAGE = "Couldn't prepare the check on this phone."
