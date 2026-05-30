package com.kernel.ai.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Stub [RecognitionService] required by Android's [AssistantRoleBehavior] eligibility check.
 *
 * The OS validates that a package declaring a [android.service.voice.VoiceInteractionService]
 * also exposes a [RecognitionService] before granting the ASSISTANT role. Without this entry,
 * the role framework logs "unqualified voice interaction service" and immediately reverts the
 * selection to the previous holder.
 *
 * Jandal performs its own speech recognition via Sherpa-ONNX / Android native STT; this
 * stub exists solely to satisfy the system's metadata check at role-grant time. When Jandal
 * is the default assistant, the system may point `voice_recognition_service` at this stub,
 * so app-owned SpeechRecognizer callers must explicitly bind to a real external recognizer.
 */
private const val TAG = "KernelAI"

class JandalRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.w(TAG, "JandalRecognitionService invoked unexpectedly")
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
