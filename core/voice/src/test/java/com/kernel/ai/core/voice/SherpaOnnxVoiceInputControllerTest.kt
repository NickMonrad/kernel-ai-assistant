package com.kernel.ai.core.voice

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream

class SherpaOnnxVoiceInputControllerTest {
    private val assetManager: AssetManager = mockk()
    private val audioManager: AudioManager = mockk(relaxed = true)
    private val context: Context = mockk {
        every { assets } returns assetManager
        every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
    }
    private val controller = SherpaOnnxVoiceInputController(context)

    @Test
    fun `isAvailable returns false when all assets throw`() = runTest {
        every { assetManager.open(any<String>()) } throws IOException()

        assertFalse(controller.isAvailable())
    }

    @Test
    fun `isAvailable returns false when one asset throws`() = runTest {
        every { assetManager.open(match { it.endsWith("tokens.txt") }) } throws IOException()
        every { assetManager.open(match { !it.endsWith("tokens.txt") }) } returns InputStream.nullInputStream()

        assertFalse(controller.isAvailable())
    }

    @Test
    fun `startListening returns Unavailable when model files absent`() = runTest {
        every { assetManager.open(any<String>()) } throws IOException()

        val result = controller.startListening(VoiceCaptureMode.Command)

        assertInstanceOf(VoiceInputStartResult.Unavailable::class.java, result)
    }

    @Test
    fun `startListening returns Unavailable when Sherpa class not found`() = runTest {
        every { assetManager.open(any<String>()) } returns InputStream.nullInputStream()

        val result = controller.startListening(VoiceCaptureMode.Command)

        assertInstanceOf(VoiceInputStartResult.Unavailable::class.java, result)
    }
}
