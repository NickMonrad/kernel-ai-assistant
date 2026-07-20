package com.kernel.ai.core.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToneStartListeningCuePlayerTest {

    @Test
    fun `FOREGROUND maps to STREAM_MUSIC`() {
        assertEquals(AudioManager.STREAM_MUSIC, streamForCueContext(StartListeningCueContext.FOREGROUND))
    }

    @Test
    fun `WAKE_WORD maps to STREAM_ALARM`() {
        assertEquals(AudioManager.STREAM_ALARM, streamForCueContext(StartListeningCueContext.WAKE_WORD))
    }

    @Test
    fun `CLOCK_ALERT maps to STREAM_ALARM`() {
        assertEquals(AudioManager.STREAM_ALARM, streamForCueContext(StartListeningCueContext.CLOCK_ALERT))
    }

    @Test
    fun `empty device types returns unknown`() {
        assertEquals("unknown", classifyRoute(emptySet()))
    }

    @Test
    fun `only built-in speaker returns built_in_speaker`() {
        assertEquals("built_in_speaker", classifyRoute(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }

    @Test
    fun `bluetooth a2dp route returns bluetooth_a2dp`() {
        assertEquals("bluetooth_a2dp", classifyRoute(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun `bluetooth sco route returns bluetooth_a2dp`() {
        assertEquals("bluetooth_a2dp", classifyRoute(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
    }

    @Test
    fun `wired headphones route returns wired_headset`() {
        assertEquals("wired_headset", classifyRoute(setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
    }

    @Test
    fun `wired headset route returns wired_headset`() {
        assertEquals("wired_headset", classifyRoute(setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
    }

    @Test
    fun `mixed built-in and bluetooth returns unknown`() {
        assertEquals("unknown", classifyRoute(
            setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        ))
    }

    @Test
    fun `mixed built-in and wired returns unknown`() {
        assertEquals("unknown", classifyRoute(
            setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
        ))
    }

    @Test
    fun `unknown device type returns unknown`() {
        assertEquals("unknown", classifyRoute(setOf(AudioDeviceInfo.TYPE_USB_DEVICE)))
    }

    @Test
    fun `successful startTone returns complete playback metadata`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 10
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 25
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, 100) } returns true

        val player = ToneStartListeningCuePlayer(context)
        val result = player.playCue(StartListeningCueContext.FOREGROUND)

        assertTrue(result.started)
        assertEquals(StartListeningCueContext.FOREGROUND, result.context)
        assertEquals(AudioManager.STREAM_MUSIC, result.selectedStream)
        assertEquals(10, result.currentVolume)
        assertEquals(25, result.maxVolume)
        assertEquals("2026-07-cue-v1", result.policyVersion)
        assertNull(result.failureCategory)
    }

    @Test
    fun `playback failure reports stable failure category`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.getStreamVolume(any()) } returns 10
        every { audioManager.getStreamMaxVolume(any()) } returns 25
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, 100) } returns false

        val player = ToneStartListeningCuePlayer(context)
        val result = player.playCue(StartListeningCueContext.WAKE_WORD)

        assertFalse(result.started)
        assertEquals("playback_start_failed", result.failureCategory)
        assertEquals(StartListeningCueContext.WAKE_WORD, result.context)
        assertEquals(AudioManager.STREAM_ALARM, result.selectedStream)
    }

    @Test
    fun `ToneGenerator exception reports playback_exception`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.getStreamVolume(any()) } returns 10
        every { audioManager.getStreamMaxVolume(any()) } returns 25
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, 100) } throws RuntimeException("playback died")

        val player = ToneStartListeningCuePlayer(context)
        val result = player.playCue(StartListeningCueContext.CLOCK_ALERT)

        assertFalse(result.started)
        assertEquals("playback_exception", result.failureCategory)
    }

    @Test
    fun `release clears both cached generators`() {
        val audioManager = mockk<AudioManager>()
        every { audioManager.getStreamVolume(any()) } returns 10
        every { audioManager.getStreamMaxVolume(any()) } returns 25
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, 100) } returns true

        val player = ToneStartListeningCuePlayer(context)
        player.playCue(StartListeningCueContext.FOREGROUND)
        player.playCue(StartListeningCueContext.WAKE_WORD)

        player.release()

        verify(exactly = 2) { anyConstructed<ToneGenerator>().release() }
    }

    @Test
    fun `stream volume captured without mutation`() {
        val volumeSlot = slot<Int>()
        val maxVolumeSlot = slot<Int>()
        val audioManager = mockk<AudioManager>()
        every { audioManager.getStreamVolume(capture(volumeSlot)) } returns 10
        every { audioManager.getStreamMaxVolume(capture(maxVolumeSlot)) } returns 25
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        mockkConstructor(ToneGenerator::class)
        every { anyConstructed<ToneGenerator>().startTone(ToneGenerator.TONE_PROP_BEEP, 100) } returns true

        val player = ToneStartListeningCuePlayer(context)
        val result = player.playCue(StartListeningCueContext.FOREGROUND)

        assertEquals(AudioManager.STREAM_MUSIC, volumeSlot.captured)
        assertEquals(AudioManager.STREAM_MUSIC, maxVolumeSlot.captured)
        assertEquals(10, result.currentVolume)
        assertEquals(25, result.maxVolume)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }
}
