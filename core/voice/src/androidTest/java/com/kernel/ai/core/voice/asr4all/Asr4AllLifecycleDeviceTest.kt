package com.kernel.ai.core.voice.asr4all

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repeated open / reset / finish / close cycles on the real M export (#2004 AC4): no use after
 * close, no native program left mapped after close, and process PSS within
 * [PSS_GROWTH_BOUND_KB] of the level after the first cycle.
 *
 * Each cycle opens the program (mmap, XNNPACK delegate init, one run of each method), runs three
 * utterances ended by [Asr4AllEngine.reset] and one ended by [Asr4AllEngine.finish], then
 * closes. After close, the `.pte` must no longer be mapped into the process: a program that is
 * not released stays mapped. That check is needed in addition to PSS because a leaked program's
 * PSS cost is mostly paid once. In a local run with `close()` deliberately not releasing the
 * program, PSS rose by about 46 MB after the first leak and then stayed flat.
 */
@RunWith(AndroidJUnit4::class)
class Asr4AllLifecycleDeviceTest {
    @Test
    fun openResetCloseCyclesDoNotLeakOrUseAfterClose() {
        val dir = Asr4AllDeviceFiles.modelDir(Asr4AllSize.M)
        val pte = File(dir, "${Asr4AllModelConfig.EXPORT_DIR}/asr_encoder.pte")
        val pcm = Asr4AllDeviceFiles.clip("1272-128104-0000.wav")
        // 3 s of the clip per utterance keeps each cycle short.
        val utterance = pcm.copyOf(48_000)

        val samples = ArrayList<Long>()
        var reference: String? = null
        repeat(CYCLES + 1) { cycle ->
            val engine = Asr4AllEngine.open(dir, Asr4AllSize.M)
            assertTrue("program not mapped while open", mappings(pte) > 0)
            repeat(3) {
                Asr4AllDeviceFiles.feed(engine, utterance)
                assertTrue("no partial before reset", engine.partialText.isNotEmpty())
                engine.reset()
                assertEquals("", engine.partialText)
            }
            engine.acceptPcm16(utterance)
            val final = engine.finish()
            if (reference == null) reference = final else assertEquals("cycle $cycle output changed", reference, final)
            engine.close()

            assertThrows(Asr4AllClosedException::class.java) { engine.acceptPcm16(utterance) }
            assertThrows(Asr4AllClosedException::class.java) { engine.finish() }
            assertThrows(Asr4AllClosedException::class.java) { engine.reset() }
            assertEquals("cycle $cycle: program still mapped after close", 0, mappings(pte))

            val pss = pssKb()
            samples += pss
            Log.i(TAG, "cycle $cycle pss_kb=$pss native_heap_kb=${Debug.getNativeHeapAllocatedSize() / 1024}")
        }

        val baseline = samples.first()
        val peak = samples.drop(1).max()
        Log.i(TAG, "baseline_kb=$baseline peak_kb=$peak last_kb=${samples.last()} bound_kb=$PSS_GROWTH_BOUND_KB samples=$samples")
        assertTrue(
            "PSS grew from $baseline KB to $peak KB over $CYCLES cycles (bound $PSS_GROWTH_BOUND_KB KB): $samples",
            peak - baseline <= PSS_GROWTH_BOUND_KB,
        )
    }

    /** Mappings of [file] in this process (matched by its last path components: the FUSE path varies). */
    private fun mappings(file: File): Int {
        val suffix = file.path.substringAfter("/files/")
        return File("/proc/self/maps").readLines().count { it.endsWith(suffix) }
    }

    private fun pssKb(): Long {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
        return Debug.getPss()
    }

    private companion object {
        const val TAG = "Asr4AllLifecycleTest"
        const val CYCLES = 20

        /** Allowed PSS growth over the level after the first cycle: 24 MB. */
        const val PSS_GROWTH_BOUND_KB = 24L * 1024
    }
}
