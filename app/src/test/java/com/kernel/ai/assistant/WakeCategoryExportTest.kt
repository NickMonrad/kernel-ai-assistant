package com.kernel.ai.assistant

import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.inference.diagnostics.WakeCategory
import com.kernel.ai.core.voice.AcousticEventType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every category string the wake service and cue path emit must export as its own enum value,
 * not OTHER (#1870). Raw strings below are copied from WakeWordService and ToneStartListeningCuePlayer.
 */
class WakeCategoryExportTest {

    @Test
    fun `every emitted wake category exports as its own enum value`() {
        JandalEvents.install(File.createTempFile("jandal-wake-category", ".log"))
        val emitted = mapOf(
            "stt_no_final_result" to WakeCategory.STT_NO_FINAL_RESULT,
            "stt_unavailable" to WakeCategory.STT_UNAVAILABLE,
            "stt_stopped_without_result" to WakeCategory.STT_STOPPED_WITHOUT_RESULT,
            "transcript_collection_failed" to WakeCategory.TRANSCRIPT_COLLECTION_FAILED,
            "startup_collection_failed" to WakeCategory.STARTUP_COLLECTION_FAILED,
            "wake_capture_suspended" to WakeCategory.WAKE_CAPTURE_SUSPENDED,
            "route_activity_failed" to WakeCategory.ROUTE_ACTIVITY_FAILED,
            "session_cancelled" to WakeCategory.SESSION_CANCELLED,
            "session_failed" to WakeCategory.SESSION_FAILED,
            "record_audio_permission_missing" to WakeCategory.RECORD_AUDIO_PERMISSION_MISSING,
            "microphone_fgs_start_not_allowed" to WakeCategory.MICROPHONE_FGS_START_NOT_ALLOWED,
            "usage_access_missing" to WakeCategory.USAGE_ACCESS_MISSING,
            "usage_access_revoked" to WakeCategory.USAGE_ACCESS_REVOKED,
            "wake_model_unavailable" to WakeCategory.WAKE_MODEL_UNAVAILABLE,
            "camera_foreground_yield" to WakeCategory.CAMERA_FOREGROUND_YIELD,
            "tone_generator_unavailable" to WakeCategory.TONE_GENERATOR_UNAVAILABLE,
            "playback_start_failed" to WakeCategory.PLAYBACK_START_FAILED,
            "playback_exception" to WakeCategory.PLAYBACK_EXCEPTION,
        )
        JandalEvents.awaitWritesForTest()
        val mark = System.nanoTime() % 999_999_999L
        JandalEvents.record("test", "mark", "id" to mark)

        emitted.forEach { (raw, _) ->
            recordWakeJandalEvent(AcousticEventType.SERVICE_ERROR, generationId = 1L, sessionId = 1L) {
                mapOf("category" to raw)
            }
        }
        JandalEvents.awaitWritesForTest()
        val exported = JandalEvents.exportLines()
        val lines = exported.drop(exported.indexOfLast { " test mark " in it && "id=$mark" in it } + 1)

        emitted.forEach { (raw, expected) ->
            assertTrue(
                lines.any { Regex(" category=${expected.name}(\\s|$)").containsMatchIn(it) },
                "'$raw' did not export as ${expected.name}",
            )
        }
    }
}
