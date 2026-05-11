package com.kernel.ai.feature.chat

import com.kernel.ai.feature.chat.model.ChatUiState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatScreenKeepAwakeTest {
    @Test
    fun `keeps screen awake while initial chat loading is in progress`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = ChatUiState.Loading,
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
    }

    @Test
    fun `keeps screen awake while model is loading inside ready chat state`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(isLoadingModel = true),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
    }

    @Test
    fun `keeps screen awake while response is generating or speaking`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(isGenerating = true),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Speaking("Reply"),
                voiceMode = ChatViewModel.VoiceMode.OneShot,
            ),
        )
    }

    @Test
    fun `keeps screen awake during one shot voice capture and back and forth mode`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Listening("hello"),
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = ChatViewModel.VoiceMode.OneShot,
            ),
        )
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = ChatViewModel.VoiceMode.BackAndForth,
            ),
        )
    }

    @Test
    fun `does not keep screen awake when chat is idle`() {
        assertFalse(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
        assertFalse(
            shouldKeepChatScreenAwake(
                uiState = ChatUiState.ModelsNotReady(isDownloading = true),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
    }

    private fun readyState(
        isGenerating: Boolean = false,
        isLoadingModel: Boolean = false,
    ) = ChatUiState.Ready(
        conversationId = "conv-1",
        conversationTitle = null,
        messages = emptyList(),
        isGenerating = isGenerating,
        isSpeakingResponse = false,
        inputText = "",
        error = null,
        isLoadingModel = isLoadingModel,
        showThinkingProcess = true,
    )
}
