package com.kernel.ai.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub [RecognitionService] required by Android's [AssistantRoleBehavior] eligibility check.
 *
 * The OS validates that a package declaring a [android.service.voice.VoiceInteractionService]
 * also exposes a [RecognitionService] before granting the ASSISTANT role. Without this entry,
 * the role framework logs "unqualified voice interaction service" and immediately reverts the
 * selection to the previous holder.
 *
 * Jandal performs its own speech recognition via Sherpa-ONNX / Whisper (on-device); this
 * service is never actually invoked for recognition. It exists solely to satisfy the system's
 * metadata check at role-grant time.
 */
class JandalRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Jandal does not use the Android SpeechRecognizer API path.
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
