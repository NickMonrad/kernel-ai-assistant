package com.kernel.ai.core.voice

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.Data
import androidx.work.WorkerParameters
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class VoicePackDownloadWorkerTest {
    @Test
    fun `persisted hidden Piper download is rejected before opening URL or touching pack files`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val notificationManager = mockk<NotificationManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager

        val inputData = Data.Builder()
            .putString(KEY_VOICE_NAME, SherpaPiperVoice.JennyDioco.name)
            .putString(KEY_VOICE_DOWNLOAD_URL, "not a URL")
            .build()
        val workerParameters = mockk<WorkerParameters>(relaxed = true)
        every { workerParameters.id } returns UUID.randomUUID()
        every { workerParameters.inputData } returns inputData

        val result = VoicePackDownloadWorker(context, workerParameters).doWork()

        assertEquals(
            "Voice is not available in this build",
            result.outputData.getString(KEY_VOICE_ERROR_MESSAGE),
        )
        verify(exactly = 0) { context.cacheDir }
        verify(exactly = 0) { context.filesDir }
    }
}
