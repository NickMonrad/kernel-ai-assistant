package com.kernel.ai.debug.verifier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kernel.ai.assistant.verifyWakeWindow
import com.kernel.ai.core.voice.VoiceInputController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking

/**
 * Debug-only direct verifier probe (#1439 bounded validation).
 *
 * Exercises the REAL production low-band verification path
 * (`verifyWakeWindow` → selectable facade → `transcribeBlocking` → on-device
 * Whisper verifier → `containsWakePhrase`) against a PCM fixture WAV, exactly
 * like the detector's 3 s ring snapshot hand-off.
 *
 * Invocation (explicit component only, never implicit):
 *
 *     adb shell am broadcast \
 *       -a com.kernel.ai.debug.action.VERIFY_WAKE_WINDOW \
 *       -n com.kernel.ai.debug/com.kernel.ai.debug.verifier.WakeVerifierProbeReceiver \
 *       --es fixture_file probe_ring.wav [--ei runs 3]
 *
 * Fixtures live in app-private `files/acoustic-fixtures/` (16 kHz mono int16
 * WAV — the detector ring sample format). Result data carries the per-run
 * verdicts, e.g. `accept,accept,accept` or `reject,reject,reject`.
 *
 * Debug builds only — the receiver exists in `app/src/debug` and is never
 * part of a release APK.
 */
class WakeVerifierProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                if (!isExplicitReceiverInvocation(appContext, intent)) {
                    finish(pendingResult, "explicit_component_required")
                    return@Thread
                }
                val fileName = intent.getStringExtra(EXTRA_FIXTURE_FILE)
                if (fileName.isNullOrBlank() || fileName.contains("..") || fileName.contains('/')) {
                    finish(pendingResult, "invalid_fixture_file")
                    return@Thread
                }
                val runs = intent.getIntExtra(EXTRA_RUNS, 3).coerceIn(1, 5)
                val fixture = File(appContext.filesDir, "acoustic-fixtures/$fileName")
                val pcm = readWav16kMono(fixture)
                if (pcm == null) {
                    finish(pendingResult, "fixture_unreadable")
                    return@Thread
                }
                val controller = EntryPointAccessors.fromApplication(
                    appContext,
                    WakeVerifierProbeEntryPoint::class.java,
                ).voiceInputController()
                val outcomes = ArrayList<String>(runs)
                for (i in 1..runs) {
                    val accepted = runBlocking { verifyWakeWindow(controller, pcm) }
                    outcomes.add(if (accepted) "accept" else "reject")
                    Log.i(TAG, "wake-verifier probe $fileName run $i/$runs -> ${outcomes.last()}")
                }
                val summary = outcomes.joinToString(",")
                Log.i(TAG, "wake-verifier probe $fileName ($runs runs): $summary")
                pendingResult.setResultCode(android.app.Activity.RESULT_OK)
                pendingResult.setResultData(summary)
                pendingResult.finish()
            } catch (error: Exception) {
                Log.e(TAG, "wake-verifier probe failed", error)
                finish(pendingResult, "probe_failed")
            }
        }.start()
    }

    private fun finish(pendingResult: PendingResult, data: String) {
        pendingResult.setResultCode(android.app.Activity.RESULT_CANCELED)
        pendingResult.setResultData(data)
        pendingResult.finish()
    }

    private fun isExplicitReceiverInvocation(context: Context, intent: Intent): Boolean =
        intent.component?.packageName == context.packageName &&
            intent.component?.className == WakeVerifierProbeReceiver::class.java.name

    /** Reads a 16 kHz mono int16 WAV into [ShortArray] (the ring snapshot format). */
    private fun readWav16kMono(file: File): ShortArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(4)
                raf.readFully(riff)
                if (String(riff) != "RIFF") return null
                raf.readInt() // chunk size
                raf.readFully(riff)
                if (String(riff) != "WAVE") return null
                var channels = -1
                var sampleRate = -1
                var bits = -1
                var dataOffset = -1L
                var dataLen = 0
                while (raf.filePointer < raf.length()) {
                    val id = ByteArray(4)
                    raf.readFully(id)
                    val size = raf.readInt()
                    when (String(id)) {
                        "fmt " -> {
                            val fmt = ByteArray(size.coerceAtMost(64))
                            raf.readFully(fmt)
                            channels = (fmt[2].toInt() and 0xff) or ((fmt[3].toInt() and 0xff) shl 8)
                            sampleRate = (fmt[4].toInt() and 0xff) or ((fmt[5].toInt() and 0xff) shl 8) or
                                ((fmt[6].toInt() and 0xff) shl 16) or ((fmt[7].toInt() and 0xff) shl 24)
                            bits = (fmt[14].toInt() and 0xff) or ((fmt[15].toInt() and 0xff) shl 8)
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataLen = size
                        }
                        else -> raf.skipBytes(size)
                    }
                }
                if (channels != 1 || sampleRate != 16000 || bits != 16 || dataOffset < 0) {
                    Log.w(TAG, "unsupported fixture format: ch=$channels sr=$sampleRate bits=$bits")
                    return null
                }
                val samples = ByteArray(dataLen)
                raf.seek(dataOffset)
                raf.readFully(samples)
                ShortArray(dataLen / 2) { i ->
                    val lo = samples[i * 2].toInt() and 0xff
                    val hi = samples[i * 2 + 1].toInt()
                    ((hi shl 8) or lo).toShort()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "WakeVerifierProbe"
        const val ACTION_VERIFY_WAKE_WINDOW = "com.kernel.ai.debug.action.VERIFY_WAKE_WINDOW"
        const val EXTRA_FIXTURE_FILE = "fixture_file"
        const val EXTRA_RUNS = "runs"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WakeVerifierProbeEntryPoint {
    fun voiceInputController(): VoiceInputController
}
