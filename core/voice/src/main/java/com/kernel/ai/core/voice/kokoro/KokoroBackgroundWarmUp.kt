package com.kernel.ai.core.voice.kokoro

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loads the Kokoro engine in the background once the chat model is ready, and again whenever Kokoro becomes the active
 * voice or its voice changes, so the first Kokoro reply does not pay the engine load (S23U cold after launch: 2.6 s of
 * 4.45 s). Runs only while Kokoro is the selected engine and spoken responses are on, with memory headroom (a loaded
 * engine adds about 0.75 GB PSS, S23U round 4). Selecting another engine or turning spoken responses off releases the
 * loaded engine. A failed warm-up is logged; the reply path falls back as before.
 */
@Singleton
class KokoroBackgroundWarmUp @Inject constructor(
    private val voiceOutputPreferences: VoiceOutputPreferences,
    private val memoryGate: KokoroWarmUpMemoryGate,
    private val loadMarker: KokoroLoadMarker,
    private val kokoro: KokoroVoiceOutputController,
) {
    /** Waits for [chatModelReady], so the chat model's load is not competing with the Kokoro load, then follows settings. */
    fun start(scope: CoroutineScope, chatModelReady: Flow<Boolean>) {
        scope.launch {
            chatModelReady.first { ready -> ready }
            combine(
                voiceOutputPreferences.selectedEngine,
                voiceOutputPreferences.spokenResponsesEnabled,
                voiceOutputPreferences.selectedKokoroVoice,
            ) { engine, spokenEnabled, voice -> Request(engine, spokenEnabled, voice) }
                .distinctUntilChanged()
                .collect { request -> warmIfAllowed(request) }
        }
    }

    private suspend fun warmIfAllowed(request: Request) {
        if (request.engine != VoiceOutputEngine.KokoroExperimental) {
            kokoro.unloadEngine()
            return
        }
        if (!request.spokenEnabled) {
            kokoro.unloadEngine()
            Log.i(TAG, "kokoro background warm skipped: spoken responses off")
            return
        }
        if (loadMarker.unfinishedAtLaunch) {
            Log.w(TAG, "kokoro background warm skipped: previous native load did not finish")
            return
        }
        if (!memoryGate.hasHeadroom()) {
            Log.i(TAG, "kokoro background warm skipped: low memory")
            return
        }
        val started = System.currentTimeMillis()
        val result = try {
            kokoro.warmUp()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "kokoro background warm failed", error)
            return
        }
        Log.i(
            TAG,
            "kokoro background warm voice=${request.voice.name} ok=${result == VoiceOutputResult.Spoken} " +
                "ms=${System.currentTimeMillis() - started}",
        )
    }

    private data class Request(
        val engine: VoiceOutputEngine,
        val spokenEnabled: Boolean,
        val voice: KokoroVoice,
    )

    private companion object {
        const val TAG = "VoiceTiming"
    }
}

/** Reads the memory state that decides whether a background warm-up may load a ~0.75 GB engine. */
@Singleton
class KokoroWarmUpMemoryGate @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasHeadroom(): Boolean {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return kokoroWarmUpHasHeadroom(info.lowMemory, info.availMem)
    }
}

/** Available memory required before a background warm-up: the engine's footprint plus about 1 GiB of margin. */
internal const val KOKORO_WARM_UP_MIN_AVAILABLE_BYTES = 2L * 1024 * 1024 * 1024

internal fun kokoroWarmUpHasHeadroom(lowMemory: Boolean, availableBytes: Long): Boolean =
    !lowMemory && availableBytes >= KOKORO_WARM_UP_MIN_AVAILABLE_BYTES
