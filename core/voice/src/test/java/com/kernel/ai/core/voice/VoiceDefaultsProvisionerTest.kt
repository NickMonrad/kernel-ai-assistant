package com.kernel.ai.core.voice

import android.content.Context
import android.content.SharedPreferences
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.terms.TERMS_VERSION
import com.kernel.ai.core.inference.terms.TermsAcceptancePreferences
import com.kernel.ai.core.inference.terms.TermsAcceptanceRecord
import com.kernel.ai.core.voice.VoiceDefaultsProvisioner.Companion.planSpeechOutputVoice
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceDefaultsProvisionerTest {
    @Test
    fun `updated install without a stored engine keeps Android TTS and starts nothing`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = false,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(true, plan.keepAndroidTts)
        assertEquals(false, plan.startDownload)
    }

    @Test
    fun `fresh install whose effective engine is Piper starts the missing voice`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = false,
            isExistingInstall = false,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(true, plan.startDownload)
    }

    @Test
    fun `effective Android TTS never starts the Piper voice`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = false,
            effectiveEngine = VoiceOutputEngine.AndroidTts,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.startDownload)
    }

    @Test
    fun `stored Piper with the voice already downloaded starts nothing`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = true,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(false, plan.startDownload)
    }

    @Test
    fun `stored Piper with a missing voice starts it even on an updated install`() {
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(true, plan.startDownload)
    }

    @Test
    fun `engine picked during the consent wait is not overwritten by the updated-install default`() {
        // The caller reads hasStoredEngine after the wait, so a Piper pick made during it is stored by then.
        val plan = planSpeechOutputVoice(
            hasStoredEngine = true,
            isExistingInstall = true,
            effectiveEngine = VoiceOutputEngine.SherpaExperimental,
            voiceDownloaded = false,
        )

        assertEquals(false, plan.keepAndroidTts)
        assertEquals(true, plan.startDownload)
    }

    @Test
    fun `asr4all waits for the Terms and queues once they are accepted`() = runBlocking {
        val harness = ProvisionerHarness(MutableStateFlow(TermsAcceptanceRecord()))
        val queue = launch { harness.provisioner.queueSpeechInputModel() }
        yield()
        assertFalse(queue.isCompleted)
        assertEquals(emptyList<KernelModel>(), harness.downloads)
        assertFalse(harness.markerSet)

        harness.terms.value = TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION, acceptedAtMillis = 1L)
        queue.join()
        assertEquals(Asr4AllInstall.models(Asr4AllSize.M).toSet(), harness.downloads.toSet())
        assertTrue(harness.markerSet)
    }

    @Test
    fun `engine picked during the Terms wait decides what is queued`() = runBlocking {
        val harness = ProvisionerHarness(MutableStateFlow(TermsAcceptanceRecord()))
        val queue = launch { harness.provisioner.queueSpeechInputModel() }
        yield()
        harness.engine.value = VoiceInputEngine.AndroidNative
        harness.terms.value = TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION, acceptedAtMillis = 1L)
        queue.join()
        assertEquals(emptyList<KernelModel>(), harness.downloads)
        assertFalse(harness.markerSet)
    }

    @Test
    fun `queueSpeechInput queues no asr4all file while the Terms are unaccepted`() = runBlocking {
        val harness = ProvisionerHarness(MutableStateFlow(TermsAcceptanceRecord()))
        harness.provisioner.queueSpeechInput(VoiceInputEngine.Auto)
        assertEquals(emptyList<KernelModel>(), harness.downloads)
    }

    @Test
    fun `queueSpeechInput queues asr4all once the Terms are accepted`() = runBlocking {
        val harness = ProvisionerHarness(MutableStateFlow(TermsAcceptanceRecord(TERMS_VERSION, 1L)))
        harness.provisioner.queueSpeechInput(VoiceInputEngine.Auto)
        assertEquals(Asr4AllInstall.models(Asr4AllSize.M).toSet(), harness.downloads.toSet())
    }
}

private const val ASR4ALL_MARKER = "asr4all_queued_v1"

/** Provisioner with mocked preferences and downloads; [terms] drives the Terms gate. */
private class ProvisionerHarness(
    val terms: MutableStateFlow<TermsAcceptanceRecord>,
    val engine: MutableStateFlow<VoiceInputEngine> = MutableStateFlow(VoiceInputEngine.Auto),
) {
    val downloads = mutableListOf<KernelModel>()
    var markerSet = false
    val provisioner: VoiceDefaultsProvisioner

    init {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            if (firstArg<String>() == ASR4ALL_MARKER) markerSet = true
            editor
        }
        val preferences = mockk<SharedPreferences>(relaxed = true)
        every { preferences.edit() } returns editor
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { context.getExternalFilesDir(any()) } returns createTempDirectory().toFile().apply { deleteOnExit() }
        val modelDownloads = mockk<ModelDownloadManager>(relaxed = true)
        every { modelDownloads.startDownload(any(), any(), any(), any()) } answers {
            downloads.add(firstArg())
            Unit
        }
        val voiceInput = mockk<VoiceInputPreferences>(relaxed = true) {
            every { selectedEngine } returns engine
            every { asr4allSize } returns MutableStateFlow(Asr4AllSize.M)
        }
        val detector = mockk<HardwareProfileDetector>(relaxed = true) {
            every { profile.tier } returns HardwareTier.MID_RANGE
        }
        val acceptance = mockk<TermsAcceptancePreferences>(relaxed = true) {
            every { record } returns terms
        }
        provisioner = VoiceDefaultsProvisioner(
            context = context,
            voiceInputPreferences = voiceInput,
            voiceOutputPreferences = mockk(relaxed = true),
            modelDownloadManager = modelDownloads,
            voicePackDownloadManager = mockk(relaxed = true),
            downloadNetworkPreferences = mockk(relaxed = true),
            hardwareProfileDetector = detector,
            termsAcceptance = acceptance,
        )
    }
}
