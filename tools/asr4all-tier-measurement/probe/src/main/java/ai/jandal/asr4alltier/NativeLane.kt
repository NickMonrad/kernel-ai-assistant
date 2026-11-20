package ai.jandal.asr4alltier

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android native recognition with the shipping intent (`NativeAndroidVoiceInputController.buildRecognizerIntent`:
 * free-form, partial results, prefer offline, max 1 result, device default language) and the shipping backend
 * choice (on-device recogniser when available, else the platform service), fed the clip's PCM through
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE` instead of the microphone (API 33+). The recogniser runs in another
 * process, so load time and memory are not attributable here.
 */
class NativeLane(private val context: Context, backend: String) : StreamingLane {
    private val main = Handler(Looper.getMainLooper())
    private val onDevice = when (backend) {
        "ondevice" -> true
        "platform" -> false
        else -> SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
    override val description = "android-native ${if (onDevice) "on-device" else "platform"} SpeechRecognizer (EXTRA_AUDIO_SOURCE)"
    override val inProcess: Boolean get() = false
    private var recognizer: SpeechRecognizer? = null
    private var writer: ParcelFileDescriptor.AutoCloseOutputStream? = null
    // The read end must stay open until the service has received it (startListening is asynchronous).
    private var readEnd: ParcelFileDescriptor? = null
    @Volatile private var partial = ""
    @Volatile private var final: String? = null
    private val segments = java.util.Collections.synchronizedList(ArrayList<String>())
    private val segmented = true
    @Volatile var error: Int? = null; private set
    @Volatile var endedBeforeEof = false; private set
    private var done = CountDownLatch(1)

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)) { "main thread blocked" }
    }

    override fun reset() {
        closeSession()
        partial = ""
        final = null
        segments.clear()
        error = null
        endedBeforeEof = false
        done = CountDownLatch(1)
        val pipe = ParcelFileDescriptor.createPipe()
        writer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            // With a file audio source the services tested return an empty onResults bundle; results arrive
            // only in segmented-session mode (onSegmentResults + onEndOfSegmentedSession). Deviation from the
            // shipping intent, required to feed file PCM at all.
            if (segmented) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        onMain {
            val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            r.setRecognitionListener(listener)
            r.startListening(intent)
            recognizer = r
        }
        readEnd = pipe[0]
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            this@NativeLane.error = error
            done.countDown()
        }
        override fun onResults(results: Bundle?) {
            if (debug) android.util.Log.i("ASR4ALL_TIER", "native onResults keys=${results?.keySet()} best=${best(results)}")
            final = best(results).orEmpty()
            done.countDown()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.takeIf { it.isNotEmpty() }?.let { partial = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onSegmentResults(segmentResults: Bundle) {
            if (debug) android.util.Log.i("ASR4ALL_TIER", "native onSegmentResults ${best(segmentResults)}")
            best(segmentResults)?.takeIf { it.isNotEmpty() }?.let { segments += it }
        }
        override fun onEndOfSegmentedSession() {
            if (debug) android.util.Log.i("ASR4ALL_TIER", "native onEndOfSegmentedSession ${segments.size}")
            final = segments.joinToString(" ")
            done.countDown()
        }
    }

    /** Public-clip debugging only: logs recogniser callbacks (never enabled for owner audio). */
    var debug = false

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    override fun feed(chunk: FloatArray): String {
        val w = writer
        if (w != null && done.count > 0) {
            val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in chunk) bytes.putShort((v * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            try { w.write(bytes.array()) } catch (_: IOException) { endedBeforeEof = true }
        } else if (done.count == 0L) {
            endedBeforeEof = true
        }
        return partial
    }

    override fun correctedPartial(): String = partial

    override fun finish(): Pair<String, String> {
        runCatching { writer?.close() }
        writer = null
        done.await(15, TimeUnit.SECONDS)
        val text = final ?: ""
        return text to text
    }

    /** `extractBestTranscript`: first result, trimmed. */
    override fun shippingPreNormalise(final: String): String = final.trim()

    private fun closeSession() {
        runCatching { writer?.close() }
        writer = null
        runCatching { readEnd?.close() }
        readEnd = null
        val r = recognizer ?: return
        recognizer = null
        onMain { runCatching { r.cancel() }; runCatching { r.destroy() } }
    }

    override fun close() = closeSession()
}
