package com.kernel.ai.core.inference.diagnostics

import com.kernel.ai.core.inference.download.KernelModel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Enum constants used by real call sites must survive the sanitiser into the exported line (#1870). */
class JandalEventConstantsTest {

    @Test
    fun `download worker phase keeps model and phase constants`() {
        val model = KernelModel.entries.first()
        val line = buildJandalEvent(
            1, "download", "worker_phase",
            mapOf("model" to model, "phase" to WorkerPhase.RUNNING),
        ).toLine()

        assertTrue(line.contains("model=${model.name.uppercase()}"), line)
        assertTrue(line.contains("phase=RUNNING"), line)
    }

    @Test
    fun `download state kind keeps its constant`() {
        val line = buildJandalEvent(
            1, "download", "state",
            mapOf("state" to DownloadStateKind.DOWNLOADED),
        ).toLine()

        assertTrue(line.contains("state=DOWNLOADED"), line)
    }

    @Test
    fun `tts failure keeps its category constant`() {
        val line = buildJandalEvent(
            1, "tts", "speak_failed",
            mapOf("category" to TtsFailure.ENGINE_START),
        ).toLine()

        assertTrue(line.contains("category=ENGINE_START"), line)
    }
}
