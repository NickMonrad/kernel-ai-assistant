package com.kernel.ai.debug.inflect

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.voice.InflectMicroOnnxRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit synthetic runtime smoke test for the isolated Inflect application ID.
 * Requires model graphs acquired through the app's Settings → Voice → Inflect Micro download flow
 * and an existing app-private eSpeak data directory supplied as an instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class InflectSpikeReceiverRuntimeTest {
    @Test
    fun phonemizesAndSynthesizesSyntheticTextWithIsolatedApplicationStartup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Select only ${InflectProbeStartupIsolation.RUNTIME_TEST_CLASS} to run this probe",
            arguments.getString("class") == InflectProbeStartupIsolation.RUNTIME_TEST_CLASS,
        )

        val context = instrumentation.targetContext
        assertEquals(InflectProbeStartupIsolation.PROBE_PACKAGE, context.packageName)
        assertTrue(
            "The test-only startup mode must be enabled",
            com.kernel.ai.BuildConfig.IS_INFLECT_PROBE,
        )
        // Mirror the production model-directory resolution without calling modelDirectory(), which
        // creates the directory if absent. This test requires the models to pre-exist.
        val modelDirectory = context.getExternalFilesDir("models")
            ?: File(context.filesDir, "models")
        listOf(
            InflectMicroOnnxRunner.DURATION_MODEL_FILE,
            InflectMicroOnnxRunner.DECODE_MODEL_FILE,
        ).forEach { fileName ->
            val modelFile = File(modelDirectory, fileName)
            assertTrue("Missing pre-existing model file ${modelFile.path}", modelFile.isFile)
            assertTrue("Empty model file ${modelFile.path}", modelFile.length() > 0L)
        }
        val dataDirectory = arguments.getString(InflectSpikeReceiver.EXTRA_DATA_DIRECTORY)
        assertFalse(
            "An existing app-private eSpeak data directory is required",
            dataDirectory.isNullOrBlank(),
        )
        assertTrue("Missing eSpeak directory $dataDirectory", File(dataDirectory!!).isDirectory)

        System.loadLibrary("onnxruntime")
        val label = "runtime-test-${System.nanoTime()}"
        val intent = Intent(context, InflectSpikeReceiver::class.java)
            .setAction(InflectSpikeReceiver.ACTION_RUN_RUNTIME_PROBE)
            .putExtra(InflectSpikeReceiver.EXTRA_TEXT, "Hello from Jandal")
            .putExtra(InflectSpikeReceiver.EXTRA_DATA_DIRECTORY, dataDirectory)
            .putExtra(InflectSpikeReceiver.EXTRA_LABEL, label)
        val latch = CountDownLatch(1)
        val resultCode = AtomicInteger(Int.MIN_VALUE)
        val resultData = AtomicReference<String?>()
        context.sendOrderedBroadcast(
            intent,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    resultCode.set(getResultCode())
                    resultData.set(getResultData())
                    latch.countDown()
                }
            },
            Handler(Looper.getMainLooper()),
            Int.MIN_VALUE,
            null,
            null,
        )
        assertTrue(
            "Inflect runtime probe timed out",
            latch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertEquals("Inflect receiver did not complete successfully", RESULT_OK, resultCode.get())

        val summaryFile = File(resultData.get() ?: error("Receiver returned no evidence path"))
        assertTrue("Missing synthetic probe summary ${summaryFile.path}", summaryFile.isFile)
        val summary = JSONObject(summaryFile.readText())
        assertFalse(
            "Receiver returned no phonemes",
            summary.getString("frontend_phonemes").isBlank(),
        )
        assertTrue("Waveform contains non-finite samples", summary.getBoolean("finite"))
        assertTrue("No waveform samples were generated", summary.getInt("waveform_samples") > 0)
        assertTrue("Audio duration is not positive", summary.getLong("audio_duration_ms") > 0L)
        val wavFile = File(summary.getString("wav_file"))
        assertTrue("Missing synthetic probe WAV ${wavFile.path}", wavFile.isFile)
        assertTrue("Synthetic probe WAV is empty", wavFile.length() > 0L)
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 90L
        const val RESULT_OK = 0
    }
}
